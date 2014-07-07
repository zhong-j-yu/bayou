package bayou.gzip;

import _bayou._tmp._Util;
import bayou.bytes.ByteSource;
import bayou.http.HttpEntity;
import bayou.http.HttpEntityWrapper;

/**
 * Compresses the origin HttpEntity with gzip.
 * <p>
 *     The body of this entity will be the body of the origin entity compressed in gzip format.
 * </p>
 * <p>
 *     If the origin entity has ETag, this entity will append ".gzip" to it.
 * </p>
 * <p>
 *     This entity is <a href="../http/HttpEntity.html#sharable">sharable</a> iff the origin entity is sharable.
 *     If this entity is sharable and to be served to multiple http messages, consider the
 *     {@link bayou.http.CachedHttpEntity} wrapper.
 * </p>
 */
public class GzipHttpEntity implements HttpEntityWrapper
{
    // warning: this compression can be pretty slow.
    // could actually slow down client download speed. may drop overall server throughput. may max out CPU.
    // not a problem for a light-load server. user should benchmark it for his environment/load.

    HttpEntity origin;
    int compressionLevel;

    /**
     * Create a GzipHttpEntity, which compresses the origin entity body with gzip.
     * <p>
     *     `compressionLevel` can be 0-9. For on-the-fly gzip, consider compressionLevel=1,
     *      which is faster, with pretty good compression ratio.
     * </p>
     */
    public GzipHttpEntity(HttpEntity origin, int compressionLevel)
    {
        this.origin = origin;

        _Util.require( 0<=compressionLevel && compressionLevel<=9, "0<=compressionLevel<=9");

        this.compressionLevel = compressionLevel;
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
     * The body after compression.
     * <p>
     *     The body of this entity will be the body of the origin entity compressed in gzip format.
     * </p>
     * <p>
     *     See also {@link GzipByteSource}.
     * </p>
     */
    @Override
    public ByteSource body()
    {
        ByteSource originBody = origin.body();
        // next line doesn't throw, or we need to close originBody
        return new GzipByteSource(originBody, compressionLevel); // no throw
    }


    /**
     * The length of the body (after compression).
     * <p>
     *     This implementation usually returns null, because the length is unknown
     *     without actually performing the compression.
     * </p>
     */
    @Override
    public Long contentLength()
    {
        return null;  // we don't know the length of compressed data
        // after a body is read to EOF, we can know its length.
        // we could save the length and return it in this method,
        // assuming the length doesn't change for each body() call.
    }

    /**
     * The content encoding, containing "gzip".
     * <p>
     *     If the origin entity has no content encoding, this method returns "gzip".
     * </p>
     * <p>
     *     If the origin entity has a content encoding, this method appends ", gzip" to it.
     * </p>
     */
    @Override
    public String contentEncoding()
    {
        String encoding = origin.contentEncoding();
        if(encoding==null || encoding.isEmpty())
            return "gzip";
        else  // multiple encoding? not likely
            return encoding + ", gzip";   // "in the order in which they were applied"
        // if origin is already in "gzip", we'll gzip again. caller should avoid that.
    }

    /**
     * The ETag.
     * <p>
     *     If the origin entity has no ETag, this method returns null.
     * </p>
     * <p>
     *     If the origin entity has an ETag, this method appends ".gzip" to it.
     * </p>
     */
    @Override
    public String etag()
    {
        String etag = origin.etag();
        if(etag==null)
            return null;
        else
            return etag + ".gzip";
    }
    // if origin has ETag, gzip will append ".gzip" to ETag
    // the problems:
    //     new ETag may conflict with origin ETag space.
    //     same origin, but diff compressLevel, should have diff ETag. we don't do that here.
    //         user should use consistent compressionLevel; 1 is usually good enough.
    //         otherwise user must assign different ETag. (can simply append compressionLevel, .gzip0 - .gzip9)
    // user can subclass and override getEtag() for proper behavior


}
