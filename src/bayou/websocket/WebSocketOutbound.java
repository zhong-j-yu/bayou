package bayou.websocket;

import _bayou._bytes._EmptyByteSource;
import _bayou._tmp._ByteBufferUtil;
import bayou.async.Async;
import bayou.async.Promise;
import bayou.bytes.ByteSource;
import bayou.tcp.TcpConnection;
import bayou.util.End;
import bayou.util.Result;
import bayou.util.function.FunctionX;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.function.Consumer;

class WebSocketOutbound
{
    final WebSocketChannelImpl chann;
    final WebSocketServerConf conf;
    final TcpConnection tcpConn;
    final ThroughputMeter throughputMeter;

    WebSocketOutbound(WebSocketChannelImpl chann, WebSocketServer server,
                      TcpConnection tcpConn, ByteBuffer handshakeResponse)
    {
        this.chann = chann;
        this.conf = server.conf;
        this.tcpConn = tcpConn;

        throughputMeter = new ThroughputMeter(conf.writeMinThroughput, conf.writeTimeout);

        flushMark = conf.outboundBufferSize;
        assert flushMark>0;

        tcpConn.queueWrite(handshakeResponse);
    }

    
    // stage
    // ----------------------------------------------------------------------------------------------------

    final Object lock(){ return this; }
    // we don't really worry about lock contention. usually all actions are done on the selector flow.

    Exception error;
    //TBA: we may send this exception to multiple consumers. problematic if it's mutable.

    boolean closeCalled;

    // keep only one ping/pong frame, the latest one.
    ByteBuffer pingFrame;
    ByteBuffer pongFrame;

    ArrayDeque<OutgoingMessage> messages; // null iff empty

    // close frame is treated as an OutgoingMessage (the last one)
    boolean closeFrameStaged;

    static final int
        pump_running =0,
        pump_awaitingFrame =1,
        pump_awaitingWritable =2,
        pump_retired =3;

    int pumpState;
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
            WebSocketServer.logErrorOrDebug(e);

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
        if(throughputMeter.isPaused())
            throughputMeter.resumeClock();

