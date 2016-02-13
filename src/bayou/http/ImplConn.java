package bayou.http;

import _bayou._tmp._ByteBufferPool;
import _bayou._tmp._TrafficDumpWrapper;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.async.Fiber;
import bayou.async.Promise;
import bayou.mime.HeaderMap;
import bayou.mime.Headers;
import bayou.ssl.SslConnection;
import bayou.tcp.TcpConnection;
import bayou.util.Result;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

class ImplConn
{
    static final boolean FIBER = _Util.booleanProp(true, "bayou.http.server.fiber");
    // by default, each connection is associated with a fiber, for features like CookieJar.
    // can be disabled if app doesn't need fibers, to save a little CPU time.

    static final boolean PREV_HEADERS = _Util.booleanProp(false, "bayou.http.server.prevHeaders");
    // not too helpful to performance

    HttpServer server;
    HttpServerConf conf;
    TcpConnection tcpConn;
    Promise<Void> promise;

    _TrafficDumpWrapper dump;

    ImplConn(HttpServer server, TcpConnection tcpConn)
    {
        this.server = server;
        this.conf = server.conf;
        this.tcpConn = tcpConn;

        this.dump = conf.trafficDumpWrapper;

        if(dump!=null)
            dump.print(connId(), " open [", tcpConn.getPeerIp().getHostAddress(), "] ==\r\n");

        if(FIBER)
            startFiber(); // do that in a named method, to have a better fiber trace.
        else
            jump(Goto.reqNew);
    }

    void startFiber()
    {
        // all code are run in this fiber.
        new Fiber<Void>(tcpConn.getExecutor(), fiberName(null), ()->
        {
            promise = new Promise<>();
            promise.fiberTracePop();

            jump(Goto.reqNew);
            // based on browser tests, the connection is often readable here, right after establishment.
            // some browsers may open and hold some idle connections, without immediate 1st requests.
            // that's not blessed by spec. we don't care for that. we expect an immediate 1st request,
            // so the timeout here is requestHeadTimeout, not keepAliveTimeout. (they do have the same default values)

            return promise;
        });
    }

    int reqId;
    ImplConnReq xReq;
    ImplHttpRequest request;
    HeaderMap prevHeaders;  // for pipelined requests, cache prev header values.
    TcpConnection tunnelConn;

    HttpResponse response;
    ImplConnResp xResp;


    enum Goto
    {
        NA,  // suspended while awaiting, or stopped after close()

        reqNew, reqNone, reqErr, reqBad, reqGood,

        respStart, respWrite, respEnd, awaitReq,
        respPipeBody, respDrainMark, respFlushAll,  // xResp internal goto

    }

    void jump(Goto g)
    {
        if(FIBER) assert Fiber.current()!=null;

        try
        {
            while(g!=Goto.NA)
                g = execOne(g);
        }
        catch (Exception|Error t)
        {
            // unexpected exception, most likely from user code. nothing we can do.
            // the problem must be logged and investigated.
            HttpServer.logUnexpected(t);
            close(null, "unexpected error: ", t);
            return;
        }

        if(PIPELINE && flush==1) // delayed flush
            flush1();
    }
    Goto execOne(Goto g)
    {
        switch(g)
        {
            case reqNew  : return startNewRequest();
            case reqNone : return noNewRequests();
            case reqErr  : return errorReadingRequest();
            case reqBad  : return gotBadRequest();
            case reqGood : return gotGoodRequest();

            case respStart : return startResponding();
            case respWrite : return responseWrite();
            case respEnd   : return responseEnded();
            case awaitReq  : return awaitNewRequest();
            //
            case respPipeBody  : return xResp.pipeBody();
            case respDrainMark : return xResp.drainMark();
            case respFlushAll  : return xResp.flushAll();

            default: throw new AssertionError();
        }
    }



    Goto startNewRequest()
    {
        reqId += 1;

        xReq = new ImplConnReq(this);

        return xReq.read();
    }

