package bayou.ssl;

import _bayou._tmp._ByteBufferUtil;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.async.Promise;
import bayou.tcp.TcpChannel;
import bayou.tcp.TcpChannel2Connection;
import bayou.tcp.TcpConnection;
import bayou.util.Result;
import bayou.util.function.FunctionX;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

// read the first byte from client. if it is 22, assume it's SSL handshake request.
class SslDetector
{

    SslChannel2Connection toSsl;
    TcpChannel2Connection toPlain;
    TcpChannel channel;
    Promise<TcpConnection> promise;

    public SslDetector(SslChannel2Connection toSsl, TcpChannel2Connection toPlain,
        TcpChannel channel, Promise<TcpConnection> promise)
    {
        this.toSsl = toSsl;
        this.toPlain = toPlain;

        this.channel = channel;
        this.promise = promise;
    }

    void kill(Exception cause) // no throw
    {
        if(trace)trace("kill", cause);
        channel.close(); // no throw

        promise.fail(cause);

        // nothing else to clean up
        // `this` then becomes garbage. nobody is referencing me
    }

    void read()
    {
        try
        {
            readE();
        }
        catch(Exception cause)
        {
            kill(cause);
        }
    }

    void onReadable(Result<Void> result)
    {
        Exception error=result.getException();
        if(trace)trace("read callback", error);
        if(error!=null)
            kill(error);
        else
            read();  //no recursion, see channel.awaitReadable()
    }

    void readE() throws Exception
    {
        if(trace)trace("readE()");
        ByteBuffer readBuffer = toSsl.sslBufferPool.checkOut(); // throws
        try
        {
            int r = channel.read(readBuffer); // throws
            if(trace)trace("after channel.read()", "readBuffer", readBuffer);
            readBuffer.flip();

            if(r==-1) // we expect client to send some bytes first.
            {
                kill(new IOException("client closed the connection"));
                // finally free readBuffer
                return;
            }

            if(r==0)
            {
                if(trace)trace("enter read select");
                Async<Void> await = channel.awaitReadable(true);
                promise.onCancel(await::cancel);
                await.onCompletion(this::onReadable);
                // finally free readBuffer
                return;
            }

            // r>=1
            int firstByte = readBuffer.get(0);
            if(trace)trace("first byte", firstByte);
            if(firstByte==22) // SSL3 and TLS1
            {
                if(trace)trace("ssl detected");

                // transfer readBuffer to SslHandshaker
                ByteBuffer initBuffer = readBuffer;
                readBuffer = null;

                toSsl.handshake(channel, _Util.cast(promise), initBuffer);
            }
            else // Plain
            {
                if(trace)trace("plain detected");

                TcpConnection conn = toPlain.convert(channel);
                conn.unread( _ByteBufferUtil.copyOf(readBuffer) );
                // finally free readBuffer
                promise.succeed(conn);
            }
        }
        finally
        {
            if(readBuffer!=null) // still owned by us. free it
            {
                if(trace)trace("free readBuffer");
                toSsl.sslBufferPool.checkIn(readBuffer);
            }
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
        System.out.print("SslDetector   " +connId+ "   " +(traceId++)+"   ");
        for(Object arg : args)
        {
            System.out.print(arg);
            System.out.print(" ");
        }
        System.out.println();
    }
}
