package _bayou._http;

import bayou.http.SimpleHttpEntity;
import bayou.mime.ContentType;

import java.nio.ByteBuffer;
import java.time.Instant;

// no Last-Modified, ETag for request entity
public class _SimpleRequestEntity extends SimpleHttpEntity
{

    public _SimpleRequestEntity(ContentType contentType, ByteBuffer bytes)
    {
        super(contentType, bytes);
    }

    public _SimpleRequestEntity(ContentType contentType, byte[] bytes)
    {
        super(contentType, bytes);
    }

    @Override
    public Instant lastModified()
    {
        return null;
    }

    @Override
    public String etag()
    {
        return null;
    }

    @Override
    public Instant expires()
    {
        return null;
    }
}