    Goto noNewRequests()
    {
        // no bytes read, EOF. client disconnect before next request. normal condition.
        // if ssl, if client didn't close_notify, read() will throw and we won't reach here.
        //         if client did close_notify, we don't respond close_notify.

        xReq = null;

        return close(null, "closed by client", null);
    }
    Goto errorReadingRequest()
    {
        // error when reading request head (in the beginning or in the middle of the head)
        // most likely network error. just forget this request as if it didn't happen.
        // the connection is probably broken, can't write anything back.

        // note however, if error is timeout, it may not be serious. we could write 408 back.
        // that informs client that it can safely retry the request (even for POST).
        // we don't do that now. request head is so small, timeout is very rare,
        // it's probably due to a severe network problem, not likely recoverable.

        assert xReq.readError!=null;
        HttpServer.logErrorOrDebug(xReq.readError);  // most likely a checked network exception

        if(xReq.toDump!=null)
        {
            xReq.toDump.add("<ERROR>\r\n\r\n");
            dump.print(xReq.toDump);
        }

        Exception readError = xReq.readError;

        xReq = null;

        return close(null, "error while reading request head: ", readError);
    }

    Goto gotBadRequest()
    {
        // request head is illegal somehow, syntactically or semantically.
        //
        // connection is ok, we play nice and try to write back some error message.
        // usually, before writing the response, we should drain the request. but we can't trust the
        // request framing here. so we don't do that. can't drain raw bytes from tcpConn either since
        // we can't expect an EOF. so we just imm write the response. since the error response is small,
        // write should succeed, even if client isn't reading. server then drain inbound. if client is
        // still writing, the drain process helps client to finish the write step. client then probably
        // will read the error response, then closes the connection, which ends server's drain process.
        //
        // even if write fails, not a big loss, since this is a bad request.

        if(xReq.toDump!=null)
        {
            xReq.toDump.add("<BAD REQUEST>\r\n\r\n");
            dump.print(xReq.toDump);
        }

        request = xReq.request; // maybe incomplete, any field can be missing, including "method"
        assert !request.sealed; // sealed = good request

        HttpResponseImpl resp = xReq.errorResponse;
        assert resp!=null;  // there's always an error message

        xReq = null;

        xResp = ImplRespMod.modErr(this, request, resp); // no throw. is last response.
        if(dump!=null)
            xResp.dumpResp();
        // we dump the whole response head here, but actual write may fail later

        return Goto.respWrite;
    }

    Goto gotGoodRequest()
    {
        if(xReq.toDump!=null)
            dump.print(xReq.toDump);

        request = xReq.request;
        assert request.sealed; // sealed = good request

        xReq = null;

        if(FIBER) HttpRequest.setFiberLocal(request);
        if(FIBER) Fiber.current().setName(fiberName(request));

        HttpUpgrader upgrader = server.findUpgrader(request.headers);
        if(upgrader!=null)
            return tryUpgrade(upgrader);

        return handleRequest();
    }

    Goto tryUpgrade(HttpUpgrader upgrader)
    {
        Async<HttpResponse> upgradeAsync;
        try
        {
            upgradeAsync = upgrader.tryUpgrade(request, tcpConn); // should not throw
            if(upgradeAsync==null)
                upgradeAsync = Async.success((HttpResponse)null);
        }
        catch (RuntimeException|Error e)
        {
            // upgrader may think it has taken over the connection and start writing to it.
            // so we don't try here to write an internal error http response to client.
            // just log the error and close the connection.
            throw e;
        }

        Result<? extends HttpResponse> upgradeResult0 = upgradeAsync.pollResult();
        if(upgradeResult0!=null) // usually
            return tryUpgrade2(upgradeResult0);

        upgradeAsync.onCompletion(upgradeResult ->
            jump(tryUpgrade2(upgradeResult)));
        return Goto.NA;
    }

    Goto tryUpgrade2(Result<? extends HttpResponse> upgradeResult)
    {
        try
        {
            response = upgradeResult.getOrThrow();
        }
        catch (Exception e)
        {
            // we don't know what's wrong. log and close
            HttpServer.logUnexpected(e);
            close(null, "unexpected error: ", e);
            return Goto.NA;
        }

        if(response==null) // good, upgrader takes over the connection. I'm retired
        {
            request=null;
            if(FIBER) promise.succeed(null); // this fiber ends
            return Goto.NA;
        }

        // failed to upgrade to WebSocket, conn is still normal http. write the response
        return Goto.respStart;
    }


