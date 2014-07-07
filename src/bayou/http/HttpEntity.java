package bayou.http;

import _bayou._tmp._HttpUtil;
import bayou.bytes.ByteSource;
import bayou.mime.ContentType;

import java.time.Instant;

/**
 * Http entity.
 * <p>
 *     An http entity is included in an http message to represent a resource.
 *     See <a href="http://tools.ietf.org/html/rfc2616#section-7">RFC2616 &sect;7 Entity</a>.
 * </p>
 * <p>
 *     An entity contains a {@link #body() body}, and metadata like {@link #contentType() contentType}.
 * </p>
 * <h4 id=sharable>Sharable Entity</h4>
 * <p>
 *     An entity is sharable if its body can be read multiple times by multiple readers,
 *     potentially concurrently. A sharable entity can be cached and served to multiple http messages.
 * </p>
 */

// http entity (as termed in rfc2616. in new draft, it's called representation)
// all methods must be non-blocking. including body(), and all methods of the body.
// this may require entity constructor to block to collect info up front.
public interface HttpEntity
{


    /**
     * The body of this entity.
     * <p>
     *     This method must not return null. If the body is empty, return a ByteSource
     *     that contains 0 bytes.
     * </p>
     * <p>
     *     If this entity is <a href="#sharable">sharable</a>,
     *     each invocation of this method must return a new, independent ByteSource.
     * </p>
     * <p>
     *     If this entity is not sharable, this method can only be invoked once;
     *     further invocations throw IllegalStateException.
     * </p>
     * <p>
     *     The caller must close() the returned ByteSource eventually.
     * </p>
     * <p>
     *     If the producer of the entity wants to treat the body as a sink to write to,
     *     see {@link bayou.bytes.BytePipe}.
     * </p>
     * <p>
     *     It's possible that the consumer of an `HttpEntity` never calls the `body()` method.
     *     The implementation of `body()` should be lazy, creating body only on demand.
     * </p>
     * @throws IllegalStateException if invoked more than once on a non-sharable entity.
     */
    ByteSource body() throws IllegalStateException;
    // request body read() has default timeout
    // if there's a problem getting the body, use ErrorByteSource to delay the problem till 1st read()
    // total bytes returned by the body must agree with contentLength() (if non-null)
    // each getBody() call should return a new independent ByteSource at pos=0.
    // some entity can be shared; getBody() can be invoked multiple times by different threads
    // for other entity, getBody() can only be called once; following calls should throw IllegalStateException
    //   do not try to return the same body on 2nd call. getBody() should return a body in init state (pos=0)
    //   if needed, caller can save the body and pass it around.
    //
    // an impl might attempt to make getBody() lighter/lazier, by delaying work till read().
    // but usually read() imm follows getBody(), so the laziness is not awarded.


    /**
     * The content type.
     * <p>
     *     This property corresponds to the
     *     <a href="http://tools.ietf.org/html/rfc2616#section-14.17">Content-Type</a> header.
     * </p>
     * <p>
     *     This method <i>may</i> return null if the content type is unknown,
     *     then the recipient of the entity must guess the content type.
     *     However this is strongly discouraged.
     * </p>
     */
    ContentType contentType();
    // if request entity has no Content-Type, app probably should simply reject it.



    /**
     * The length of the body; null if unknown.
     * <p>
     *     This property corresponds to the
     *     <a href="http://tools.ietf.org/html/rfc2616#section-14.13">Content-Length</a> header.
     * </p>
     * <p>
     *     If {@link #contentEncoding() contentEncoding}!=null,
     *     contentLength is the length after the encoding is applied.
     * </p>
     * <p>
     *     The default implementation returns null.
     * </p>
     */
    // this info is not very important; null is fine.
    default Long contentLength(){ return null; }
    // question whether this method should be in the body object, i.e. getBodyLength() => getBody().getLength()
    // we don't like that; server wants to cheaply get the length without getBody(), e.g. for a HEAD request.
    // so we consider length as a metadata outside body source. in parable:
    //     HttpEntity            File             List
    //     getBodyLength()       length           size()
    //     getBody()             inputStream      iterator()


