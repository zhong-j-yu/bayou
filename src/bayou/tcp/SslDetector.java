package bayou.tcp;

import _bayou._tmp._JobTimeout;
import bayou.async.Async;
import bayou.util.Result;

import javax.net.ssl.SSLException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

// read the first byte from client. if it is 22, assume it's SSL handshake request.
class SslDetector
{
    static void start(TcpServerX server, TcpChannel channel)
    {
        new SslDetector(server, channel).read();
    }

    TcpServerX server;
    TcpChannel channel;
    _JobTimeout timeout;

    public SslDetector(TcpServerX server, TcpChannel channel)
    {
        this.server = server;
        this.channel = channel;
    }

    void kill(Exception cause) // no throw
    {
        if(trace)trace("kill", cause);
        channel.close(); // no throw

        if(timeout!=null)
            timeout.complete();

        // nothing else to clean up

        if(cause!=null)
            TcpServer.logErrorOrDebug(cause);

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

    // we'll use plain buffer pool for our reading.
    // we are in the mixed mode, probably the connection is more likely plain than ssl.
    // if the connection turns out to be ssl, we need to copy bytes to an ssl buffer.
    // since ssl client hello is small (<200 bytes), that is not expensive.

    void readE() throws Exception
    {
        if(trace)trace("readE()");
        ByteBuffer readBuffer = server.plainReadBufferPool.checkOut(); // throws
        try
        {
            int r = channel.read(readBuffer); // throws
            if(trace)trace("after channel.read()", "readBuffer", readBuffer);
            readBuffer.flip();

            if(r==-1) // that's fine
            {
                kill(null);
                // finally free readBuffer
                return;
            }

            if(r==0)
            {
                if(trace)trace("enter read select");
                Async<Void> await = channel.awaitReadable(true);
                if(timeout==null)
                    timeout = new _JobTimeout(server.confSslHandshakeTimeout, "NbTcpServerX.confSslHandshakeTimeout");
                timeout.setCurrAction(await);
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
                // copy bytes into a ssl buffer, and free the plain initBuffer

                // should be small, <200 bytes. otherwise we trash it
                if(readBuffer.remaining() > server.sslBufferPool.getBufferCapacity())  // can't copy to ssl buffer
                    throw new SSLException("supposed to be SSL Client Hello, " +
                            "but message too big, size="+readBuffer.remaining());

                ByteBuffer sslBuffer = server.sslBufferPool.checkOut();
                sslBuffer.put(readBuffer); // won't overflow
                sslBuffer.flip();
                if(trace)trace("copied to sslBuffer", sslBuffer);
                // free readBuffer early, before SslHandshaker.start()
                if(trace)trace("free readBuffer");
                server.plainReadBufferPool.checkIn(readBuffer);
                readBuffer = null;

                SslHandshaker.start(server, channel, timeout, sslBuffer);
            }
            else // Plain
            {
                if(trace)trace("plain detected");
                ByteBuffer plainBuffer = readBuffer;
                readBuffer = null; // hand over, don't free it in finally{}

                if(timeout!=null)
                    timeout.complete();
                PlainTcpConnection.start(server, channel, plainBuffer);
            }
        }
        finally
        {
            if(readBuffer!=null) // still owned by us. free it
            {
                if(trace)trace("free readBuffer");
                server.plainReadBufferPool.checkIn(readBuffer);
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