        while(true) // fetch & flush loop
        {
            // [flush]
            long wr = tcpConn.getWriteQueueSize();
            if(wr> flushMark)
            {
                tcpConn_write(); // throws
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

                if(f== fetch_noMoreFrames) // close frame queued, or close called.
                {
                    // we should not send SSL_CLOSE_NOTIFY. websocket messages are self-terminated,
                    // no need for SSL to mark the end. and after close-frame, there probably should
                    // not be any more data on the wire
                    // TCP_FIN is probably not helpful either; client should have stopped reading from us
                    // after it received close-frame, or the last message (client knows it's the last per app protocol)
                    flushMark =0;
                    continue;             // flush all bytes
                }

                assert f== fetch_stall;
                // try write bytes already queued (maybe none)
                tcpConn_write(); // throws
                wr = tcpConn.getWriteQueueSize();
                if(wr>0)
                {
                    // both fetch and write stall.
                    // await writable only. don't care if a frame is available before tcpConn is writable
                    // throughput meter clock not paused
                    set_awaitWritable(lock());  // then goto [fetch]
                    return;
                }
                else // wr==0
                {
                    // fetch stall only. await frame.
                    throughputMeter.pauseClock();   // will be resumed by `resumePump`
                    pumpState = pump_awaitingFrame; // then goto [fetch]
                    return;
                }
            }

        } // fetch & flush loop

    } // pumpE()

    // watch for error from the source side.
    // if error==null and closeCalled, carry on to flush all queued bytes, before tcpConn.close()

    void set_awaitWritable(Object lock)
    {
        pumpState = pump_awaitingWritable;
        awaitWritableAsync = tcpConn.awaitWritable().timeout(conf.writeTimeout);
        awaitWritableAsync.onCompletion(onConnWritable);
    }

    Consumer<Result<Void>> onConnWritable = result -> {
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
                WebSocketServer.logErrorOrDebug(e);
                setError(lock(), e);
                pump_retire(lock(), false);
                return;
            }

            // goto pump()
        }

        pump();
    };

    volatile long lastWriteTime_volatile = System.currentTimeMillis();  // accessed by inbound

    void tcpConn_write() throws Exception
    {
        long w = tcpConn.write(); // throws
        lastWriteTime_volatile = System.currentTimeMillis();

        if(!throughputMeter.reportBytes(w))
        {
            throw new IOException("outbound throughput too low");  // as if network error
        }
    }
    // tcpConn errors could be benign, e.g. write timeout, write throughput too low.
    // inbound could still be working, we don't destroy inbound proactively.
    // however app ought to detect error from outbound and close the whole ws connection.

    void pump_retire(Object lock, final boolean graceful)
    {
        // graceful iff error==null
        pumpState = pump_retired;
        chann.tcpConn_close(graceful);
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
        else if(pingFrame !=null)
        {
            if(chann.dump!=null)
                chann.dumpFrameHead(false, pingFrame);
            tcpConn.queueWrite(pingFrame);
            pingFrame =null;
            return fetch_success;
        }
        else if(pongFrame !=null)
        {
            if(chann.dump!=null)
                chann.dumpFrameHead(false, pongFrame);
            tcpConn.queueWrite(pongFrame);
            pongFrame =null;
            return fetch_success;
        }
        else if(messages !=null) // not empty
        {
            OutgoingMessage msg = messages.peekFirst();
            assert msg!=null;
            return msg.fetchFrame(lock); // success, stall, error
        }
        else if(closeFrameStaged || closeCalled)
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
        int opCode; // start as non-zero for the 1st frame, then become 0 for continuing frames

        ByteSource src; // null after EOF
        Async<ByteBuffer> pendingRead;
        ByteBuffer leftover;

        long totalBytes;
        Promise<Long> promise;

        Result<ByteBuffer> read(Object lock)
        {
            if(leftover!=null)
            {
                Result<ByteBuffer> result = Result.success(leftover);
                leftover=null;
                return result;
            }

            // may reach here before prev pending read is complete, e.g. a pong is staged meanwhile.
            if(pendingRead==null)
                pendingRead = src.read();
            Result<ByteBuffer> result = pendingRead.pollResult();
            if(result!=null)
                pendingRead=null;
            else
                pendingRead.onCompletion(onMsgReadComplete);
            return result;
        }

        int fetchFrame(Object lock)
        {
            // we need to know frame length ahead of time.
            // if we want to support huge frame, we need to read and buffer a lot of data
            final long maxPayload = Math.min(conf.outboundPayloadMax, conf.outboundBufferSize);  // >0
            ArrayList<ByteBuffer> bbs = new ArrayList<>();
            long length=0;

            while(true)
            {
                Result<ByteBuffer> readResult = read(lock);  // may throw unexpectedly from user code
                if(readResult==null) // read pending
                {
                    if(length==0)
                        return fetch_stall;
                    else
                        break; // goto [queue write], then return stall
                }

                Exception e = readResult.getException();
                if(e instanceof End)
                {
                    messages.removeFirst(); // remove me
                    if(messages.isEmpty())
                        messages =null; // die young

                    src.close();
                    src = null;

                    promise.succeed(totalBytes);
                    // a bit premature to complete promise, since the frame hasn't been written yet.

                    break; // goto [queue write]
                    // possible: length==0
                }
                else if(e!=null) // outbound corrupt
                {
                    WebSocketServer.logErrorOrDebug(e);
                    setError(lock, e);  // will clean up all messages
                    return fetch_error;
                }

                // got some bytes. maybe more than we need for this frame.
                ByteBuffer bb = readResult.getValue();
                assert bb!=null;
                long r = bb.remaining();  // could be 0
                long x = maxPayload-length; // >0
                if(r>x)
                {
                    leftover = bb;
                    bb = _ByteBufferUtil.slice(leftover, (int)x);
                    r = x;
                }
                bbs.add(bb);
                totalBytes+=r;
                if(totalBytes<0) // overflow, not likely
                    throw new RuntimeException("message bytes > Long.MAX");
                length+=r; // won't overflow if totalBytes didn't overflow
                if(length>=maxPayload)
                    break; // goto [queue write]

                // bytes<maxPayload, continue loop
            }

            // [queue write]
            // payload length can be 0 if fin=true
            byte[] frameHead = makeFrameHead(/*fin*/(src==null), opCode, length);
            opCode= WsOp.continue_; // for following frames

            if(chann.dump!=null)
                chann.dumpFrameHead(false, ByteBuffer.wrap(frameHead));
            tcpConn.queueWrite(ByteBuffer.wrap(frameHead));

            for(ByteBuffer bb : bbs)
                tcpConn.queueWrite(bb);

            return pendingRead==null ? fetch_success : fetch_stall;
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
        chann.pumpExec.execute(_pump);
    }

    // called by inbound to inject a ping or pong frame.
    // return false if unable to stage, due to error, or close frame being sent.
    boolean stagePingPong(boolean isPing, ByteBuffer frame)
    {
        synchronized (lock())
        {
            if(error !=null)
                return false;

            if(closeCalled)  // closeCalled && error==null : graceful close, no more outbound data
                return false;

            if(closeFrameStaged)
            {
                if(messages==null)  // close frame sent. cannot write any more frame
                    return false;
                // close frame staged but not sent; we can still write this ping/pong frame.
                // use case: app staged a lot of outbound messages then close frame.
                // that takes a while to flush. meanwhile if client sends a ping, we want to respond pong asap.
            }

            if(isPing)
                pingFrame = frame;
            else
                pongFrame = frame;
            // previous frame will be kicked out.
            // if client floods us with Ping requests, we'll only stage one Pong here.

            // even if pump was waiting on curr msg read(), the arrival of this ping/pong frame has priority.
            resumePumpIfAwaitingFrame(lock());
        }
        return true;
    }
    boolean stagePing()
    {
        byte[] head = makeFrameHead(true, WsOp.ping, 0);
        return stagePingPong(true, ByteBuffer.wrap(head)); // no body
    }
    boolean stagePong(byte[] body, int bodyLength)
    {
        byte[] frame = makeControlFrame(WsOp.pong, body, bodyLength);
        return stagePingPong(false, ByteBuffer.wrap(frame));
    }

    Async<Void> stageCloseFrame()  // treat it as a message
    {
        OutgoingMessage msg = new OutgoingMessage();
        msg.opCode = WsOp.close;
        msg.src = new _EmptyByteSource();
        return stageMsg(msg).map(funcLongToVoid);
    }
    static final FunctionX<Long, Void> funcLongToVoid = aLong -> null;

    Async<Long> stageMessage(WebSocketMessage wsMsg)
    {
        OutgoingMessage msg = new OutgoingMessage();
        msg.opCode = wsMsg.isText() ? WsOp.text : WsOp.binary;
        msg.src = wsMsg;
        return stageMsg(msg);
    }
    Async<Long> stageMsg(final OutgoingMessage msg)
    {
        synchronized (lock())
        {
            // serious programming error. write flow should not stage more message after close frame
            if(closeFrameStaged)
                return Result.failure(new IllegalStateException("close frame was queued"));

            // less serious. close() may be called by read flow async-ly, so write flow isn't at fault.
            if(closeCalled)
                return Result.failure(new AsynchronousCloseException());

            if(error !=null)
                return Result.failure(error);


            msg.promise = new Promise<>();
            msg.promise.onCancel(reason ->
                cancelWrite(msg, reason));

            if(msg.opCode== WsOp.close)
                closeFrameStaged =true;

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








    // error, cancel, close
    // -----------------------------------------------------------------------------------------



    void cancelWrite(OutgoingMessage msg, Exception reason)
    {
        synchronized (lock())
        {
            if(error !=null) // msg was either queued, or aborted.
                return;

            if(closeCalled)
                return;
                // closeCalled && error==null, graceful close, msg must have been queued
                // we don't really need this clause; msg.src==null should be true here.

            if(msg.src==null)  //  <==> `messages` does not contain `msg`
                return;  // msg has been written; cancel has no effect

            // cancel an unwritten message. corrupts the entire outbound.

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
                // close() after all messages have been queued. graceful close. no error here.

                if(pumpState == pump_awaitingFrame) // wr==0 here
                    pump_retire(lock(), true);
                // else if running/awaitingWritable, let pump finish on its own.
                //     ping/pong may exist, they'll be queued. but no more frames will be queued.
                //     pump will try to flush all queued bytes then retire gracefully.
                //     pump may encounter tcpConn error before flushing is done.
                //     duration of flushing is limited by bufferSize/minThroughput
                // else retired (closeFrame was flushed)
            }
            else
            {
                // close() before messages are queued. abortive close. error. cancel messages.
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



    void setError(Object lock, final Exception e)
    {
        assert error ==null;
        error = e;

        cleanup(lock);
    }
    // actually, no lock needed in cleanup, nobody else is touching these fields after error,
    // everybody checks `error` first before proceed.
    void cleanup(Object lock)
    {
        pingFrame = null;
        pongFrame = null;

        if(messages !=null)
        {
            for(final OutgoingMessage msg : messages)
            {
                if(msg.pendingRead==null)
                    msg.src.close();
                else // close src after read completes
                {
                    msg.pendingRead.cancel(error);
                    msg.pendingRead.onCompletion(byteBufferResult ->
                        msg.src.close());
                }

                msg.promise.fail(error);
            }
            messages =null;
        }
    }

    static byte[] makeFrameHead(boolean fin, int opCode, long payloadLength)
    {
        byte[] head;
        int ex;
        if(payloadLength<=125)
        {
            ex=0;
            head = new byte[2+ex];
            head[1] = (byte)payloadLength;
        }
        else if(payloadLength<=0xFFFF)
        {
            ex=2;
            head = new byte[2+ex];
            head[1] = (byte)126;
        }
        else
        {
            ex=8;
            head = new byte[2+ex];
            head[1] = (byte)127;
        }
        for(int i=2+ex-1; i>=2; i--)
        {
            head[i] = (byte)payloadLength;
            payloadLength>>=8;
        }

        int byte0 = opCode;
        if(fin)
            byte0 |= 0b1000_0000;
        head[0] = (byte)byte0;

        return head;
    }

    static byte[] makeControlFrame(int opCode, byte[] body, int bodyLength)
    {
        assert bodyLength<=125;
        byte[] frame = new byte[2+bodyLength];
        frame[0] = (byte)(0b1000_0000 | opCode);
        frame[1] = (byte)bodyLength;
        System.arraycopy(body, 0, frame, 2, bodyLength);
        return frame;
    }

}
