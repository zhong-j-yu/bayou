package bayou.http;

import _bayou._tmp._TrafficDumpWrapper;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.async.AsyncIterator;
import bayou.async.Fiber;
import bayou.async.Promise;
import bayou.mime.Headers;
import bayou.ssl.SslConnection;
import bayou.tcp.TcpConnection;
import bayou.util.Result;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

class ImplConn
{
    HttpServer server;
    HttpServerConf conf;
    TcpConnection nbConn;
    Promise<Void> promise;

    _TrafficDumpWrapper dump;

    ImplConn(HttpServer server, TcpConnection nbConn)
    {
        this.server = server;
        this.conf = server.conf;
        this.nbConn = nbConn;

        this.dump = conf.trafficDumpWrapper;

        if(dump!=null)
            dump.print(connId(), " open [", nbConn.getPeerIp().getHostAddress(), "] ==\r\n");

        startFiber(); // do that in a named method, to have a better fiber trace.
    }

    void startFiber()
    {
        // all code are run in this fiber.
        new Fiber<Void>(nbConn.getExecutor(), fiberName(null), ()->
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
    TcpConnection tunnelConn;

    HttpResponse response;
    ImplConnResp xResp;


    enum Goto
    {
        NA,  // suspended while awaiting, or stopped after close()

        reqNew, reqNone, reqErr, reqBad, reqGood,

        respStart, respEnd, awaitReq,
        respPipeBody, respDrainMark, respFlushAll,  // xResp internal goto

    }

    void jump(Goto g)
    {
        assert Fiber.current()!=null;

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
        }
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
        // request framing here. so we don't do that. can't drain raw bytes from nbConn either since
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

        return xResp.startWrite();
    }

    Goto gotGoodRequest()
    {
        if(xReq.toDump!=null)
            dump.print(xReq.toDump);

        request = xReq.request;
        assert request.sealed; // sealed = good request

        xReq = null;

        HttpRequest.setFiberLocal(request);
        Fiber.current().setName(fiberName(request));

        String hvUpgrade = request.headers().get(Headers.Upgrade);
        if(hvUpgrade!=null) // should be rare
        {
            HttpUpgrader upgrader = server.findUpgrader(hvUpgrade);
            if(upgrader!=null)
                return tryUpgrade(upgrader);
        }

        return handleRequest();
    }

    Goto tryUpgrade(HttpUpgrader upgrader)
    {
        Async<HttpResponse> upgradeAsync;
        try
        {
            upgradeAsync = upgrader.tryUpgrade(request, nbConn); // should not throw
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
            promise.succeed(null); // this fiber ends
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

        if(request.state100==2) // we tried to write "100 Continue", write failed
            return close(null, "failed to write 100-continue response", null);
            // inbound/outbound status unknown. abort conn
        // no attempt to write resp in that case, since it'll probably fail too.

        if(request.state100==1)
        {
            if(tunnelConn!=null)
            {
                return drainReqBodyThenWriteResp();
                // CONNECT request with body and Expect:100-continue.
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

        if(request.stateBody==0 || request.stateBody==3) // most common. no body, or body is drained.
            return doWriteResp();

        // stateBody == 1 or 2

        // drain request body, then write resp

        return drainReqBodyThenWriteResp();

        // note: if state100==3, we still want to drain request body, in case client is single threaded.
    }

    private Goto drainReqBodyThenWriteResp()
    {
        ImplHttpRequestEntity entity = (ImplHttpRequestEntity)request.entity;
        final ImplHttpRequestEntity.Body body = entity.getBodyInternal();
        body.closed = false; // user may closed the body. clear it so we can read().

        // note: each body.read() will check confClientUploadTimeout, throughput, max body length
        Async<Void> drainAsync = AsyncIterator.forEach(body::read, input -> {}) // simply discard
            .timeout(conf.drainRequestTimeout);
        // after drain, body.closed stays true. ideally we should reset it to previous value.
        // no big deal, since soon we'll set request.responded, which will prevent body read.

        drainAsync.onCompletion(drainResult -> jump(afterReqBodyDrain(drainResult)));
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
            server.tunneller.doTunnel(this, nbConn, tunnelConn);

            request = null;
            response = null;
            tunnelConn = null;

            promise.succeed(null); // this fiber ends
            return Goto.NA;
        }

        // forbid reading request body from here on.
        request.responded = true;

        try
        {
            ArrayList<Cookie> jarCookies = (ArrayList<Cookie>)CookieJar.getAllChanges();
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

        return xResp.startWrite();
    }

    Goto responseEnded()  // fail or success
    {
        CookieJar.clearAll();
        Fiber.current().setName(fiberName(null));
        HttpRequest.setFiberLocal(null);

        if(xResp.bodyError!=null) // internal problem. should be interesting, needs to be investigated.
            HttpServer.logUnexpected(xResp.bodyError); // if it's not really interesting, disable logging for it

        if(xResp.connError!=null) // usually external network problem, uninteresting
            HttpServer.logErrorOrDebug(xResp.connError);
        // note: bodyErr/connErr means this is the last response

        doAccessLog(); // regardless of error
        // it uses request and xResp

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
        Async<Void> awaitReadable = nbConn.awaitReadable(/*accepting*/true);
        Result<Void> awaitReadableResult = awaitReadable.pollResult();
        if(awaitReadableResult==null) // more common
        {
            awaitReadable.timeout(conf.keepAliveTimeout)
                .onCompletion(cbNextRequestReadable);
            return Goto.NA;
        }
        else // readable now, not common
        {
            if(awaitReadableResult.isFailure()) // actually never happens
                return onNextRequestReadable(awaitReadableResult);

            // likely due to a prev unread() because of request pipelining.
            // to be fair to other connections, yield, do not goto reqNew directly.
            // note: even if sever is not accepting, the buffered data in nbConn is still accepted here
            Fiber.current().getExecutor().execute(() ->
                jump(Goto.reqNew));
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
                request, xResp.response, bodyWritten,
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

        Async<Void> closing = nbConn.close(drainTimeout);
        nbConn =null;

        if(closing.isCompleted()) // not rare
            promise.complete(closing.pollResult());
        else
            closing.onCompletion(promise::complete);

        return Goto.NA;
    }





    String connId()
    {
        return "== "+((nbConn instanceof SslConnection)?"https":"http")+" connection #"+nbConn.getId();
    }

    String fiberName(HttpRequest request)
    {
        InetAddress ip = request!=null? request.ip() : nbConn.getPeerIp();

        StringBuilder sb = new StringBuilder();
        sb.append((nbConn instanceof SslConnection)?"https":"http")
            .append(" connection #").append(nbConn.getId())
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
        return "== request #"+nbConn.getId()+"-"+reqId+" ==\r\n";
    }
    String respId()
    {
        return "== response #"+nbConn.getId()+"-"+reqId+" ==\r\n";
    }
}
