package bayou.websocket;

import _bayou._tmp._ByteBufferUtil;
import _bayou._str._HexUtil;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.async.Promise;
import bayou.ssl.SslConnection;
import bayou.tcp.TcpConnection;
import bayou.util.End;
import bayou.util.Result;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.TimeoutException;

class WebSocketInbound
{
    final WebSocketChannelImpl chann;
    final WebSocketServerConf conf;
    final TcpConnection tcpConn;
    final WebSocketOutbound outbound;
    final ThroughputMeter throughputMeter;

    WebSocketInbound(WebSocketChannelImpl chann, WebSocketServer server,
                     TcpConnection tcpConn, WebSocketOutbound outbound)
    {
        this.chann = chann;
        this.conf = server.conf;
        this.tcpConn = tcpConn;
        this.outbound = outbound;

        throughputMeter = new ThroughputMeter(conf.readMinThroughput, Duration.ofSeconds(10));
    }





    // stage state .........................................................................................

    final Object lock(){ return this; }
    // we don't really worry about lock contention. usually all actions are done on the selector flow.

    Exception pumpError;
    boolean closeCalled;

    int pumpState;
    static final int
        pump_running =0,
        pump_awaitingStage =1,  // stage is full
        pump_awaitingReadable =2,
        pump_retired =3;

    Async<Void> awaitReadable;  // non-null during awaitingReadable.

    void close()
    {
        synchronized (lock())
        {
            if(closeCalled)
                return;
            closeCalled = true;

            if(pumpError!=null) // pump retired
                return;

            if(pumpState==pump_awaitingReadable)
                awaitReadable.cancel(new AsynchronousCloseException()); // wake up pump to see closeCalled then retire
            else if(pumpState==pump_awaitingStage)
                pump_retire(lock(), true);
                // in a more formal treatment, we should wake up pump to see closeCalled then tire.
                // here we do it directly as a shortcut.
            // else running - pump will soon see `closeCalled` and retire
            // else retired due to closeFrame/tcpFin

            // note: retire due to close() is graceful. app chooses to close without reading all inbound data.

            clearStage(lock());
        }
    }

    void stageErrorAndRetire(Object lock, Exception e)
    {
        assert pumpError==null;
        pumpError = e;

        assert pumpState == pump_running;
        pump_retire(lock, false);   // not graceful

        if(closeCalled)
            return;

        clearStage(lock);
    }

    boolean pump_retire(Object lock, final boolean graceful)
    {
        pumpState = pump_retired;
        chann.tcpConn_close(graceful);
        return false;
    }


    // pump read bytes .............................................

    long lastReadTime = System.currentTimeMillis();

    Runnable _pump = this::pump;
    void pump()
    {
        assert pumpState == pump_running;
        while( pump1() ) continue;
    }

    boolean pump1()
    {
        ByteBuffer bb;
        try
        {
            bb = tcpConn.read();
        }
        catch (Exception e)
        {
            WebSocketServer.logErrorOrDebug(e);
            synchronized (lock())
            {
                stageErrorAndRetire(lock(), e);
            }
            return false;
        }

        if(bb== TcpConnection.STALL)
        {
            awaitReadable();
            // state: awaitingReadable or retired
            return false;
        }

        if(bb== TcpConnection.TCP_FIN || bb== SslConnection.SSL_CLOSE_NOTIFY)
        {
            // tcp FIN not treated as an immediate error, staged message data can still be read. just set the tcpFin flag.
            // if app protocol has built-in end-of-conversation convention, it doesn't need close-frame.
            // therefore the client can simply close the tcp after it sends the last message.
            // simple example app protocol: the client sends one message to the server, that's all.
            // client simply closes tcp after writing the message; server simply closes tcp after reading the message.
            //
            // if TCP_FIN comes in the middle of a frame, that's more serious. for now we don't care.

            stageTcpFin();
            // state: retired
            return false;
        }

        // bb is data
        lastReadTime = System.currentTimeMillis(); // not exactly; bb may come from prev unread()

        return handleBytes(bb);  // if false, error or stage is full, pause pump
        // if stage full and bb is not empty, tcpConn.unread(bb) has been executed.
    }


