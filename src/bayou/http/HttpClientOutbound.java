package bayou.http;

import _bayou._bytes._ErrorByteSource;
import _bayou._http._HttpUtil;
import _bayou._str._CharSeqSaver;
import _bayou._tmp.*;
import bayou.async.Async;
import bayou.async.Promise;
import bayou.bytes.ByteSource;
import bayou.mime.HeaderMap;
import bayou.mime.Headers;
import bayou.tcp.TcpConnection;
import bayou.util.End;
import bayou.util.Result;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static bayou.mime.Headers.Content_Length;
import static bayou.mime.Headers.Transfer_Encoding;

class HttpClientOutbound
{

    final HttpClientConnection conn;
    final TcpConnection tcpConn;

    final boolean sendAbsoluteUri;

    final long outboundBufferSize;

    HttpClientOutbound(HttpClientConnection conn, boolean sendAbsoluteUri)
    {
        this.conn = conn;
        this.tcpConn = conn.tcpConn;

        this.sendAbsoluteUri = sendAbsoluteUri;

        this.outboundBufferSize = 16*1024; // >0
        // we may make this configurable. but requests are usually small, so it's not important now.

        flushMark = outboundBufferSize; // >0

    }


    // stage
    // ----------------------------------------------------------------------------------------------------

    final Object lock(){ return this; }
    // we don't really worry about lock contention. usually all actions are done on the selector flow.

    Exception error;
    //TBA: we may send this exception to multiple consumers. problematic if it's mutable.

    boolean closeCalled;

    ArrayDeque<OutgoingMessage> messages; // null iff empty

    ArrayDeque<ReqInfo> reqInfoQ = new ArrayDeque<>();
    ReqInfo pollReqInfo()
    {
        synchronized (lock())
        {
            return reqInfoQ.pollFirst();
        }
    }


    static final int
        pump_running =0,
        pump_awaitingFrame =1,
        pump_awaitingWritable =2,
        pump_retired =3;

    int pumpState = pump_awaitingFrame;
    Async<Void> awaitWritableAsync; // non-null iff awaitingWritable





    // pump: fetch frame, write to tcpConn
    // ----------------------------------------------------------------------------------------------------

    long flushMark; // must be >0 initially. later becomes 0 when there are no more frames

    Runnable _pump = this::pump;
    void pump()
    {
        // lock is not held here
        try
        {
            pumpE();
        }
        catch (Exception e) // from tcpConn; or unexpected RuntimeException from user code
        {
            synchronized (lock())
            {
                if(error ==null)
                    setError(lock(), e);

                pump_retire(lock(), false);
            }
        }
    }

    void pumpE() throws Exception
    {
        while(true) // fetch & flush loop
        {
            // [flush]
            long wr = tcpConn.getWriteQueueSize();
            if(wr> flushMark)
            {
                tcpConn.write(); // throws
                wr = tcpConn.getWriteQueueSize();

                if(wr> flushMark) // await writable, then try write again
                {
                    synchronized (lock())
                    {
                        if(error !=null)
                        {
                            pump_retire(lock(), false);
                            return;
                        }

                        set_awaitWritable(lock());  // then goto [flush]
                    }
                    return;
                }
            }

            // wr<=flushMark
            if(flushMark ==0) // wr=flushMark=0, all outbound bytes are written successfully.
            {
                synchronized (lock())
                {
                    // error==null here
                    pump_retire(lock(), true);
                }
                return;
            }

            // [fetch]
            synchronized (lock())
            {
                int f = fetchFrames(lock());
                // may throw unexpectedly from user code

                if(f== fetch_error)
                {
                    pump_retire(lock(), false);
                    return;
                }

                if(f== fetch_success) // a frame is queued to tcpConn
                {
                    continue;        // write if wr>flushMark
                }

                if(f== fetch_noMoreFrames) // close called.
                {
                    flushMark =0;
                    continue;             // flush all bytes
                }

                assert f== fetch_stall;
                // try write bytes already queued (maybe none)
                tcpConn.write(); // throws
                wr = tcpConn.getWriteQueueSize();
                if(wr>0)
                {
                    // both fetch and write stall.
                    // await writable only. don't care if a frame is available before tcpConn is writable
                    set_awaitWritable(lock());  // then goto [fetch]
                    return;
                }
                else // wr==0
                {
                    // fetch stall only. await frame.
                    pumpState = pump_awaitingFrame; // then goto [fetch]
                    return;
                }
            }

        } // fetch & flush loop

    } // pumpE()


    void set_awaitWritable(Object lock)
    {
        pumpState = pump_awaitingWritable;
        awaitWritableAsync = tcpConn.awaitWritable(); // no timeout. it's up to higher layer to cancel the request.
        awaitWritableAsync.onCompletion(onConnWritable);
    }

