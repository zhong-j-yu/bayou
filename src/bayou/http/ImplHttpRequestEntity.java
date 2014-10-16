package bayou.http;

import _bayou._async._Asyncs;
import _bayou._tmp._ByteBufferUtil;
import bayou.async.Async;
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

class ImplHttpRequestEntity implements HttpEntity
{
    ImplConn hConn;
    ImplHttpRequest request;
    Long bodyLength; // null if unknown/chunked
    // if chunked, we'll know the total length at end of body. we could update this var then.
    // but too late to be useful. if user really need that, user can count the total bytes.

    public ImplHttpRequestEntity(ImplConn hConn, ImplHttpRequest request, Long bodyLength)
    {
        this.hConn = hConn;
        this.request = request;
        this.bodyLength = bodyLength;
    }

    public Long contentLength()
    {
        return bodyLength;
    }

    ContentType contentType;
    public ContentType contentType()
    {
        return contentType;
    }

    String contentEncoding;
    public String contentEncoding()
    {
        return contentEncoding;
    }

    public String etag(){ return null; } // should not exist in request

    public Instant lastModified(){ return null; } // should not exist in request

    public Instant expires(){ return null; } // should not exist in request

    boolean getBody_invoked;
    @Override
    public ByteSource body()
    {
        if(getBody_invoked)
            throw new IllegalStateException("getBody() can be called only once on an HTTP request entity"); // per spec
        getBody_invoked =true;

        if(request.responded)
            throw new IllegalStateException("request body cannot be read after response is written");

        return getBodyInternal();
    }
    static final String resp100String = "HTTP/1.1 100 Continue\r\n\r\n";
    static final byte[] resp100Bytes = resp100String.getBytes(StandardCharsets.UTF_8);

    Body body;  // server may access it to drain it
    Body getBodyInternal()
    {
        if(body==null)
        {
            if(bodyLength ==null)
            {
                request.stateBody = 2;
                body = new ImplHttpRequestChunkedBody(hConn, request);
            }
            else // >=0
            {
                long len = bodyLength.longValue();

                request.stateBody = len>0 ? 2 : 3;  // special handling for len=0 (actually state was already 3)
                body = new SimpleBody(hConn, request, len);
            }
        }
        return body;
    }

    static abstract class Body implements ByteSource
    {
        ImplConn hConn;
        ImplHttpRequest request;
        Duration confReadTimeout;
        long t0;
        long minThroughput; // >=0
        boolean closed;

        Body(ImplConn hConn, ImplHttpRequest request)
        {
            this.hConn = hConn;
            this.request = request;
            this.confReadTimeout = hConn.conf.readTimeout;
            this.minThroughput = hConn.conf.readMinThroughput;
        }

        // skip()
        // cannot really skip client input

        @Override
        public Async<Void> close()
        {
            closed = true;
            return Async.VOID;
            // caller may close earlier before the entire message body is consumed.
        }


        // body is implemented as non-blocking, because NbConnection is non-blocking.
        // we'll need to convert it to async
        abstract ByteBuffer nb_read() throws Exception;
        // return NbConnection.STALL, END, or data. Guarantee: data.remaining>0

        static final ByteBuffer END = ByteBuffer.wrap(new byte[0]); // sentinel


        @Override
        public Async<ByteBuffer> read() throws IllegalStateException
        {
            if(request.responded) // treat it as benign, don't throw directly
                return Result.failure(new IllegalStateException(
                    "request body cannot be read after response is written"));

            if(closed)
                throw new IllegalStateException("closed");

            if(request.state100==2)
                return Result.failure(new IOException("failed to respond 100 Continue."));

            if(request.state100==1) // we need to send 100 continue once
            {
                request.state100=2;

                hConn.nbConn.queueWrite(ByteBuffer.wrap(resp100Bytes));  // 25 bytes

                try
                {
                    long w = hConn.nbConn.write(); // throws.

                    if(w<resp100Bytes.length)
                        throw new IOException("unable to flush: "+w+"/"+resp100Bytes.length);
                }
                catch (Exception e)
                {
                    return Result.failure(new IOException("failed to respond 100 Continue", e));
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

            if(t0==0)
                t0 = System.currentTimeMillis();

            // every read() has a default timeout to guard against slow clients
            // app can rely on that timeout, or set a shorter timeout.
            // if app sets a longer timeout, it won't be effective; try conf the longer timeout instead
            return read2(null).timeout(confReadTimeout);
            // readMinThroughput is not enough to cut off slow clients if read() can stall forever
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
                return _Asyncs.fail(promise, e);
            }

            if(bb== END)
            {
                return _Asyncs.fail(promise, End.instance());
            }

            if(bb!= TcpConnection.STALL)
            {
                // data. will not be empty (no big deal even if empty)
                return _Asyncs.succeed(promise, bb);
            }

            // read stalled

            final Promise<ByteBuffer> promiseF = (promise!=null)?promise : new Promise<ByteBuffer>();
            Async<Void> awaitReadable = hConn.nbConn.awaitReadable(/*accepting*/false);
            promiseF.onCancel(awaitReadable::cancel);
            awaitReadable.onCompletion(result -> {
                Exception error = result.getException();
                if(error!=null)
                    promiseF.fail(error);
                else
                    read2(promiseF);
            });
            return promiseF;
            // after nbConn turns readable, nb_read() can stall again
            // 1. ssl conn, got more raw bytes, but not a complete ssl record.
            // 2. chunked,  got more raw bytes, but none of next chunk-data
            // we'll loop till deadline.
        } // read2()

    } // class Body




    static class SimpleBody extends Body
    {
        long bodyLength;

        SimpleBody(ImplConn hConn, ImplHttpRequest request, long bodyLength)
        {
            super(hConn, request);

            this.bodyLength = bodyLength;
        }

        long bytesRead; // from hConn

        @Override
        public ByteBuffer nb_read() throws Exception
        {
            if(bytesRead >= bodyLength)
                return END;
            // note in the degenerate case bodyLength==0, EOF is imm reached.
            //    stateBody=3 was done by special handling earlier.

            // errors may be recoverable, hConn remains ok. caller may try read() again, tho unlikely it will.
            // in any case, hConn will be closed after response, unless hConn.readingReqBody turns false.

            ByteBuffer bb = hConn.nbConn.read(); // throws

            if(bb== TcpConnection.STALL)
            {
                // check throughput (only when read stalls)
                long time = System.currentTimeMillis() - t0;
                if(time> 10_000) // don't check in the beginning
                {
                    long minRead = minThroughput * time / 1000;
                    if(bytesRead < minRead)
                        throw new IOException("Client upload throughput too low");
                }

                return TcpConnection.STALL;
            }

            if(bb== TcpConnection.TCP_FIN || bb== SslConnection.SSL_CLOSE_NOTIFY)
                throw new IOException("connection closed before end of entity body");

            assert bb.remaining()>0;
            bytesRead += bb.remaining();

            if(bytesRead < bodyLength)
                return bb;

            // bytesRead >= bodyLength, we reached the end of body
            request.stateBody = 3;

            if(bytesRead== bodyLength) // most likely
                return bb;

            // bytesRead>bodyLength, more bytes available than Content-Length. this is uncommon
            int extra = (int)(bytesRead - bodyLength);
            ByteBuffer slice =  _ByteBufferUtil.slice(bb, bb.remaining()-extra);
            // extra bytes save as leftover, for next request head
            hConn.nbConn.unread(bb);
            return slice;
        }

    }

}