    void awaitReadable()
    {
        long quietTime = System.currentTimeMillis() - Math.max(lastReadTime, outbound.lastWriteTime_volatile);
        long confPingIntervalMs = conf.pingInterval.toMillis();
        final boolean pinged;
        final Duration timeout;
        if(quietTime >= confPingIntervalMs)
        {
            // tcpConn has been quite (no read/write) longer than ping interval. send ping
            boolean pingStaged = outbound.stagePing();
            if(pingStaged)
            {
                // ping should be sent immediately since there's been no other outbound bytes
                pinged=true;
                timeout=conf.pingPongTimeout;
                // expecting client to respond pong quickly. if timeout, err.
                // it doesn't have to be pong; any bytes will wake up chann, we don't care.
            }
            else // outbound error, closed, or close-frame sent.
            {
                // we are not able to ping to test if the connection is still alive.
                // example app: writes some messages, send close-frame, then readMessage().
                // no timeout, wait indefinitely here.
                pinged=false;
                timeout=null;
            }
        }
        else
        {
            pinged=false;
            timeout = Duration.ofMillis(confPingIntervalMs - quietTime + 250);
            // if await timeout, [check ping interval again]
        }

        // note: if we are in the middle of a message, application has a timeout on msg.read().
        // if that timeout expires, app usually closes the channel.

        synchronized (lock())
        {
            if(closeCalled)
            {
                pump_retire(lock(), true);
            }
            else
            {
                pumpState = pump_awaitingReadable;

                awaitReadable = tcpConn.awaitReadable(/*accepting*/false);
                if(timeout!=null)
                    awaitReadable = awaitReadable.timeout(timeout);

                awaitReadable.onCompletion(result ->
                    onReadable(pinged, result));
            }
        }
    }

    void onReadable(boolean pinged, Result<Void> result)
    {
        Exception e = result.getException();
        synchronized (lock())
        {
            assert pumpState == pump_awaitingReadable;
            pumpState = pump_running;
            awaitReadable =null;

            if(closeCalled)
            {
                pump_retire(lock(), true);
                return;
            }

            if(!(e==null || (e instanceof TimeoutException)))
            {
                WebSocketServer.logErrorOrDebug(e);
                stageErrorAndRetire(lock(), e);
                return;
            }
        }

        // e is null or TimeoutException
        if(e==null) // readable now
            pump();
        else if(pinged) // serious. close outbound as well
            protocolErr("timeout while awaiting Pong from client");
        else // [check ping interval again]
            awaitReadable();
    }



    // parse bytes into frame parts ..................................................

    byte frameState;
    static final byte frameState_HEAD0=0, frameState_HEAD1=1, frameState_HEADx =2, frameState_BODY=3;

    final byte[] frameHead = new byte[14];
    byte frameHeadX; // 0-14
    // always need head bytes, even for state=body, since it contains masking key

    long frameBodyLength;
    long frameBodyX;

    // return true to continue and get more bytes
    // return false to pause pump, due to error or stage full.
    boolean handleBytes(ByteBuffer bb)
    {
        MORE_BYTES: while(bb.hasRemaining())
        {
            switch (frameState)
            {
                case frameState_HEAD0: // byte 0
                {
                    frameHeadX =0;
                    frameHead[frameHeadX++] = bb.get();
                    frameState=frameState_HEAD1;
                    continue MORE_BYTES;
                }
                case frameState_HEAD1: // byte 1
                {
                    frameHead[frameHeadX++] = bb.get();
                    frameState=frameState_HEADx;
                    // *fall through* to next case, which may require 0 bytes
                }
                case frameState_HEADx:  // collect extended payload length, and masking key
                {
                    boolean mask = ((frameHead[1] &0b1000_0000)!=0);
                    // we allow masking key to be missing at this stage (for generality).
                    int len = 0x7F & frameHead[1];
                    int lenEx = len==127? 8 : len==126? 2 : 0;
                    int headTotal = 2 + lenEx + (mask?4:0);  // max=14
                    // if headTotal==2, we already have all head bytes.

                    while(frameHeadX <headTotal && bb.hasRemaining())   // bb may be empty
                        frameHead[frameHeadX++] = bb.get();

                    if(frameHeadX <headTotal)  // should be rare
                        return true;   // bb is empty

                    // got all head bytes
                    if(chann.dump!=null)
                        chann.dumpFrameHead(true, ByteBuffer.wrap(frameHead));

                    long payloadLength = lenEx==0? len : bytesToInteger(frameHead, 2, lenEx);
                    if(payloadLength<0)  // 64-bit, 1st bit is 1.
                        return protocolErr(frameHead, frameHeadX, "negative 64-bit payload length");
                    if(!minEncodeLength(payloadLength, lenEx))
                        return protocolErr(frameHead, frameHeadX, "payload length not encoded minimally");

                    frameState = frameState_BODY;
                    frameBodyLength = payloadLength;
                    frameBodyX = 0;
                    if(frameBodyX == frameBodyLength)  // empty frame; frame end
                        frameState = frameState_HEAD0;

                    boolean cont = handleFrameHead(frameHead, frameHeadX, payloadLength, bb);
                    if(!cont)
                        return false;
                    continue MORE_BYTES;
                }
                case frameState_BODY:
                {
                    int r = (int)Math.min((long)bb.remaining(), frameBodyLength-frameBodyX);
                    assert r>0;

                    ByteBuffer body = _ByteBufferUtil.slice(bb, r);
                    body = unmask(body, (int)frameBodyX, frameHead, frameHeadX);

                    frameBodyX += r;
                    if(frameBodyX == frameBodyLength)   // frame end
                        frameState = frameState_HEAD0;

                    boolean cont = handleFrameBody(body, (frameBodyX==frameBodyLength), bb);
                    if(!cont)
                        return false;
                    continue MORE_BYTES;
                }
                default: throw new AssertionError();
            }

        } // MORE_BYTES: while(bb.hasRemaining())

        return true; // bb is empty, get more bytes.
    }



