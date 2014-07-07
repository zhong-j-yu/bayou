package bayou.http;

import bayou.bytes.ByteSource;
import bayou.mime.ContentType;

import java.time.Instant;

/**
 * A wrapper of HttpEntity.
 * <p>
 *     This interface implements HttpEntity methods by forwarding the calls
 *     to the origin entity.
 *     A subclass needs to implement {@link #getOriginEntity()},
 *     and selectively override some HttpEntity methods.
 * </p>
 */
public interface HttpEntityWrapper extends HttpEntity
{
    // CAUTION: subclass is holding a reference to origin entity, which could be wasting memory

    /**
     * The origin entity.
     */
    abstract HttpEntity getOriginEntity();

    /**
     * Equivalent to <code>getOriginEntity().body()</code> by default.
     */
    @Override
    default ByteSource body()
    {
        return getOriginEntity().body();
    }

    /**
     * Equivalent to <code>getOriginEntity().contentType()</code> by default.
     */
    @Override
    default ContentType contentType()
    {
        return getOriginEntity().contentType();
    }

    /**
     * Equivalent to <code>getOriginEntity().contentLength()</code> by default.
     */
    @Override
    default Long contentLength()
    {
        return getOriginEntity().contentLength();
    }

    /**
     * Equivalent to <code>getOriginEntity().contentEncoding()</code> by default.
     */
    @Override
    default String contentEncoding()
    {
        return getOriginEntity().contentEncoding();
    }

    /**
     * Equivalent to <code>getOriginEntity().lastModified()</code> by default.
     */
    @Override
    default Instant lastModified()
    {
        return getOriginEntity().lastModified();
    }

    /**
     * Equivalent to <code>getOriginEntity().expires()</code> by default.
     */
    @Override
    default Instant expires()
    {
        return getOriginEntity().expires();
    }

    /**
     * Equivalent to <code>getOriginEntity().etag()</code> by default.
     */
    @Override
    default String etag()
    {
        return getOriginEntity().etag();
    }

}