    Goto handleRequest()
    {
        Async<HttpResponse> respAsync;
        try
        {
            respAsync = server.handler.handle(request); // should not throw
            if(respAsync==null)
                throw new NullPointerException("null returned from "+server.handler);
        }
        catch (RuntimeException|Error e)
        {
            respAsync = HttpResponse.internalError(e);
        }

        if(request.method.equals("CONNECT"))
            respAsync = respAsync.then( resp->server.tunneller.tryConnect(request, resp, this) );
        // if the CONNECT request has a body, and it's not read by app, it becomes tunnel payload.

        Result<? extends HttpResponse> respResult = respAsync.pollResult();
        if(respResult!=null) // immediate completion is quite common
            return handlerDone(respResult);

        // TBA: server.stopAll() should cancel respAsync.
        // probably not big deal - VM is probably shutting down anyway.
        respAsync.onCompletion(result -> jump(handlerDone(result)));
        return Goto.NA;
    }

    Goto handlerDone(Result<? extends HttpResponse> respResult)
    {
        try
        {
            response = respResult.getOrThrow();
            if(response==null)
                throw new NullPointerException("response==null");
        }
        catch (Exception e)
        {
            response = HttpResponse.internalError(e);
        }

        return Goto.respStart;
    }

    Goto startResponding()
    {
        // we may need to drain request body before writing the response
        if(request.entity==null) // common
            return doWriteResp();

        ImplHttpEntity.Body body = request.entity.body; // non-null

        // forbid app from reading request body from here,
        // otherwise there's concurrency and semantic problems if we drain request body here.
        body.close();

        if(request.state100==2) // we tried to write "100 Continue", write failed
            return close(null, "failed to write 100-continue response", null);
            // inbound/outbound status unknown. abort conn
        // no attempt to write resp in that case, since it'll probably fail too.

        if(request.state100==1)
        {
            if(tunnelConn!=null)
            {
                return drainReqBodyThenWriteResp(body);
                // CONNECT request with body and Expect:100-continue. (entity!=null)
                // this is highly unlikely in practice.
                // we must drain the request body before tunneling.
            }
            else
            {
                return doWriteResp();  // no drain
                // client expects 100 before sending the body. app responds without reading request body.
                // in this case, it's preferred by both ends that the request body is not sent.
                // client may send body anyway without seeing a 100 response, but then it must have concurrent read/write flows.
                // response will be tagged Connection:close. after response, we'll still try to drain inbound data,
                // a good client should imm close the connection after receiving the response, so the draining ends imm too.
                // consider sick case: Content-Length:0 and Expect:100-continue
            }
        }

        // state100 == 0 or 3.

        if(body.eof()) // common
            return doWriteResp();

        // drain request body, then write resp

        return drainReqBodyThenWriteResp(body);

        // note: if state100==3, we still want to drain request body, in case client is single threaded.
    }

    private Goto drainReqBodyThenWriteResp(ImplHttpEntity.Body body)
    {
        body
            .drain()
            .timeout(conf.drainRequestTimeout)
            .onCompletion(drainResult -> jump(afterReqBodyDrain(drainResult)))
        ;
        return Goto.NA;
    }
    Goto afterReqBodyDrain(Result<Void> drainResult)
    {
        Exception e = drainResult.getException();
        if(e!=null)
        {
            // network error; confDrainRequestTimeout; or constrains on upload timeout/length/throughput
            HttpServer.logErrorOrDebug(e);
            return close(null, "failed to drain request body: ", e);
        }

        // drained successfully
        return doWriteResp();
    }

    Goto doWriteResp()
    {
        if(tunnelConn!=null)
        {
            server.tunneller.doTunnel(this, tcpConn, tunnelConn);

            request = null;
            response = null;
            tunnelConn = null;

            if(FIBER) promise.succeed(null); // this fiber ends
            return Goto.NA;
        }

        try
        {
            List<Cookie> jarCookies = FIBER? (ArrayList<Cookie>)CookieJar.getAllChanges() : Collections.emptyList();
            // leave fiber local cookie jars. may be needed by response body. clear them after response is written

            xResp = ImplRespMod.modApp(this, request, response, jarCookies);  // may throw. may be last response
        }
        catch (Exception e) // something wrong in the user response, e.g. illegal header value
        {
            xResp = ImplRespMod.modErr(this, request, HttpResponse.internalError(e));  // no throw. last response
        }

        response = null;  // we won't be needing it


        if(dump!=null)
            xResp.dumpResp();
        // we dump the whole response head here, but actual write may fail later

        return Goto.respWrite;
    }