    // handle frame head, body, end ............................................................

    boolean protocolErr(byte[] head, byte headX, String msg)
    {
        msg += ". framing bytes: 0x";
        for(int i=0; i< headX; i++)
            msg += " " + _HexUtil.byte2hex(head[i]);

        return protocolErr(msg);
    }
    boolean protocolErr(String msg)
    {
        WebSocketException ex = new WebSocketException(msg);
        synchronized (lock())
        {
            stageErrorAndRetire(lock(), ex);
        }

        // this is a serious error. close the entire channel.
        // we don't bother to write a close frame to client for the reason.

        if(chann.dump!=null) // show reason for close
            chann.dump.print(chann.connId(), " inbound protocol error: ", msg, "\r\n");
        // we don't need to log it. typically app will see `ex` when it reads.

        chann.close();

        return false;
    }


    byte opCode;
    boolean msgFrameFin =true; // in the beginning pretend there's a prev msg frame with fin=1
    byte[] controlFrameBody;  // 0-125
    byte controlFrameBodyX;   // 0-125

    boolean handleFrameHead(byte[] head, byte headX, long payloadLength, ByteBuffer bb)
    {
        byte byte0 = head[0];

        if((byte0 & 0b0111_0000)!=0)  // RSV 123
            return protocolErr(head, headX, "non-zero RSV bit");

        if((head[1] &0b1000_0000)==0) // mask bit
            return protocolErr(head, headX, "client frame MASK=0");

        boolean fin = (byte0 &0b1000_0000)!=0;
        opCode = (byte)(byte0 & 0b0000_1111);

        if(opCode >0xA)
            return protocolErr(head, headX, "unknown op code");

        if(opCode >= 0x8) // control frame
        {
            if(!fin)
                return protocolErr(head, headX, "control frame FIN=0");

            if(payloadLength >125)
                return protocolErr(head, headX, "control frame payload length > 125");

            controlFrameBody = new byte[(int) payloadLength];
            controlFrameBodyX = 0;

            if(payloadLength==0)
                return handleControlFrameEnd();
            else
                return true;
        }

        // message frame.
        if(opCode >0x2)
            return protocolErr(head, headX, "unknown op code");

        if(opCode == WsOp.continue_) // continuation frame of the current message
        {
            if(msgFrameFin)
                return protocolErr(head, headX, "unexpected continuation frame; expecting text/binary frame");
            msgFrameFin = fin;

            boolean msgEnd = payloadLength==0 && msgFrameFin;
            if(msgEnd) // empty continuation frame with fin=1 to end curr message
                return stageMsg(null, null, msgEnd, bb);
            else
                return true;
            // possible sick case: payloadLength==0&&!fin - a spurious continuation frame
        }
        else // opCode = text/binary, start of a new message.
        {
            if(!msgFrameFin)
                return protocolErr(head, headX, "unexpected text/binary frame; expecting continuation frame");
            msgFrameFin = fin;

            ByteBuffer msgStart = (opCode == WsOp.text) ? MSG_TXT : MSG_BIN;
            boolean msgEnd = payloadLength==0 && msgFrameFin; // can be true - an empty msg
            return stageMsg(msgStart, null, msgEnd, bb);
        }
    }

