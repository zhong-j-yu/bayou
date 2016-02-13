package bayou.http;

import _bayou._async._Asyncs;
import _bayou._tmp._ByteBufferUtil;
import _bayou._tmp._ControlException;
import bayou.async.Async;
import bayou.async.AsyncIterator;
import bayou.async.Promise;
import bayou.bytes.ByteSource;
import bayou.mime.ContentType;
import bayou.ssl.SslConnection;
import bayou.tcp.TcpConnection;
import bayou.util.End;
import bayou.util.Result;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

class ImplHttpEntity implements HttpEntity
{
    Long bodyLength; // null if unknown/chunked
    // if chunked, we'll know the total length at end of body. we could update this var then.
    // but too late to be useful. if user really need that, user can count the total bytes.
    Body body; // non-null
    boolean bodyCalled;

    // for request
    public ImplHttpEntity(ImplConn hConn, Long bodyLength)
    {
        this.bodyLength = bodyLength;

        HttpServerConf conf = hConn.conf;
        if(bodyLength ==null)
        {
            body = new ImplHttpChunkedBody(hConn.tcpConn, conf.readTimeout, conf.readMinThroughput,
                conf.requestBodyMaxLength);
        }
        else // >=0
        {
            body = new FixedLengthBody(hConn.tcpConn, conf.readTimeout, conf.readMinThroughput,
                bodyLength.longValue());
        }
    }

    void expect100(ImplConn hConn, ImplHttpRequest request)
    {
        request.state100 = 1;
        body.send100 = new Send100(hConn, request);
    }

    // for response
    public ImplHttpEntity(TcpConnection tcpConn, boolean reqIsHead, boolean chunked, Long bodyLength)
    {
        this.bodyLength = bodyLength;

        Duration readTimeout = Duration.ofSeconds(15); // todo
        long minThroughput = 0; // todo

        if(reqIsHead)
        {
            body = new FixedLengthBody(tcpConn, readTimeout, minThroughput, 0);
        }
        else
        {
            if(chunked)
            {
                long requestBodyMaxLength = Long.MAX_VALUE;
                body = new ImplHttpChunkedBody(tcpConn, readTimeout, minThroughput, requestBodyMaxLength);
            }
            else if(bodyLength!=null) // >=0
            {
                body = new FixedLengthBody(tcpConn, readTimeout, minThroughput, bodyLength.longValue());
            }
            else
            {
                body = new FinTerminatedBody(tcpConn, readTimeout, minThroughput);
            }
        }

        body.awaitEof = new Promise<>();
        if(body.eof()) // possible (fixed length of 0)
            body.awaitEof.succeed(null);

    }


    @Override
    public Long contentLength()
    {
        return bodyLength;
    }

    @Override
    public ByteSource body()
    {
        if(bodyCalled)
            throw new IllegalStateException("body() can be called only once"); // per spec
        bodyCalled=true;

        return body;
    }


    ContentType contentType;
    @Override
    public ContentType contentType()
    {
        return contentType;
    }

    String contentEncoding;
    @Override
    public String contentEncoding()
    {
        return contentEncoding;
    }

    String etag;
    @Override
    public String etag()
    {
        return etag;
    }

    boolean etagIsWeak;
    @Override
    public boolean etagIsWeak()
    {
        return etagIsWeak;
    }

    Instant lastModified;
    @Override
    public Instant lastModified()
    {
        return lastModified;
    }

    Instant expires;
    @Override
    public Instant expires()
    {
        return expires;
    }





    static abstract class Body implements ByteSource
    {
        TcpConnection tcpConn;
        Duration confReadTimeout;
        long t0;
        long minThroughput; // >=0
        boolean closed;

        long bytesRead; // updated by subclass

        Promise<Void> awaitEof; // fail if body is closed before EOF

        Body(TcpConnection tcpConn, Duration confReadTimeout, long minThroughput)
        {
            this.tcpConn = tcpConn;
            this.confReadTimeout = confReadTimeout;
            this.minThroughput = minThroughput;
        }

        // skip()
        // cannot really skip client input