    /**
     * Encoding that has been applied on the body; null if none.
     * <p>
     *     This property corresponds to the
     *     <a href="http://tools.ietf.org/html/rfc2616#section-14.11">Content-Encoding</a> header.
     * </p>
     * <p>
     *     For example, if the entity is an html document, and contentEncoding=="gzip",
     *     the entity body is the html document compressed in gzip format.
     * </p>
     * <p>
     *     If two entities represent the same resource but with different content encodings,
     *     they should have different ETags.
     * </p>
     * <p>
     *     If contentEncoding!=null, the reader of the body needs to decode it to get the original content.
     * </p>
     * <p>
     *     {@link HttpServer} always automatically decodes the request entity, or rejects the request
     *     if it is unable to decode.
     *     A server app can assume that contentEncoding==null for all request entities.
     * </p>
     * <p>
     *     The default implementation returns null.
     * </p>
     */
    default String contentEncoding(){ return null; }



    /**
     * When this entity was last modified; null if unknown.
     * <p>
     *     This property corresponds to the
     *     <a href="http://tools.ietf.org/html/rfc2616#section-14.29">Last-Modified</a> header.
     * </p>
     * <p>
     *     The default implementation returns null.
     * </p>
     * <p>
     *     Note: the precision of HTTP Last-Modified value is 1 second, which is very poor.
     *     It may not adequate for fast changing resources.
     *     Consider ETag instead. The default implementation of {@link #etag()} is based on
     *     <code>lastModified()</code> with nano-second precision.
     * </p>
     */
    default Instant lastModified(){ return null; }
    // response only. (if the header exists in a request, it won't be reflected by this method)

    /**
     * When this entity expires; null if unknown.
     * <p>
     *     This property corresponds to the
     *     <a href="http://tools.ietf.org/html/rfc2616#section-14.21">Expires</a> header.
     * </p>
     * <p>
     *     The default implementation returns null.
     * </p>
     */
    // Expires should be within a year, according to http spec.
    // we don't bother app to know that. the server will cut it off if it's too long.
    default Instant expires(){ return null; }   // note: the name comes from HTTP header "Expires"
    // response only. (if the header exists in a request, it won't be reflected by this method)
    // can be in the past, meaning already expired. that should discourage http caches from caching the response.
    // however app doesn't have to do that; returning null will also make server add "Cache-Control: no-cache".


    /**
     * The entity tag; null if none.
     * <p>
     *     This property corresponds to the
     *     <a href="http://tools.ietf.org/html/rfc2616#section-14.19">ETag</a> header.
     *     For example, if <code>entity.etag()=="123"</code>, the response will contain the header <br><br>
     *         <code style="padding-left:4em;">Entity: "123"</code>
     * </p>
     * <p>
     *     See <a href="http://tools.ietf.org/html/rfc2616#section-3.11">RFC2616 &sect;3.11 Entity Tags</a>.
     *     Only "strong" ETag is supported by this method. "Weak" ETag cannot be expressed by this method.
     * </p>
     * <p>
     *     Legal chars in ETag: <code>0x21-0xFF, except 0x22(") and 0x7F(DEL)</code>.
     *     Backslash <code>0x5C(\)</code> is legal but should not be used either,
     *     because some interpret it as the escape char.
     * </p>
     * <p>
     *     The default implementation is based on {@link #lastModified()} and {@link #contentEncoding()}:
     * </p>
     * <ul>
     *     <li>
     *         if lastModified()==null, return null
     *     </li>
     *     <li>
     *         otherwise, return a string representing lastModified in nano-second precision,
     *         with contentEncoding as suffix if it's non-null.
     *         Example value: <code>"t-53319ee8-623a7c0.gzip"</code>.
     *     </li>
     * </ul>
     */
    default String etag()
    {
        return _HttpUtil.defaultEtag(lastModified(), contentEncoding());
    }
    // ETag. response only.
    // without the surrounding DQUOTE. for example
    //     ETag: "abc"   <=>  etag()="abc"  (not "\"abc\"")
    // ETag char:    %x21 / %x23-7E / obs-text   (see http bis)
    // must not contain DQUOTE; should not contain backslash
    // empty ETag is legal



    // ----------------------------------------------------------------------------------------------


    //for request, only Content-Length and Content-Type header is reflected by this interface
    //others should not be in request anyway, e.g. Last-Modified, Content-Location
    //if app does expect them, it needs to look up from request headers.

    // relevant headers that could be, but are not, included in this interface:
    //     Content-Language:  not commonly used? may add it in future
    //     Content-Location:  not commonly used
    //     Vary: not commonly used. difficult to make it right
    //     Content-Range:  getBody() is always the whole body.
    //     Accept-Ranges:  implied. server auto set it if bodyLength is known
    //     (Content-Disposition? not usually needed. app can set it in response header)
    // if needed they can be set in response header map



}