    boolean handleFrameBody(ByteBuffer body, boolean frameEnd, ByteBuffer bb)
    {
        if(opCode >= 0x8) // control frame
        {
            int r = body.remaining();
            body.get(controlFrameBody, controlFrameBodyX, r);  // won't fail - r is small enough.
            controlFrameBodyX +=r;

            if(frameEnd) // usually
                return handleControlFrameEnd();
            else
                return true;
        }
        else // msg frame. bb not empty.
        {
            boolean msgEnd = frameEnd && msgFrameFin;
            return stageMsg(null, body, msgEnd, bb);
        }
    }

    boolean handleControlFrameEnd()
    {
        switch (opCode)
        {
            case WsOp.pong:
            {
                // don't care
                controlFrameBody = null;
                return true;
            }
            case WsOp.ping:
            {
                outbound.stagePong(controlFrameBody, controlFrameBodyX); // may fail, e.g. outbound closed. don't care.
                controlFrameBody = null;
                return true;
            }
            case WsOp.close:
            {
                WebSocketClose wsEnd = parseCloseBody(controlFrameBody, 0, controlFrameBodyX);
                controlFrameBody = null;
                if(wsEnd==null)
                    return protocolErr("error parsing close frame body");

                // it's possible that the curr message has not finished, i.e. msgFrameFin=false
                // client closed urgently, something is wrong on its side.
                // we'll let the message reader to see an exception after buffered msg data are served.
                // no other error treatment. outbound is still ok.

                return stageCloseFrame(wsEnd);  // retired. return false.
            }
            default: throw new AssertionError();
        }
    }


    static WebSocketClose parseCloseBody(byte[] bytes, int start, int length)
    {
        if(length==0)
            return new WebSocketClose(1005, "no code/reason");
        if(length==1) // error
            return null;

        int code = (int) bytesToInteger(bytes, start, 2);
        String reason = new String(bytes, start+2, length-2, StandardCharsets.UTF_8);  // no decoding error
        return new WebSocketClose(code, reason);
    }






    // stage area. store msg start/body/end, closeFrame/tcpFin .............................................

    // sentinels for msg start/end events
    static final ByteBuffer MSG_BIN = ByteBuffer.allocate(16);
    static final ByteBuffer MSG_TXT = ByteBuffer.allocate(16);
    static final ByteBuffer MSG_END = ByteBuffer.allocate(0);

    // upstream guarantees that message events are in a valid sequence:
    //     start, *body, end, start, ...
    // closeFrame/tcpFin may come before the last message is ended.

    ArrayDeque<ByteBuffer> msgEvents;  // empty <=> null
    int msgEventsSize; // full iff msgEventsSize>inboundBufferSize
    void addMsgEvent(ByteBuffer event)
    {
        msgEvents.addLast(event);
        msgEventsSize += event.remaining();
    }

    WebSocketClose wsClose;  // if close frame is received, or tcp-fin received.
    static final WebSocketClose TCP_FIN = new WebSocketClose(1006, ""); // sentinel

    void clearStage(Object lock) // due to pump error or close(), whichever comes first
    {
        msgEvents = null;
        msgEventsSize = 0;
        wsClose = null;

        push(lock);  // push closeCalled/pumpError
    }

    boolean stageMsg(ByteBuffer msgStart, ByteBuffer msgBody, boolean msgEnd, ByteBuffer bb)
    {
        synchronized (lock())
        {
            if(closeCalled)
                return pump_retire(lock(), true);

            boolean msgEventsWasEmpty = (msgEvents==null);
            if(msgEvents==null)
                msgEvents = new ArrayDeque<>();

            if(msgStart!=null)
                addMsgEvent(msgStart); // size+=16, to avoid buffering too many empty/tiny messages
            if(msgBody!=null)
                addMsgEvent(msgBody);  // body not empty
            if(msgEnd)
                addMsgEvent(MSG_END);  // does not increase size

            if(msgEventsWasEmpty)
                push(lock());
            // else push() won't have effect anyway

            if( msgEventsSize <= conf.inboundBufferSize )  // stage not full, continue pump
                return true;

            // stage full; pause pump
            pumpState = pump_awaitingStage;
            if(bb.hasRemaining())
                tcpConn.unread(bb);  // we must do this before releasing lock
            return false;
        }
    }
    boolean stageCloseFrame(WebSocketClose wsClose)
    {
        synchronized (lock())
        {
            if(closeCalled)
                return pump_retire(lock(), true);

            this.wsClose = wsClose;

            if(msgEvents==null)
                push(lock());
            // else push() won't have effect anyway

            return pump_retire(lock(), true);
        }
    }
    void stageTcpFin()
    {
        stageCloseFrame(TCP_FIN);
    }