    Consumer<Result<Void>> onConnWritable = result ->
    {
        Exception e = result.getException();
        synchronized (lock())
        {
            assert pumpState == pump_awaitingWritable;
            pumpState = pump_running;
            awaitWritableAsync =null;

            if(error !=null)
            {
                pump_retire(lock(), false);
                return;
            }

            if(e!=null)
            {
                setError(lock(), e);
                pump_retire(lock(), false);
                return;
            }

            // goto pump()
        }

        pump();
    };

    void pump_retire(Object lock, final boolean graceful)
    {
        // graceful iff error==null
        pumpState = pump_retired;
        conn.tcpConn_close(graceful);
    }






    // fetch frame
    // -----------------------------------------------------------------


    // get a frame from stage, queue it into tcpConn.
    static final int
        fetch_success =0,
        fetch_stall =1,
        fetch_noMoreFrames = 2,
        fetch_error = 3;

    int fetchFrames(Object lock)
    {
        if(error !=null)
        {
            return fetch_error;
        }
        else if(messages !=null) // not empty
        {
            OutgoingMessage msg = messages.peekFirst();
            assert msg!=null;
            return msg.fetchFrame(lock); // success, stall, error
        }
        else if(closeCalled)
        {
            return fetch_noMoreFrames;
        }
        else // stage is empty, new frame/msg may arrive later
        {
            return fetch_stall;
        }
    }


    class OutgoingMessage
    {
        int reqId;
        HttpRequest req;

        /** same as {@link ReqInfo#await100} */
        Async<Void> await100;

        ByteSource body; // null after EOF

        Async<ByteBuffer> pendingRead;

        Promise<Void> promise;

        Result<ByteBuffer> read(Object lock) // return null if stall
        {
            if(req!=null)
            {
                HttpEntity entity = req.entity();
                ByteBuffer reqHead = makeHead(req, entity, sendAbsoluteUri);
                req = null;

                _TrafficDumpWrapper dump = conn.conf.trafficDumpWrapper;
                if(dump!=null)
                    dump.print("== request #"+tcpConn.getId()+"-"+reqId, " ==\r\n",
                        _ByteBufferUtil.toLatin1String(reqHead));


                if(entity!=null)
                {
                    if(await100==null)
                        body = getBody(entity, outboundBufferSize);
                    else
                        await100
                            .timeout(conn.conf.await100Timeout)
                            .onCompletion(result -> on100(result, entity));
                }
                else
                {
                    if(await100!=null) // expect 100, but no body. app should not create this situation
                    {
                        await100.cancel(new _ControlException(""));
                        await100=null;
                    }
                }

                return Result.success(reqHead);
            }

            if(await100!=null)
                return null;

            if(body==null)
                return Result.failure(End.instance());

            if(pendingRead==null)
                pendingRead = body.read();
            Result<ByteBuffer> result = pendingRead.pollResult();
            if(result!=null)
                pendingRead=null;
            else
                pendingRead.onCompletion(onMsgReadComplete);
            return result;
        }


        /** result: see {@link ReqInfo#await100} */
        void on100(Result<Void> result, HttpEntity entity)
        {
            synchronized (lock())
            {
                if(error!=null)
                    return;

                if(result.getException() instanceof TimeoutException) // timeout, or 100-resp received. send body.
                    body = getBody(entity, outboundBufferSize); // we delay calling entity.body() till it's necessary
                else // final response is received first; do not send request body. outbound is corrupt.
                    body = new _ErrorByteSource(new _ControlException("request body not sent; `Expect:100-continue` failure"));

                await100=null;
                resumePumpIfAwaitingFrame(lock());

                // note: if timeout is reached, then final response is received while request body is still sending,
                // we don't know whether the server is needing the request body, so we'll finish sending it.
            }
        }

        int fetchFrame(Object lock)
        {
            Result<ByteBuffer> readResult = read(lock);  // may throw unexpectedly from user code
            if(readResult==null) // read pending
                return fetch_stall;

            Exception e = readResult.getException();
            if(e instanceof End)
            {
                messages.removeFirst(); // remove myself
                if(messages.isEmpty())
                    messages =null; // die young

                if(body!=null)
                {
                    body.close();
                    body = null;
                }

                promise.succeed(null);
                // a bit premature to complete promise, since the frame hasn't been written yet.

                return fetch_success; // of 0 bytes
            }
            else if(e!=null) // outbound corrupt
            {
                setError(lock, e);  // will clean up all messages
                return fetch_error;
            }

            ByteBuffer bb = readResult.getValue();
            assert bb!=null;
            tcpConn.queueWrite(bb);
            return fetch_success;
        }