        @Override
        public Async<Void> close()
        {
            closed = true;

            if(awaitEof!=null && !awaitEof.isCompleted())
            {
                // caller may close earlier before the entire message body is consumed.
                // generally, we want to fail awaitEof, abandon connection without reading remaining bytes.
                // however, the body may have internal logical EOF marker, e.g. it's in a gzip-ed format.
                // the app closes the body after seeing that marker, but we haven't reached our EOF here.
                // let's attempt to reach our EOF by a quick non-blocking read. it probably works most of times.
                if(reachEof())
                    awaitEof.succeed(null);
                else
                    awaitEof.fail(new _ControlException("closed before EOF"));
            }

            return Async.VOID;
        }

        Async<Void> drain()
        {
            // note: each readX() will check confClientUploadTimeout, throughput, max body length
            return AsyncIterator.forEach(this::readX, input -> {}); // simply discard
            // readX() does not check `closed` flag.
        }

        abstract boolean eof();

        // body is implemented as non-blocking, because TcpConnection is non-blocking.
        // we'll need to convert it to async
        abstract ByteBuffer nb_read() throws Exception;
        // return TcpConnection.STALL, END, or data. Guarantee: data.remaining>0

        static final ByteBuffer END = ByteBuffer.wrap(new byte[0]); // sentinel


        @Override
        public Async<ByteBuffer> read()
        {
            if(closed) // treat it as benign, don't throw directly. (app may not be the closer)
                return Result.failure(new IllegalStateException("closed"));

            return readX();
        }

        Send100 send100;
        Async<ByteBuffer> readX()
        {
            if(send100 !=null)
            {
                try
                {
                    send100.send();
                }
                catch (Exception e) // state100=2
                {
                    return fail(null, e);
                }

                // 100 is sent
                send100 =null;
            }

            if(t0==0)
                t0 = System.currentTimeMillis();

            // every read() has a default timeout to guard against slow clients
            // app can rely on that timeout, or set a shorter timeout.
            // if app sets a longer timeout, it won't be effective; try conf the longer timeout instead
            return read2(null).timeout(confReadTimeout);
            // readMinThroughput is not enough to cut off slow clients if read() can stall forever
        }
        Async<ByteBuffer> fail(Promise<ByteBuffer> promise, Exception e)
        {
            if(awaitEof!=null && !awaitEof.isCompleted())
                awaitEof.fail(e);

            return _Asyncs.fail(promise, e);
        }
        Async<ByteBuffer> read2(Promise<ByteBuffer> promise)
        {
            ByteBuffer bb;
            try
            {
                bb = nb_read();
            }
            catch (Exception e)
            {
                return fail(promise, e);
            }

            if(bb== END)
            {
                if(awaitEof!=null && !awaitEof.isCompleted())
                    awaitEof.succeed(null);

                return _Asyncs.fail(promise, End.instance());
            }

            if(bb!= TcpConnection.STALL)
            {
                // data. will not be empty (no big deal even if empty)
                return _Asyncs.succeed(promise, bb);
            }

            // read stalled

            // check throughput when read stalls. `bytesRead` updated by subclass.
            long time = System.currentTimeMillis() - t0;
            if(time> 10_000) // don't check in the beginning
            {
                long minRead = minThroughput * time / 1000;
                if(bytesRead < minRead)
                    return fail(promise, new IOException("Client upload throughput too low"));
            }

            final Promise<ByteBuffer> promiseF = (promise!=null)?promise : new Promise<ByteBuffer>();
            Async<Void> awaitReadable = tcpConn.awaitReadable(/*accepting*/false);
            promiseF.onCancel(awaitReadable::cancel);
            awaitReadable.onCompletion(result -> {
                Exception error = result.getException();
                if(error!=null)
                    fail(promiseF, error);
                else
                    read2(promiseF);
            });
            return promiseF;
            // after tcpConn turns readable, nb_read() can stall again
            // 1. ssl conn, got more raw bytes, but not a complete ssl record.
            // 2. chunked,  got more raw bytes, but none of next chunk-data
            // we'll loop till deadline.
        } // read2()

