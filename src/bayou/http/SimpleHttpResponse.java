package bayou.http;


import bayou.async.Async;
import bayou.async.AutoAsync;
import bayou.bytes.ByteSource;
import bayou.mime.ContentType;
import bayou.util.End;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A simple HttpResponse.
 */
public class SimpleHttpResponse implements HttpResponse, AutoAsync<HttpResponse>
{
    HttpStatus status;
    SimpleEntity entity;

    public SimpleHttpResponse(HttpStatus status, ContentType contentType, byte[] bytes)
    {
        this.status = status;
        this.entity = new SimpleEntity(contentType, bytes);
    }

    @Override
    public HttpStatus status()
    {
        return status;
    }

    @Override
    public Map<String, String> headers()
    {
        return Collections.emptyMap();
    }

    @Override
    public List<Cookie> cookies()
    {
        return Collections.emptyList();
    }

    @Override
    public HttpEntity entity()
    {
        return entity;
    }

    static class SimpleEntity implements HttpEntity
    {
        ContentType contentType;
        byte[] bytes;

        SimpleEntity(ContentType contentType, byte[] bytes)
        {
            this.contentType = contentType;
            this.bytes = bytes;
        }

        @Override
        public ByteSource body() throws IllegalStateException
        {
            return new SimpleBody(bytes);
        }

        @Override
        public Long contentLength()
        {
            return (long)bytes.length;
        }

        @Override
        public ContentType contentType()
        {
            return contentType;
        }
    }

    static class SimpleBody implements ByteSource
    {
        byte[] bytes;

        SimpleBody(byte[] bytes)
        {
            this.bytes = bytes;
        }

        @Override
        public Async<ByteBuffer> read()
        {
            if(bytes==null)
                return End.async();

            Async<ByteBuffer> abb = Async.success(ByteBuffer.wrap(bytes));
            bytes=null;
            return abb;
        }

        @Override
        public Async<Void> close()
        {
            return Async.VOID;
        }
    }

}