        boolean done(Object lock)
        {
            return promise.isCompleted();
        }

        void abort(Object lock, Exception e)
        {
            if(await100!=null)
                await100.cancel(e);

            if(body!=null) // close it
            {
                if(pendingRead==null)
                    body.close();
                else // close src after read completes
                {
                    pendingRead.cancel(e);
                    pendingRead.onCompletion(byteBufferResult -> body.close());
                }
            }

            promise.fail(error);
        }
    }










    // source
    // -----------------------------------------------------------------

    Consumer<Result<ByteBuffer>> onMsgReadComplete = byteBufferResult -> {
        synchronized (lock())
        {
            if(error !=null)
                return;

            // result may be failure, but we don't need to check it here, pump will see the failure.
            resumePumpIfAwaitingFrame(lock());
            // this wakeup can be spurious, it's possible that the pump has already fetched the result.
        }
    };

    void resumePumpIfAwaitingFrame(Object lock)
    {
        if(pumpState != pump_awaitingFrame)
            return;

        // wakeup can be spurious, i.e. next fetchFrame() still stalls
        pumpState = pump_running;
        tcpConn.getExecutor().execute(_pump);
    }

    int reqId;

    Async<Void> stageMessage(HttpRequest req)
    {
        synchronized (lock())
        {
            if(closeCalled)
                return Result.failure(new AsynchronousCloseException());

            if(error !=null)
                return Result.failure(error);

            reqId++;

            ReqInfo reqInfo = new ReqInfo(reqId, req, conn.conf.await100Timeout!=null);
            reqInfoQ.addLast(reqInfo);

            OutgoingMessage msg = new OutgoingMessage();
            msg.reqId = reqId;
            msg.req = req;
            msg.promise = new Promise<>();
            msg.promise.onCancel(reason -> cancelWrite(msg, reason));
            msg.await100 = reqInfo.await100;

            if(messages ==null)
            {
                messages = new ArrayDeque<>();
                messages.addLast(msg);
                resumePumpIfAwaitingFrame(lock());
                // this wakeup can be spurious if next msg.read() stalls
            }
            else
            {
                assert !messages.isEmpty();
                messages.addLast(msg);
                // even if pump is awaitingFrame, it is waiting on a previous message to complete read().
                // the arrival of this message has no effect on pump state.
            }

            return msg.promise;
        }
    }





    static class ReqInfo // min request info required by the corresponding response(s)
    {
        int id;
        String method;
        boolean isLastRequest;

        /** same as {@link OutgoingMessage#await100} */
        Async<Void> await100;
        // convention: it's completed by cancel() (possibly multiple attempts from diff parties)
        // results: TimeoutException - timeout, or 100-resp received; send req body
        //           other Exception - final(non-1xx) resp received first. do NOT send req body
        // it's tacky to use TimeoutException for 100-resp; but timeout and 100-resp have the same effect to us.

        ReqInfo(int id, HttpRequest req, boolean waitFor100)
        {
            this.id = id;
            this.method = req.method();
            this.isLastRequest = isLast(req);

            if(waitFor100 && expect100(req.header(Headers.Expect)) )
            {
                Promise<Void> p100 = new Promise<>();
                p100.fiberTracePop();
                p100.onCancel(p100::fail);
                await100 = p100;
            }
        }
    }

    static boolean isLast(HttpRequest req)
    {
        String hvConnection = req.header(Headers.Connection);
        if(_HttpUtil.containsToken(hvConnection, "close"))
            return true;

        if(req.httpVersion().equals("1.0")) // we need positive "Connection: keep-alive" header
            if(!_HttpUtil.containsToken(hvConnection, "keep-alive"))
                return true;

        return false;
    }

    static boolean expect100(String hvExpect)
    {
        if(hvExpect==null) return false;
        return hvExpect.equalsIgnoreCase("100-continue");
        // this is not exactly correct, since Expect can be a list.
        // however, at this point, only 100-continue is defined and used in practice.
        // even if we get it wrong here (i.e. returning a `false` while 100-continue is present in the list)
        // it's not serious, since it's ok for client to send body immediately regardless of Expect header
    }





    // error, cancel, close
    // -----------------------------------------------------------------------------------------



    void cancelWrite(OutgoingMessage msg, Exception reason)
    {
        synchronized (lock())
        {
            if(error !=null) // msg was either sent, or aborted.
                return;

            if(closeCalled)
                return;
            // here, closeCalled && error==null, graceful close, msg must have been sent
            //   we don't really need this clause; msg.done() should be true here, i.e. the next clause.

            if(msg.done(lock()))  //  <==> `messages` does not contain `msg`
                return;  // msg has been written; cancel has no effect

            // cancel an unwritten message. corrupts the entire outbound.
            // note: messages queued before this one are all cancelled as well. that is not good.
            //       we don't worry about it now. in practice, it's probably not likely that
            //       a newer message is canceled before an older message is canceled.

            setError(lock(), reason);

            if(pumpState == pump_awaitingFrame)
                pump_retire(lock(), false);
            else if(pumpState == pump_awaitingWritable)
                awaitWritableAsync.cancel(reason); // wakeup pump to see the error
            // else if running, pump will see the error soon
            // else retired - not possible here
        }
    }

