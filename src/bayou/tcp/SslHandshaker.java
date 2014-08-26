package bayou.tcp;

import _bayou._tmp._ByteBufferPool;
import _bayou._tmp._JobTimeout;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.util.Result;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

// ssl handshake is half-duplex. it's done when server received client FINISHED and sent server FINISHED.
// on localhost, using browser/https, normal handshake takes around 30ms (100ms on the very 1st handshake)
// resumed handshake takes 6ms.
class SslHandshaker
{
    static void start(TcpServerX server, TcpChannel channel)
    {
        SslHandshaker x = new SslHandshaker(server, channel, null);
        x.jump(TO_READ);
    }
    static void start(TcpServerX server, TcpChannel channel, _JobTimeout timeout, ByteBuffer initBuffer)
    {
        // we already have some bytes from client, skip read, go unwrap
        SslHandshaker x = new SslHandshaker(server, channel, timeout);
        x.readBuffer = initBuffer;
        x.jump(TO_UNWRAP);
    }

    TcpServerX server;
    _ByteBufferPool bufferPool;
    TcpChannel channel;

    SSLEngine engine;
    _JobTimeout timeout;

    SslHandshaker(TcpServerX server, TcpChannel channel, _JobTimeout timeout)
    {
        if(trace)trace("start handshake ===========");
        this.server = server;
        this.bufferPool = server.sslBufferPool;
        this.channel = channel;

        engine = server.sslContext.createSSLEngine();
        engine.setUseClientMode(false);

        if(timeout==null)
            timeout = new _JobTimeout(server.confSslHandshakeTimeout, "NbTcpServerX.confSslHandshakeTimeout");
        this.timeout = timeout;

    }

    static final int TO_NA = 0;
    void jump(int j)
    {
        try
        {
            while(j!= TO_NA)
            {
                switch(j) // method should handle "usual" exceptions internally; throw only unexpected.
                {
                    case TO_READ:   j = read();   break;
                    case TO_UNWRAP: j = unwrap(); break;
                    case TO_WRAP:   j = wrap();   break;
                    case TO_WRITE:  j = write();  break;
                    case TO_FINISH: j = finish(); break;

                    default: throw new AssertionError();
                }
            }
        }
        catch(Exception t) // unexpected; shouldn't happen.
        {
            kill(t);
        }
    }

    ByteBuffer readBuffer; //raw bytes from client. for get

    // no app data is produced during handshake; but engine needs a buffer as workplace
    ByteBuffer appBuffer4Unwrap; // for put. (nothing will be put in it by unwrap())


    static final int TO_READ = 1;
    int read() throws Exception
    {
        if(trace)trace("READ");
        if(trace)trace("readBuffer", readBuffer);
        if(readBuffer==null)
            readBuffer = bufferPool.checkOut(); // throws
        else
            readBuffer.compact();
        // compact may cost copying, if there's leftover data not at position=0. unlikely during handshake.

        // readBuffer for put
        assert readBuffer.hasRemaining();

        if(trace)trace("before channel.read", readBuffer);
        int r = channel.read(readBuffer); // throws
        if(trace)trace("after channel.read", readBuffer);

        readBuffer.flip(); // for get

        if(r==-1) // premature tcp close during handshake
            return kill(null);

        if(r==0) // nothing imm to read
        {
            // to enter read select; free buffers if possible

            if(!readBuffer.hasRemaining()) // nothing in it. don't keep buffer across select.
            {
                if(trace)trace("free empty readBuffer");
                bufferPool.checkIn(readBuffer);
                readBuffer=null;
            }
            // else the buffer has leftover bytes that we must keep. likely due to fragmentation

            assert appBuffer4Unwrap==null; // unwrap freed it

            if(trace)trace("enter read select");
            awaitReadable();
            return TO_NA;
        }

        // r>0. we read some bytes, go unwrap
        return TO_UNWRAP;
    }

    void awaitReadable()
    {
        Async<Void> await = channel.awaitReadable(true);
        timeout.setCurrAction(await);
        await.onCompletion(readCallback);
    }

    Consumer<Result<Void>> readCallback = result -> {
        Exception error=result.getException();
        if(trace)trace("exit read select");
        if(error!=null)
            kill(error);
        else
            jump(TO_READ);
    };