    Goto responseWrite()
    {
        if(!PIPELINE)
            return xResp.startWrite();

        switch(flush)
        {
            case 0 :
                return xResp.startWrite();
            case 1 :
                flush=0;
                return xResp.startWrite();
            case 2 :
                flush=3;
                return Goto.NA;
            default :
                throw new AssertionError();
        }
    }

    Goto responseEnded()  // fail or success
    {
        if(FIBER) CookieJar.clearAll();
        if(FIBER) Fiber.current().setName(fiberName(null));
        if(FIBER) HttpRequest.setFiberLocal(null);

        if(xResp.bodyError!=null) // internal problem. should be interesting, needs to be investigated.
            HttpServer.logUnexpected(xResp.bodyError); // if it's not really interesting, disable logging for it

        if(xResp.connError!=null) // usually external network problem, uninteresting
            HttpServer.logErrorOrDebug(xResp.connError);
        // note: bodyErr/connErr means this is the last response

        doAccessLog(); // regardless of error
        // it uses request and xResp

        if(PREV_HEADERS) prevHeaders = request.headers;
        request = null;
        ImplConnResp xRespL = xResp;
        xResp = null;

        if(xRespL.isLast) // close conn
        {
            if(xRespL.connError!=null)  // conn probably broken during write
                return close(null, "error while writing response: ", xRespL.connError);     // no attempt to drain conn.

            if(xRespL.bodyError!=null && _Util.unchecked(xRespL.bodyError))  // really serious app logic error.
                return close(null, "error while reading response body: ", xRespL.bodyError);
                // no draining. don't care much about resp/conn/client
            // else benign body errors: probably not app logic error.
            //     we'll still want the client to receive whatever we've got. drain before close

            // drain client data before close, to avoid RST problem.
            return close(conf.closeTimeout, "closed by server", null);
        }

        // keep alive connection, go on to next request
        return Goto.awaitReq; // most fibers are in this state
    }

    Goto awaitNewRequest()
    {
        // typically client doesn't do pipeline, so it's unlikely connection is readable here.
        //   an imm read() at this point probably will fail, so we better awaitReadable() here.
        Async<Void> awaitReadable = tcpConn.awaitReadable(/*accepting*/true);
        Result<Void> awaitReadableResult = awaitReadable.pollResult();
        if(awaitReadableResult==null) // more common
        {
            if(PREV_HEADERS) prevHeaders = null;
            awaitReadable.timeout(conf.keepAliveTimeout)
                .onCompletion(cbNextRequestReadable);
            return Goto.NA;
        }
        else // readable now, not common
        {
            if(awaitReadableResult.isFailure()) // actually never happens
                return onNextRequestReadable(awaitReadableResult);

            // likely due to a prev unread() because of request pipelining.
            // to be fair to other connections, yield, do not goto reqNew directly, unless PIPELINE=true.
            // note: even if sever is not accepting, the buffered data in tcpConn is still accepted here

            if(PIPELINE)
                return Goto.reqNew;

            Executor executor = FIBER ? Fiber.current().getExecutor() : tcpConn.getExecutor();
            executor.execute(() -> jump(Goto.reqNew));
            return Goto.NA;
        }
    }

    Consumer<Result<Void>> cbNextRequestReadable = result ->
        jump(onNextRequestReadable(result));

    Goto onNextRequestReadable(Result<Void> r)
    {
        Exception exception = r.getException();
        if(exception==null)
        {
            return Goto.reqNew;
        }
        else
        {
            HttpServer.logErrorOrDebug(exception);
            String closeReason = (exception instanceof TimeoutException)
                ? "keep alive timeout: "
                : "error while waiting for next request: ";
            return close(null, closeReason, exception);
        }
    }