    // note on pumpState==pump_awaitingFrame  in cancelWrite()/close()
    // in a more formal treatment, we should resume pump(), which sees error/closeCalled then retires.
    // here we do the shortcut and directly change the state to retired.


    void close()
    {
        synchronized (lock())
        {
            if(closeCalled)
                return;
            closeCalled = true;

            if(error !=null)
            {
                assert pumpState != pump_awaitingFrame;
            }
            else if(messages ==null)
            {
                // close() after all messages have been sent. graceful close. no error here.

                if(pumpState == pump_awaitingFrame) // wr==0 here
                    pump_retire(lock(), true);
                // else if running/awaitingWritable, let pump finish on its own.
                //     pump will try to flush all queued bytes then retire gracefully.
                //     pump may encounter tcpConn error before flushing is done.
                //     (we don't have a timeout for this process though, it may take forever)
                // else retired (? not possible here?)
            }
            else
            {
                // close() before messages are sent. abortive close. error. cancel messages.
                Exception ex = new AsynchronousCloseException();
                setError(lock(), ex);

                if(pumpState == pump_awaitingFrame)
                    pump_retire(lock(), false);
                else if(pumpState == pump_awaitingWritable)
                    awaitWritableAsync.cancel(ex); // wakeup pump to see the error
                // else if running, pump will see the error soon.
                // else retired - not possible here
            }
        }
    }



    void setError(Object lock, Exception e)
    {
        assert error ==null;
        error = e;

        if(messages !=null)
        {
            for(final OutgoingMessage msg : messages)
                msg.abort(lock, e);

            messages =null;
        }

        // keep reqInfoQ. inbound can still use it.
    }



    //////////////////////////////////////////////////////////////////////////////////////////////////////////

    // [chunked request body]
    // most likely it'll not work. but we don't police it at this layer.
    // most servers don't understand chunked request, and an error response will be returned to client app.
    // if the server does understand chunked request, we can do it even if req.httpVersion=1.0

    static ByteBuffer makeHead(HttpRequest request, HttpEntity entity, boolean absUri)
    {
        // keep it minimal at this layer, do not auto-add headers. leave it to upper layer.

        // copy headers.
        HeaderMap headers = new HeaderMap();
        for(Map.Entry<String,String> entry : request.headers().entrySet())
        {
            String name = entry.getKey();
            String value = entry.getValue();
            // we don't trust name/value. check them.
            _HttpUtil.checkHeader(name, value);
            headers.put(name, value);
        }

        // app should not set these two headers, or we'll remove them
        String hContentLength = null;
        String hTransferEncoding = null;

        if(entity!=null)
        {
            _HttpUtil.copyEntityHeaders(entity, headers);
            // usually just Content-Type. some headers (eg ETag) should not be in request, but we'll copy if given

            Long len = entity.contentLength();
            if(len==null)
                hTransferEncoding = "chunked";
            else
                hContentLength = len.toString();
        }

        if(hContentLength!=null)
            headers.xPut(Content_Length, hContentLength);
        else
            headers.xRemove(Content_Length);  // in case app set it

        if(hTransferEncoding!=null)
            headers.xPut(Transfer_Encoding, hTransferEncoding);
        else
            headers.xRemove(Transfer_Encoding);   // in case app set it

        ////////////////////////////////////////


        _CharSeqSaver out = new _CharSeqSaver( 8 + 4*headers.size());
        {
            String reqTarget = _HttpUtil.target(request, absUri);
            out
                .append(request.method()).append(" ")
                .append(reqTarget)
                .append(" HTTP/").append(request.httpVersion()).append("\r\n");

            for(Map.Entry<String,String> nv : headers.entrySet())
            {
                String name = nv.getKey();
                String value = nv.getValue();
                // name value have been sanity checked. we'll not generate syntactically incorrect header.
                out.append(name).append(": ").append(value).append("\r\n");
            }
            out.append("\r\n");
        }
        return ByteBuffer.wrap(out.toLatin1Bytes());
    }



    static ByteSource getBody(HttpEntity entity, long outboundBufferSize)
    {
        ByteSource body = entity.body();

        if(entity.contentLength()==null)
            body = new ImplChunkedSource(outboundBufferSize, body);

        return body;
    }
}
