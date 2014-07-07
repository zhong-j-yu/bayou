package bayou.http;

import bayou.bytes.ByteSource;
import bayou.bytes.ThrottledByteSource;

/**
 * Throttles the origin HttpEntity.
 * <p>
 *     The body of this entity will be the body of the origin entity,
 *     but throttled for throughput.
 * </p>
 * <p>
 *     See {@link ThrottledByteSource}.
 * </p>
 * <p>
 *     This entity is <a href="HttpEntity.html#sharable">sharable</a> iff the origin entity is sharable.
 * </p>
 */
public class ThrottledHttpEntity implements HttpEntityWrapper
{
    HttpEntity origin;
    int bufferSize;
    ThrottledByteSource.Curve curve;

    /**
     * Create a throttled wrapper of the origin entity.
     * <p>
     *     See {@link ThrottledByteSource#ThrottledByteSource(ByteSource, int, ThrottledByteSource.Curve)}.
     * </p>
     */
    public ThrottledHttpEntity(HttpEntity origin, int bufferSize, ThrottledByteSource.Curve curve)
    {
        this.origin = origin;

        this.bufferSize = bufferSize;
        this.curve = curve;
    }

    /**
     * Create a throttled wrapper of the origin source, with a linear curve.
     * <p>
     *     See {@link ThrottledByteSource#ThrottledByteSource(ByteSource, int, long, long)}
     * </p>
     */
    public ThrottledHttpEntity(HttpEntity origin, int bufferSize, long b0, long bytesPerSecond)
    {
        this(origin, bufferSize, ThrottledByteSource.Curve.linear(b0, bytesPerSecond));
    }


    /**
     * The origin entity.
     */
    @Override
    public HttpEntity getOriginEntity()
    {
        return origin;
    }

    /**
     * The entity body.
     * <p>
     *     The body of this entity will be the body of the origin entity,
     *     but throttled for throughput.
     * </p>
     */
    @Override
    public ByteSource body()
    {
        ByteSource originBody = origin.body();
        // next line doesn't throw, or we need to close originBody
        return new ThrottledByteSource(originBody, bufferSize, curve);
    }

}