    static final int TO_UNWRAP = 2;
    int unwrap() throws Exception
    {
        if(trace)trace("UNWRAP");
        if(trace)trace("appBuffer4Unwrap", appBuffer4Unwrap);
        if(appBuffer4Unwrap==null)
            appBuffer4Unwrap = bufferPool.checkOut(); // throws
        // else kept from prev unwrap. consecutive unwraps is common
        //   no need to call clear() - it's always in the cleared state.

        SSLEngineResult result = engine.unwrap(readBuffer, appBuffer4Unwrap); // throws
        if(trace)trace("engine.unwrap()", result);
        if(trace)trace("readBuffer", readBuffer);

        switch(result.getStatus())
        {
            // won't happen. if close_notify received during handshake, engine.unwrap() throws fatal error.
            case CLOSED:
                throw new AssertionError("closed during handshake");

            // if client sends larger record (>16KB), we may have buffer overflow/underflow.
            // not a real concern in handshake, records are usually very small anyway.
            // treat it as client protocol exception.

            case BUFFER_OVERFLOW:
                throw new SSLException("client ssl record too large");

            case BUFFER_UNDERFLOW:
                // if we already have max bytes for a record, client is sending large record.
                if(readBuffer.remaining()==readBuffer.capacity())
                    throw new SSLException("client ssl record too large"); // especially wrong during handshake

                // ok. to read more bytes from client
                // we could return TO_READ. but it's unlikely that more client bytes are imm available.
                // buffer is big enough to hold client handshake messages, so if we have an incomplete record,
                // mostly likely prev channel.read() consumed all imm client data.
                // take a shortcut, enter read select directly. (ok if the assumption is wrong)

                // to enter read select. free buffers
                if(!readBuffer.hasRemaining()) // nothing in it. don't keep buffer across select.
                {
                    // so we are expecting the next client record, yet there's exactly 0 bytes.
                    // this can be credible, if client write() one record at a time,
                    // and the next record to unwrap is still on its way.
                    if(trace)trace("free readBuffer");
                    bufferPool.checkIn(readBuffer);
                    readBuffer=null;
                }
                // else the buffer has bytes of incomplete record. likely due to fragmentation.
                else
                if(trace)trace("keep readBuffer");

                if(trace)trace("free appBuffer4Unwrap");
                bufferPool.checkIn(appBuffer4Unwrap); // it has nothing of value
                appBuffer4Unwrap=null;

                if(trace)trace("enter read select");
                awaitReadable();
                return TO_NA;

            case OK: break;
            default: throw new AssertionError();
        }
        // status=OK
        switch(result.getHandshakeStatus())
        {
            case NOT_HANDSHAKING:
                throw new AssertionError();

            case FINISHED: // possible in resumed handshake, where client sends the last handshake msg.
                // note: readBuffer!=null. may not may not contain data. finish() will deal with it.
                if(trace)trace("free appBuffer4Unwrap");
                bufferPool.checkIn(appBuffer4Unwrap);
                appBuffer4Unwrap=null;

                return TO_FINISH;

            case NEED_UNWRAP: // consecutive unwrap. it's common. that's why we keep appBuffer4Unwrap
                // same two buffers
                return TO_UNWRAP;

            case NEED_TASK: // run tasks and try unwrap again. it's common.
                syncRunTasks(); // throws
                // same two buffers
                return TO_UNWRAP;

            case NEED_WRAP:
                // we only need appBuffer4Unwrap again after client responds. free it now.
                if(trace)trace("free appBuffer4Unwrap");
                bufferPool.checkIn(appBuffer4Unwrap);
                appBuffer4Unwrap=null;

                // if readBuffer has leftover, we need to keep it. (shouldn't happen during handshake)
                // otherwise we can repurpose it as write buffer for wrap. (very likely)
                if(!readBuffer.hasRemaining())
                {
                    if(trace)trace("repurpose readBuffer -> writeBuffer");
                    writeBuffer = readBuffer;
                    readBuffer = null;
                }

                return TO_WRAP;

            default: throw new AssertionError();
        }
    }

    ByteBuffer writeBuffer; // destination of wrap().
    // during handshake, there can be consecutive wrap(). for example, [change-cipher]+[finished]
    // they are very small records. it would be nice to flush all in one write (wrap-wrap-flush)
    // unfortunately each wrap() requires a max buffer (remaining=maxRecordSize).
    // so we can't use 1 writeBuffer for multiple records. keep it simple, we do wrap-flush-wrap-flush

    // a buffer of 0 capacity. for ssl engine wrap()
    static final ByteBuffer BB0 = ByteBuffer.wrap(new byte[0]);

    static final int TO_WRAP = 3;
    int wrap() throws Exception
    {
        if(trace)trace("WRAP");
        if(trace)trace("writeBuffer", writeBuffer);
        if(writeBuffer==null)
            writeBuffer = bufferPool.checkOut(); // throws

        writeBuffer.clear(); // for put
        SSLEngineResult result = engine.wrap(BB0, writeBuffer); // throws
        if(trace)trace("engine.wrap", result);
        if(trace)trace("writeBuffer", writeBuffer);
        writeBuffer.flip(); // for get

        switch(result.getStatus())
        {
            case CLOSED: // impossible during handshake
                throw new AssertionError();

            case BUFFER_UNDERFLOW: // impossible, don't need anything in the source buffer
                throw new AssertionError();

            case BUFFER_OVERFLOW: // impossible
                throw new AssertionError();

            case OK: break;
            default: throw new AssertionError();
        }
        // status=OK
        switch(result.getHandshakeStatus())
        {
            case NOT_HANDSHAKING:
                throw new AssertionError();

            case NEED_TASK: // run tasks and try wrap() again
                syncRunTasks(); // throws
                return TO_WRAP;

            case NEED_WRAP:
                break; // flush then wrap

            case NEED_UNWRAP:
                break; // flush then read-unwrap

            case FINISHED: // server to send the last msg that concludes the handshake.
                break; // flush

            default: throw new AssertionError();
        }
        // handshake status: FINISHED/NEED_WRAP/NEED_UNWRAP
        // we must flush writeBuffer before continuing.
        return TO_WRITE;
    }

