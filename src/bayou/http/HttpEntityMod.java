package bayou.http;

import bayou.bytes.ByteSource;
import bayou.mime.ContentType;

import java.time.Instant;

// a wrapper of an origin entity, where some metadata can be overridden
class HttpEntityMod implements HttpEntity
{
    // sentinel values for fields not overridden

    static final String originString = new String(new char[0]);

    static final Instant originInstant = Instant.ofEpochMilli(0x1d96cf3c234e2664L);
    // hopefully this is a unique object. there's no Instant constructor accessible.


    final HttpEntity origin;
    HttpEntityMod(HttpEntity origin)
    {
        this.origin = origin;
    }

    @Override
    public ByteSource body() throws IllegalStateException
    {
        return origin.body();
    }

    @Override
    public ContentType contentType()
    {
        return origin.contentType();
    }

    @Override
    public Long contentLength()
    {
        return origin.contentLength();
    }

    @Override
    public String contentEncoding()
    {
        return origin.contentEncoding();
    }




    Instant lastModified=originInstant;
    @Override
    public Instant lastModified()
    {
        if(lastModified==originInstant)
            return origin.lastModified();
        else
            return this.lastModified;
    }

    Instant expires=originInstant;
    @Override
    public Instant expires()
    {
        if(expires==originInstant)
            return origin.expires();
        else
            return this.expires;
    }

    String etag=originString;
    @Override
    public String etag()
    {
        if(etag==originString)
            return origin.etag();
        else
            return this.etag;
    }

    Boolean etagIsWeak=null; // null means no mod
    @Override
    public boolean etagIsWeak()
    {
        if(etagIsWeak==null)
            return origin.etagIsWeak();
        else
            return etagIsWeak.booleanValue();
    }
}
