package bayou.http;

import bayou.bytes.ByteSource;
import bayou.bytes.ByteSourceCache;
import bayou.mime.ContentType;

import java.time.Instant;

/**
 * Caches the origin entity in memory.
 * <p>
 *     The body and metadata of the origin entity are cached in memory.
 *     This is useful for frequently requested entities.
 * </p>
 * <p>
 *     This entity is always <a href="HttpEntity.html#sharable">sharable</a>,
 *     whether the origin entity is sharable or not.
 * </p>
 */
public class CachedHttpEntity implements HttpEntity
{
    // do not use HttpEntityWrapper, which keeps a reference to origin entity.

    final Object lock(){ return this; }
    HttpEntity origin; // null after first body() call
    volatile ByteSourceCache bodyCache_volatile;
    volatile Long bodyLength_volatile;  // can start as null. eventually non-null if copying succeeds.

    // copy of origin properties
    final ContentType contentType;
    final String contentEncoding;
    final Instant lastModified;
    final Instant expires;
    final String etag;

    /**
     * Create a cache of the origin entity.
     */
    public CachedHttpEntity(HttpEntity origin)
    {
        this.origin = origin;
        this.bodyLength_volatile = origin.contentLength();

        // lazy,don't create bodyCache yet.
        // tho ByteSourceCache is lazy by itself, we don't know if the body from origin entity is lazy or not.

        this.contentType = origin.contentType();
        this.contentEncoding = origin.contentEncoding();
        this.etag = origin.etag();
        this.lastModified = origin.lastModified();
        this.expires = origin.expires();
    }

    /**
     * The entity body.
     * <p>
     *     The returned ByteSource is based on an in-memory cache of the origin entity body.
     *     See {@link ByteSourceCache}.
     * </p>
     * <p>
     *     This entity is sharable, the body() method can be called multiple times.
     * </p>
     */
    @Override
    public ByteSource body()
    {
        ByteSourceCache bodyCacheL;
        if((bodyCacheL= bodyCache_volatile)==null)
        {
            synchronized (lock())
            {
                if((bodyCacheL= bodyCache_volatile)==null)
                {
                    ByteSource body = origin.body();
                    origin=null;
                    bodyCache_volatile = bodyCacheL = new ByteSourceCache(body, bodyLength_volatile);
                }
            }
        }
        return bodyCacheL.newView();
    }

    /**
     * Return <code>"contentType"</code> of the origin entity.
     */
    @Override
    public ContentType contentType()
    {
        return contentType;
    }

    /**
     * The length of the body.
     * <p>
     *     The length may be null/unknown initially;
     *     however, it eventually becomes known after caching is done.
     *     See {@link ByteSourceCache#getTotalBytes()}.
     * </p>
     */
    @Override
    public Long contentLength()
    {
        Long len = bodyLength_volatile;
        if(len==null)
        {
            ByteSourceCache bodyCacheL = bodyCache_volatile;
            if(bodyCacheL!=null)
            {
                // dataSize becomes known after copying succeeds
                len = bodyCacheL.getTotalBytes(); // a volatile read
                if(len!=null)
                    bodyLength_volatile = len;
            }
        }
        return len;
    }

    /**
     * Return <code>"contentEncoding"</code> of the origin entity.
     */
    @Override
    public String contentEncoding()
    {
        return contentEncoding;
    }

    /**
     * Return <code>"lastModified"</code> of the origin entity.
     */
    @Override
    public Instant lastModified()
    {
        return lastModified;
    }

    /**
     * Return <code>"expires"</code> of the origin entity.
     */
    @Override
    public Instant expires()
    {
        return expires;
    }

    /**
     * Return <code>"etag"</code> of the origin entity.
     */
    @Override
    public String etag()
    {
        return etag;
    }

}