    // push events from stage to reader ................................................................

    IncomingMessage currMsg;
    Promise<WebSocketMessage> msgPromise;
    // it's possible that both are non-null - user called close() on curr msg before EOF, then try to get next message.
    // we'll need to drain data of curr msg before serving next message.

    void push(Object lock)
    {
        Exception error = pumpError;
        if(error==null && closeCalled)
            error = new AsynchronousCloseException();
        // pumpError is usually more interesting than AsynchronousCloseException, so use it first.

        if(error !=null)
        {
            if(currMsg !=null)
            {
                currMsg.pushErr(lock, error);  // will terminate curr msg
            }
            if(msgPromise !=null)
            {
                msgPromise.fail(error);
                msgPromise =null;
            }

            return;
        }

        while(true)
        {
            if(msgEvents==null)
            {
                if(wsClose==TCP_FIN)
                {
                    if(currMsg!=null)
                    {
                        currMsg.pushErr(lock, new IOException("inbound EOF"));
                    }
                    if(msgPromise !=null)
                    {
                        msgPromise.fail(new IOException("inbound EOF"));
                        msgPromise =null;
                    }
                }
                else if(wsClose !=null)
                {
                    if(currMsg!=null)
                    {
                        String str = "close frame received before current message is finished";
                        currMsg.pushErr(lock, new WebSocketException(str, wsClose));
                    }
                    if(msgPromise !=null)
                    {
                        msgPromise.fail(wsClose);
                        msgPromise =null;
                    }
                }

                return;
            }

            ByteBuffer bb = msgEvents.peekFirst();
            assert bb!=null;
            int bb_remaining = bb.remaining(); // snapshot in case it's changed in push(bb)

            boolean bbConsumed = push(lock, bb);
            if(!bbConsumed)
                return;

            msgEventsSize -= bb_remaining;
            msgEvents.removeFirst();
            if(msgEvents.isEmpty())
                msgEvents=null; // die young
        }
        // may loop multiple times
    }

    // return false if bb is not consumed
    boolean push(Object lock, ByteBuffer bb)
    {
        if(currMsg !=null)
        {
            if(bb==MSG_END)
                return currMsg.pushEof(lock); // true
            else
                return currMsg.pushData(lock, bb);  // true or false
        }

        if(msgPromise !=null)
        {
            if(bb==MSG_TXT)
                currMsg = new IncomingMessage(true, this);
            else if(bb==MSG_BIN)
                currMsg = new IncomingMessage(false, this);
            else
                throw new AssertionError();

            msgPromise.succeed(currMsg);
            msgPromise =null;
            return true;
        }

        return false;
    }

    // called by reader after setting a promise
    void pull(Object lock)
    {
        push(lock);

        if(pumpState==pump_awaitingStage && msgEventsSize <= conf.inboundBufferSize)
        {
            pumpState = pump_running;
            chann.pumpExec.execute(_pump);
        }
    }




    // app reads message ................................................................................

    Async<WebSocketMessage> readMessage()
    {
        final Promise<WebSocketMessage> promise;
        synchronized (lock())
        {
            if(currMsg!=null && !currMsg.closeCalled)
                return Result.failure(new IllegalStateException("previous message not finished/closed"));
            // we don't auto close it. the situation could indicate an app bug.
            // if app wants to ignore the rest of curr msg, app must explicitly do msg.close()

            if(msgPromise !=null)
                return Result.failure(new IllegalStateException("previous readMessage() is still pending"));

            msgPromise = promise  = new Promise<>();

            pull(lock());
        }

        Result<WebSocketMessage> result = promise.pollResult();
        if(result!=null) // common
            return result;

        promise.onCancel(reason -> {
            synchronized (lock())
            {
                if(msgPromise!=promise)
                    return;
                msgPromise = null;
            }
            promise.fail(reason);
        });
        return promise;
        // no default timeout for readMessage(). we can't find a good default value.
        // app either want a very big timeout, probably infinity, or a very small timeout.
    }

    static class IncomingMessage implements WebSocketMessage
    {
        final boolean isText;

        final Object lock;

        WebSocketInbound inbound; // null on eof or error
        // double linked: inbound <--> msg    on eof or error, remove both links

        Exception error; // fed by stage.
        //TBA: we may send this exception to multiple consumers. problematic if it's mutable.

        Promise<ByteBuffer> readPromise;
        boolean closeCalled;