    static final int TO_WRITE = 4;
    int write() throws Exception
    {
        if(trace)trace("WRITE");
        // writeBuffer for get

        long w = channel.write(writeBuffer);
        if(trace)trace("channel.write()", w);

        if(writeBuffer.hasRemaining()) // bytes remained to be written
        {
            if(trace)trace("enter write select, remain:", writeBuffer.remaining());
            // most likely it's futile to immediately try channel.write() again. wait to become writable.
            Async<Void> await = channel.awaitWritable();
            timeout.setCurrAction(await);
            await.onCompletion(writeCallback);
            return TO_NA;
        }

        // done writing
        SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
        if(trace)trace("done write, handshake status:", hs);

        if(hs==SSLEngineResult.HandshakeStatus.NEED_WRAP)
        {
            // reuse writeBuffer
            return TO_WRAP;
        }

        // not likely we can reuse the buffer
        if(trace)trace("free writeBuffer");
        bufferPool.checkIn(writeBuffer);
        writeBuffer = null;

        if(engine.getHandshakeStatus()==SSLEngineResult.HandshakeStatus.NEED_UNWRAP)
        {
            if(readBuffer!=null) // leftover in read buffer. very unlikely
            {
                if(trace)trace("readBuffer leftover");
                assert readBuffer.hasRemaining(); // for get
                // go directly to unwrap the leftover bytes first. may underflow
                return TO_UNWRAP;
            }

            // we need to read and unwrap. we just wrote to client, it should take a while before client responds,
            // most likely it's futile to immediately try channel.read() now. go directly to select.
            if(trace)trace("enter read select");
            awaitReadable();
            return TO_NA;
        }

        // else, handshake finished (NOT_HANDSHAKING)
        return TO_FINISH;
    }

    Consumer<Result<Void>> writeCallback = result -> {
        if(trace)trace("exit write select");

        Exception error=result.getException();
        if(error!=null)
            kill(error);
        else
            jump(TO_WRITE);
    };

    static final int TO_FINISH = 5;
    int finish()
    {
        if(trace)trace("finish");
        timeout.complete();

        SslTcpConnection sslConn = new SslTcpConnection(server, channel, engine);
        if(readBuffer!=null)
        {
            if(readBuffer.hasRemaining())
            {
                // leftover client bytes, hand it over to connection. this is common.
                // for example, with resumed handshake on a new connection, browser
                // sends the last handshake msg and the http request in one batch.
                if(trace)trace("readBuffer leftover, handover to SslNbConnection");
                sslConn.clientNetBuffer = readBuffer; // for get
                readBuffer=null;
                sslConn.needMoreClientBytes = false; // check leftover before reading more net data
            }
            else // no bytes in it
            {
                // we could handover the empty buffer to sslConn, set needMoreClientBytes=true.
                // very likely sslConn.read() is called imm so the buffer is imm useful.
                // however, we can't be sure here. for possible counter case, don't do that.
                if(trace)trace("free readBuffer");
                bufferPool.checkIn(readBuffer);
                readBuffer=null;
            }
        }
        assert appBuffer4Unwrap==null;
        assert writeBuffer==null;
        if(trace)trace("end handshake ==============");
        sslConn.connId = this.connId;
        server.onConnect(sslConn); // no throw
        return TO_NA;
        // `this` then becomes garbage. nobody is referencing me
    }


    int kill(Exception cause) // no throw
    {
        if(trace)trace("kill", cause);
        channel.close(); // no throw

        // self clean up

        timeout.complete();

        if(readBuffer!=null)
            bufferPool.checkIn(readBuffer);

        if(appBuffer4Unwrap!=null)
            bufferPool.checkIn(appBuffer4Unwrap);

        if(writeBuffer!=null)
            bufferPool.checkIn(writeBuffer);

        if(cause!=null)
            _Util.logErrorOrDebug(TcpServer.logger, cause);

        return TO_NA;
        // `this` then becomes garbage. nobody is referencing me
    }

    // for now, assume tasks are non-blocking, no need for another thread.
    void syncRunTasks() // throws
    {
        Runnable task;
        while(null!=(task=engine.getDelegatedTask()))
        {
            if(trace)trace("run task", task);
            task.run(); // throws
        }
    }



    static final boolean trace = false;
    static AtomicInteger connIdGen = new AtomicInteger(0);
    int connId;
    void trace(Object... args)
    {
        if(connId==0)
            connId = connIdGen.incrementAndGet();

        trace0(connId, args);
    }
    static int traceId = 0;
    synchronized static void trace0(int connId, Object... args)
    {
        System.out.print("Ssl HS   " +connId+ "   " +(traceId++)+"   ");
        for(Object arg : args)
        {
            System.out.print(arg);
            System.out.print(" ");
        }
        System.out.println();
    }

}