    void doAccessLog()
    {
        if(!request.sealed) // a hackish way to know that request is bad; fields may be missing
            return;         // can't and won't log. request is not really served anyway
        // if we are to log bad requests, we have to be very careful reading its fields.

        HttpAccessLoggerWrapper printer = conf.accessLoggerWrapper;
        if(printer==null)  // no access log
            return;

        long bodyWritten = xResp.writtenTotal - xResp.headLength;
        if(bodyWritten<0)  // i.e. even head isn't completely written
            bodyWritten=0;

        HttpAccess entry = new HttpAccess(
                request, xResp.asHttpResponse(), bodyWritten,
                request.timeReceived, xResp.writeT0, System.currentTimeMillis(),
                xResp.connError);

        printer.print(entry);
    }


    Goto close(Duration drainTimeout, String reason, Throwable exception)
    {
        if(dump!=null)
            dump.print(
                connId(), " closed == ",
                reason==null?"":reason,
                exception==null?"":exception.toString(),
                "\r\n"
            );

        if(tunnelConn!=null)
        {
            tunnelConn.close(null);
            tunnelConn=null;
        }

        if(PIPELINE) flush=0;

        Async<Void> closing = tcpConn.close(drainTimeout);
        tcpConn =null;

        if(FIBER)
        {
            if(closing.isCompleted()) // not rare
                promise.complete(closing.pollResult());
            else
                closing.onCompletion(promise::complete);
        }

        return Goto.NA;
    }





    String connId()
    {
        return "== "+((tcpConn instanceof SslConnection)?"https":"http")+" connection #"+ tcpConn.getId();
    }

    String fiberName(HttpRequest request)
    {
        InetAddress ip = request!=null? request.ip() : tcpConn.getPeerIp();

        StringBuilder sb = new StringBuilder();
        sb.append((tcpConn instanceof SslConnection)?"https":"http")
            .append(" connection #").append(tcpConn.getId())
            .append(" [").append(ip.getHostAddress()).append("]");
        if(request!=null)
        {
            sb.append(' ').append(request.method());
            String uri = request.uri();
            if(uri.length()>40)
                uri = uri.substring(0, 37) + "...";
            sb.append(' ').append(uri);
        }
        return sb.toString();
    }


    String reqId()
    {
        return "== request #"+ tcpConn.getId()+"-"+reqId+" ==\r\n";
    }
    String respId()
    {
        return "== response #"+ tcpConn.getId()+"-"+reqId+" ==\r\n";
    }


    /////////////////////////////////////////////////////////////////////////////////////////////

    static final boolean PIPELINE = _Util.booleanProp(false, "bayou.http.server.pipeline");
    // if enabled, give preference to pipelined requests in a connection; multiple responses may be buffered.
    //
    // pipeline isn't common in browsers. most are half-duplex: drain the response before writing next request.
    // this class was written with that in mind, because half-duplex is simpler to code.
    // we patched this class with a little hack to batch-process pipelined requests.
    // a better impl would be 2 concurrent flows, for inbound and outbound, like we do in HttpClient.
    // and we should expose HttpServerConnection to app for low-level request/response handling. TBA.


    byte flush; // [0] none [1] delayed  [2] awaitWritable  [3] new response

    void flush1()
    {
        try
        {
            tcpConn.write();
        }
        catch (Exception e)
        {
            // something wrong with the connection. we don't need to handle it here.
            // the problem will manifest itself again, e.g. awaitReadable() should fail.
            HttpServer.logErrorOrDebug(e);
            flush=0;
            return;
        }

        if(tcpConn.getWriteQueueSize()==0)
        {
            flush=0;
            return;
        }

        // not all data are flushed. await writable and try again.
        flush=2;
        tcpConn.awaitWritable().timeout(conf.writeTimeout)
            .onCompletion(this::flush2);
        // here we have 2 concurrent flows - main-flow and flush-flow.
        // they are on the same thread; no lock is needed.
        // but they can't both do write, especially, no concurrent awaitWritable(). flush-flow controls writes.
        // main-flow may alter the state before flush-flow wakes up from awaitWritable()
        //   -> 3  a new response is added
        //   -> 0  close called

    }

    void flush2(Result<Void> result)
    {
        if(flush==0)
            return;

        if(flush==3)
        {
            flush=0;
            jump(Goto.respWrite);  // becomes main-flow
            return;
        }

        flush1();
    }





}