        boolean reachEof()
        {
            if(send100 !=null)
                return false;

            while(true)
            {
                ByteBuffer bb;
                try
                {
                    bb = nb_read();
                }
                catch (Exception e)
                {
                    return false;
                }

                if(bb== END)
                    return true;

                if(bb==TcpConnection.STALL)
                    return false;

                // data. discard and read next bb
            }
        }

    } // class Body


    static class Send100
    {
        static final String resp100String = "HTTP/1.1 100 Continue\r\n\r\n";
        static final byte[] resp100Bytes = resp100String.getBytes(StandardCharsets.UTF_8);

        ImplConn hConn;
        ImplHttpRequest request;
        Send100(ImplConn hConn, ImplHttpRequest request)
        {
            this.hConn = hConn;
            this.request = request;
        }
        void send() throws Exception
        {
            if(request.state100==2)
                throw new IOException("failed to respond 100 Continue.");

            if(request.state100==1) // we need to send 100 continue once
            {
                request.state100=2;

                hConn.tcpConn.queueWrite(ByteBuffer.wrap(resp100Bytes));  // 25 bytes

                try
                {
                    hConn.tcpConn.write(); // throws.

                    if(hConn.tcpConn.getWriteQueueSize()>0)
                        throw new IOException("unable to flush: "+resp100Bytes.length);
                }
                catch (Exception e)
                {
                    throw new IOException("failed to respond 100 Continue", e);
                }
                // since we are only writing 25 bytes, we expect write() to complete.
                // if by some freak accident it doesn't complete here, treat it like an error.
                // if state100=2, response will not be written, conn will be aborted.

                // we could handle the case better, wait till the 100 response is written
                // then call read2(). leave that for future. for now, consider it a rare case.

                request.state100=3;

                if(hConn.dump!=null)
                    hConn.dump.print(hConn.respId(), resp100String);
            }
        }
    }


    static class FixedLengthBody extends Body
    {
        long bodyLength;

        FixedLengthBody(TcpConnection tcpConn, Duration confReadTimeout, long minThroughput, long bodyLength)
        {
            super(tcpConn, confReadTimeout, minThroughput);

            this.bodyLength = bodyLength;
        }

        @Override
        public boolean eof()
        {
            return bytesRead >= bodyLength;
        }

        @Override
        public ByteBuffer nb_read() throws Exception
        {
            if(bytesRead >= bodyLength)
                return END;

            ByteBuffer bb = tcpConn.read(); // throws

            if(bb== TcpConnection.STALL)
            {
                return TcpConnection.STALL;
            }

            if(bb== TcpConnection.TCP_FIN || bb== SslConnection.SSL_CLOSE_NOTIFY)
                throw new IOException("connection closed before end of entity body");

            assert bb.remaining()>0;
            bytesRead += bb.remaining();

            if(bytesRead < bodyLength)
                return bb;

            // bytesRead >= bodyLength, we reached the end of body

            if(bytesRead== bodyLength) // most likely
                return bb;

            // bytesRead>bodyLength, more bytes available than Content-Length. this is uncommon
            int extra = (int)(bytesRead - bodyLength);
            bytesRead = bodyLength;
            ByteBuffer slice =  _ByteBufferUtil.slice(bb, bb.remaining()-extra);
            // extra bytes save as leftover, for next request head
            tcpConn.unread(bb);
            return slice;
        }

    }


    static class FinTerminatedBody extends Body
    {
        boolean fin;

        FinTerminatedBody(TcpConnection tcpConn, Duration confReadTimeout, long minThroughput)
        {
            super(tcpConn, confReadTimeout, minThroughput);
        }

        @Override
        public boolean eof()
        {
            return fin;
        }

        @Override
        public ByteBuffer nb_read() throws Exception
        {
            if(fin)
                return END;

            ByteBuffer bb = tcpConn.read(); // throws

            if(bb== TcpConnection.STALL)
            {
                return TcpConnection.STALL;
            }

            if(bb== TcpConnection.TCP_FIN || bb== SslConnection.SSL_CLOSE_NOTIFY)
            {
                fin = true;
                return END;
            }

            bytesRead += bb.remaining();
            return bb;
        }

    }

}