        IncomingMessage(boolean isText, WebSocketInbound inbound)
        {
            this.isText = isText;

            this.inbound = inbound;
            this.lock = inbound.lock();
        }

        @Override
        public String toString()
        {
            return isText? "websocket text message" : "websocket binary message";
        }

        @Override
        public boolean isText()
        {
            return isText;
        }

        @Override
        public Async<ByteBuffer> read() throws IllegalStateException
        {
            final Promise<ByteBuffer> promise;
            synchronized (lock)
            {
                if(closeCalled)
                    throw new IllegalStateException("closed");
                if(error!=null)
                    return Result.failure(error);
                if(inbound==null)
                    return _Util.EOF;
                if(readPromise !=null)
                    throw new IllegalStateException("prev read() is still pending");

                readPromise = promise = new Promise<>();
                inbound.throughputMeter.resumeClock();  // throughput clock is on from read start to read complete

                inbound.pull(lock);
            }

            Result<ByteBuffer> result = promise.pollResult();
            if(result!=null) // common
                return result;

            promise.onCancel(reason -> {
                synchronized (lock)
                {
                    if(readPromise!=promise)
                        return;
                    readPromise=null;
                }
                promise.fail(reason);
            });
            return promise.timeout(inbound.conf.readTimeout);
            // readMinThroughput is not enough to cut off slow clients if read() can stall forever
        }

        void readComplete(boolean success, ByteBuffer bb, Exception e)
        {
            if(success)
                readPromise.succeed(bb);
            else
                readPromise.fail(e);

            readPromise = null;
            inbound.throughputMeter.pauseClock();
        }

        @Override
        public Async<Void> close()
        {
            synchronized (lock)
            {
                if(closeCalled)
                    return Async.VOID;
                closeCalled = true;

                if(inbound==null)  // eof or error
                    return Async.VOID;

                if(readPromise!=null)  // uh? support async close?
                    readComplete(false, null, new AsynchronousCloseException());

                // message has not ended; consume/ignore further msg events.
                inbound.pull(lock);   // will consume all staged body/end for this message
                return Async.VOID;
            }
        }

        boolean pushData(Object lock, ByteBuffer bb)
        {
            if(readPromise==null)
            {
                if(!closeCalled)
                    return false; // bb not consumed. to be fed to the next promise

                // else - closed() called before EOF was reached. bb is consumed and discarded.
                // throughput not checked; it doesn't matter; next readMessage() has its timeout.
                return true;
            }

            boolean throughputOk = inbound.throughputMeter.reportBytes(bb.remaining());
            if(throughputOk)
            {
                readComplete(true, bb, null);
                return true;
            }

            readComplete(false, null, new IOException("inbound throughput too low"));

            // inbound is not corrupt. app can still read next message.
            // however this message is corrupt, because bb is discarded.
            // the problem should be persistent so that further read() will fail.
            // we borrow closeCalled to do that. this message still consumes msg data till eof/error.
            closeCalled = true;

            return true;
        }

        boolean pushEof(Object lock)
        {
            if(readPromise!=null)
                readComplete(false, null, End.instance());

            inbound.currMsg = null;
            inbound = null;
            return true; // eof is always consumed
        }

        void pushErr(Object lock, Exception e)
        {
            if(readPromise!=null)
                readComplete(false, null, e);

            error = e;

            inbound.currMsg = null;
            inbound = null;
        }

    }

    // bytes are in network order.
    // if first bit is 1, and there are 8 bytes, we'll return a negative value.
    static long bytesToInteger(byte[] bytes, int start, int length)
    {
        long result=0;
        for(int i=0; i<length; i++)
        {
            result <<= 8;
            result += 0xFF&bytes[start+i];
        }
        return result;
    }
    static boolean minEncodeLength(long payloadLength, int lenEx)
    {
        if(payloadLength<=125)
            return lenEx==0;

        if(payloadLength<=0xFFFF)
            return lenEx==2;

        assert lenEx==8;
        return true;
    }

    static ByteBuffer unmask(ByteBuffer bb, int off, byte[] frameHead, int end)  // last 4 bytes is the masking key
    {
        if(bb.isReadOnly())
            bb = _ByteBufferUtil.copyOf(bb);

        int m0 = (end-4);

        int p0 = bb.position();
        int len = bb.remaining();
        for(int i=0; i<len; i++)
        {
            byte b = bb.get(p0+i);
            byte m = frameHead[ m0 + ((off+i)&0x03) ];
            b = (byte)(b^m);
            bb.put(b);
        }
        bb.position(p0);
        return bb;
    }

}
