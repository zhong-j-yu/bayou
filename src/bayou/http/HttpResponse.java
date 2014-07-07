package bayou.http;

import _bayou._tmp._CharDef;
import _bayou._tmp._HttpUtil;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.file.FileHttpEntity;
import bayou.gzip.GzipHttpEntity;
import bayou.html.HtmlDoc;
import bayou.mime.ContentType;
import bayou.mime.FileSuffixToContentType;
import bayou.mime.HeaderMap;
import bayou.mime.Headers;
import bayou.mime.TokenParams;
import bayou.text.TextDoc;
import bayou.text.TextHttpEntity;
import bayou.util.function.FunctionX;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static bayou.mime.Headers.Accept_Encoding;

/**
 * Http response.
 * <p>
 *     An http response contains a status, some headers and cookies,
 *     and (in most cases) an entity.
 * </p>
 * <p>
 *     An HttpResponse is sharable iff its entity is
 *     <a href="HttpEntity.html#sharable">sharable</a>.
 *     A sharable response can be cached and served to multiple requests.
 * </p>
 * <p>
 *     See {@link HttpResponseImpl} for a mutable implementation.
 * </p>
 * <p>
 *     This interface contains some static methods for creating/transforming responses,
 *     for example {@link #text(int, CharSequence...)}, {@link #gzip(HttpResponse)}.
 * </p>
 * <p>
 *     Most of these methods return <code>HttpResponseImpl</code>
 *     which the caller can modify. Note also that
 *     <code>HttpResponseImpl</code> is a subtype of <code>Async&lt;HttpResponse&gt;</code>.
 * </p>
 * <p>
 *     Example usage:
 * </p>
 * <pre>
 *     Async&lt;HttpResponse&gt; handle(HttpRequest request)
 *     {
 *         return HttpResponse
 *             .text(200, "goodbye world")      // create a response
 *             .header("Connection", "close")   // set a header
 *             .then(HttpResponse::gzip);       // transform with gzip
*      }
 * </pre>
 *
 */

public interface HttpResponse
{

    // no httpVersion() like in HttpRequest? is it useful?
    //    currently our server responds with same http version as request 's version.


    /**
     * Response status.
     * For example, {@link HttpStatus#c200_OK "200 OK"}.
     * <p>
     *     This method must not return 1xx status (e.g. "100 Continue");
     *     1xx responses are handled automatically by underlying libraries.
     * </p>
     */
    HttpStatus status();
    // shouldn't be 1xx.  100-continue is handled by server already.  101 not supported.

    /**
     * Shorthand for <code>status().code()</code>.
     */
    public default int statusCode()
    {
        return status().code();
    }

    /**
     * Response headers.
     * <p>
     *     The returned Map is case insensitive for lookup.
     *     The caller should treat the Map as read-only.
     * </p>
     * <p>
     *     The following headers should <b>not</b> be included in this Map:
     * </p>
     * <ul>
     *     <li>
     *         Entity headers (e.g. <code>"Content-Type"</code>).
     *         Entity metadata should be expressed on the {@link #entity()}.
     *     </li>
     *     <li>
     *         <code>"Set-Cookie"</code> headers. Cookies are represented in {@link #cookies()}.
     *     </li>
     *     <li>
     *         <code>"Content-Length"</code> and <code>"Transport-Encoding"</code> headers.
     *         They are handled automatically by underlying libraries.
     *     </li>
     * </ul>
     * <p>
     *     See {@link Headers} for common header names.
     *     See {@link HeaderMap} for a suitable implementation.
     *     See {@link TokenParams} for a certain type of header values.
     * </p>
     * <p>
     *     Note that each header contains a single value.
     *     Per spec, multiple headers with the same name is identical in semantics
     *     to a single combined header:
     * </p>
     * <pre>
     *     Foo: value1   |
     *     Foo: value2   |  ====&gt;  |  Foo: value1, value2, value3
     *     Foo: value3   |
     * </pre>
     */
    Map<String,String> headers();
    // if an entity header is both in headers(), and in entity(), the value from the entity wins
    // header value can be empty - the header exists in response with an empty value
    //   (however, not sure which header in response can contain empty value)
    //   app should avoid giving empty header values
    // can set Connection:close to instruct the server to close the connection after response is written.
    //
    // no convenience method `header(name)->value`. not used by server app often.


    /**
     * Response cookies.
     * <p>
     *     The returned list should be treated as read-only by the caller.
     * </p>
     * <p>
     *     Although response cookies are expressed in <code>"Set-Cookie"</code> headers on the wire,
     *     they cannot be treated as normal headers (due to a historical oversight).
     *     Therefore {@link #headers()} cannot contain <code>"Set-Cookie"</code> headers;
     *     and, response cookies must be expressed in {@link #cookies()}.
     * </p>
     *
     * @see bayou.http.HttpResponseImpl#cookies()
     * @see CookieJar
     */
    List<Cookie> cookies();
    // we need special handling for cookies in response
    // 1. multiple Set-Cookie headers cannot be combined into one line
    // 2. cookie standards are in a chaos; a helper class is helpful to users

    /**
     * Response entity; null if none.
     * <p>
     *     A response must have an entity, except in the following cases:
     * </p>
     * <ul>
     *     <li>
     *         Status is 204. <code>entity()</code> must return null.
     *     </li>
     *     <li>
     *         Status is 304. <code>entity()</code> must return null.
     *         However, a server app rarely needs to generate a 304 response,
     *         because HttpServer usually handles conditional GET requests
     *         {@link HttpServerConf#autoConditional(boolean) automatically}.
     *     </li>
     *     <li>
     *         Status is 1xx. However, this interface forbids 1xx in {@link #status()} anyway.
     *     </li>
     * </ul>
     * <p>
     *     If the request method is HEAD, the response should contain an entity,
     *     as it would have for a GET request.
     *     A server app usually treats HEAD as GET anyway, so this isn't a concern.
     *     The metadata of the entity will be accessed;
     *     the body of the entity will not be read or transmitted.
     * </p>
     */
    HttpEntity entity();








    // ===================================================================================
    // static
    // ===================================================================================


    /**
     * Create a response with `data` as the body.
     *
     * @see bayou.http.SimpleHttpEntity
     */
    static HttpResponseImpl data(int statusCode, String contentType, byte[] data)
    {
        _Util.require(contentType != null, "contentType!=null");
        ContentType ct = ContentType.parse(contentType);

        return new HttpResponseImpl(HttpStatus.of(statusCode), new SimpleHttpEntity(ct, data));
    }

    /**
     * Create a <code>"text/plain;charset=UTF-8"</code> response.
     *
     * @see bayou.text.TextHttpEntity
     */
    static HttpResponseImpl text(int statusCode, CharSequence... texts)
    {
        return new HttpResponseImpl(HttpStatus.of(statusCode), new TextHttpEntity(texts));
    }
    // no text(int statusCode, TextDoc doc). not used often

    /**
     * Create a <code>"text/html;charset=UTF-8"</code> response.
     * <p>
     *     See {@link HtmlDoc#toResponse(int)} to create a response for an HtmlDoc.
     * </p>
     * @see bayou.text.TextHttpEntity
     */
    static HttpResponseImpl html(int statusCode, CharSequence... htmlContent)
    {
        return new HttpResponseImpl(HttpStatus.of(statusCode),
            new TextHttpEntity(ContentType.text_html_UTF_8, htmlContent));
    }
    // we don't have html(statusCode, HtmlDoc) here, to avoid dependency HttpResponse->HtmlDoc,
    // which is problematic for HotHttpHandler shared classes.

    /**
     * Create a "redirect" response.
     * <p>
     *     The status code is <code>303</code>.
     *     See {@link #redirect(HttpStatus, String)} if you want a different status code.
     * </p>
     * <p>
     *     The uri can be absolute or relative, for example
     *     <code>"http://example.com"</code> or <code>"/show?id=123"</code>.
     * </p>
     */
    static HttpResponseImpl redirect(String uri)
    {
        return redirect(HttpStatus.c303_See_Other, uri);
    }

    /**
     * Create a "redirect" response.
     * <p>
     *     The status code can be <code>301/302/303/307</code>.
     *     A new code <code>308</code> is being proposed but not widely adopted yet.
     * </p>
     * <p>
     *     Choose the status code carefully. In most cases, <code>303</code> is the proper code.
     *     See also {@link #redirect(String)} which uses 303.
     * </p>
     * <p>
     *     The uri can be absolute or relative, for example
     *     <code>"http://example.com"</code> or <code>"/show?id=123"</code>.
     * </p>
     */
    static HttpResponseImpl redirect(HttpStatus status, String uri)
    {
        if(!_CharDef.check(uri, _CharDef.Uri.legalChars))
            throw new IllegalArgumentException("invalid uri: "+uri);
        // not a very thorough validation. but at least all chars are ascii.

        HttpEntity entity = new TextHttpEntity(ContentType.text_plain_US_ASCII, uri); // all uri chars are ascii
        HttpResponseImpl response = new HttpResponseImpl(status, entity);
        response.headers.put(Headers.Location, uri);
        return response;
    }

    /**
     * Create an internal error response.
     * <p>
     *     The status code is <code>500</code>.
     * </p>
     * <p>
     *     The response body contains an "error id" which is a hash value of the `error`.
     *     The "error id" is also logged locally for cross reference.
     * </p>
     */
    // transform error to a vague response. don't reveal `e` to end user.
    // error is logged with some hash, hash is sent to client.
    static HttpResponseImpl internalError(Throwable error)
    {
        // log and investigate. handler usually shouldn't throw
        String errorId = HttpHelper.logErrorWithId(error);

        return HttpHelper.simpleResp(HttpStatus.c500_Internal_Server_Error,
            "Internal Server Error [error id: " + errorId + "]\r\n\r\n" + new Date());
        // no Connection:close? though something is screwed, connection is not necessarily corrupted
    }

    /**
     * Create a response serving the file.
     * <p>
     *     The Content-Type will be derived from `filePath`,
     *     using {@link bayou.mime.FileSuffixToContentType#getGlobalInstance()}.
     * </p>
     * <p>
     *     Be careful if `filePath` is constructed from end user input;
     *     normalize it first and make sure the user is allowed to access the file.
     * </p>
     * <p>
     *     If any IO exception occurred (e.g. the file does not exist on disk),
     *     the response will be "404 Not Found" instead.
     * </p>
     * <p>
     *     Note that this method returns <code>Async&lt;HttpResponse&gt;</code>,
     *     not an <code>HttpResponseImpl</code>. If you need to modify the response,
     *     for example to add a header, try
     * </p>
     * <pre>
     *     Async&lt;HttpResponse&gt; fileResp = HttpResponse.file(200, FILE_PATH);
     *     fileResp = fileResp.then( r -&gt; new HttpResponseImpl(r).header("foo", "bar") );
     * </pre>
     */
    static Async<HttpResponse> file(int statusCode, String filePath)
    {
        return Async.execute(() -> {
            try
            {
                FileHttpEntity entity = new FileHttpEntity(Paths.get(filePath), null);
                return new HttpResponseImpl(HttpStatus.of(statusCode), entity);
            }
            catch (IOException e)
            {
                // usually NoSuchFileException or AccessDeniedException. whatever, display 404
                // don't expose error detail or file path to client.
                // programmer probably was fairly certain the file can be served.
                // treat the problem as unexpected, log as error.
                String errorId = HttpHelper.logErrorWithId(e);
                return HttpHelper.simpleResp(HttpStatus.c404_Not_Found,
                    "File Not Found [error id: " + errorId + "]\r\n\r\n" + new Date());
            }
            // other exceptions
        });
    }

    /**
     * Cache the response in memory, particularly the body.
     * <p>
     *     This is a response transforming method, which can often be used in
     *     {@link Async#then(FunctionX)}.
     * </p>
     * <p>
     *     A cached response is often saved, to be served to multiple requests.
     * </p>
     * <p>
     *     Example usage:
     * </p>
     * <pre>
     *     static final Async&lt;HttpResponse&gt; respCached =
     *
     *                     HttpResponse.file(200, "/tmp/big.txt")
     *                                 .then(HttpResponse::gzip)
     *                                 .then(HttpResponse::cache);
     *
     * </pre>
     * <p>
     *     See also {@link CachedHttpEntity}.
     * </p>
     */
    static HttpResponseImpl cache(HttpResponse response)
    {
        HttpEntity entity = response.entity();
        if(entity!=null)
            entity = new CachedHttpEntity(entity);
        return new HttpResponseImpl(response).entity(entity);
    }

    /**
     * Compress the response with "gzip".
     * <p>
     *     This is a response transforming method, which can often be used in
     *     {@link Async#then(FunctionX)}.
     * </p>
     * <p>
     *     Example usage:
     * </p>
     * <pre>
     *     // in http handler
     *
     *     return HttpResponse.file(200, "/tmp/big.txt")
     *                        .then(HttpResponse::gzip);
     *
     * </pre>
     * <p>
     *     Response header <code>"Vary: Accept-Encoding"</code> will be added.
     *     Entity metadata <code>"Etag"</code> and <code>"Content-Encoding"</code> will be modified,
     *     see {@link GzipHttpEntity}.
     * </p>
     *
     * @see HttpRequest#acceptsGzip()
     * @see GzipHttpEntity
     * @see HttpServerConf#autoGzip(boolean)
     *
     */
    // may be useful to manually control gzip on individual response (without server auto gzip)
    // gzip level 1
    static HttpResponseImpl gzip(HttpResponse response)
    {
        HttpEntity entity = response.entity();
        HttpResponseImpl resp = new HttpResponseImpl(response);
        if(entity==null)
            return resp;
        entity = new GzipHttpEntity(entity, 1);
        resp.entity(entity);
        _HttpUtil.addVaryHeader(resp.headers(), Accept_Encoding);
        return resp;
    }

    /**
     * Throttle the response.
     * <p>
     *     The throttled response will serve the body no faster than
     *     the specified `bytesPerSecond`.
     * </p>
     * <p>
     *     Throttling may be useful for simulating slow network on local dev machine.
     * </p>
     * @see bayou.http.ThrottledHttpEntity
     */
    // note: bufferSize=8K
    // x=bufferSize/bytesPerSecond shouldn't be too big; or source will stall x seconds between servings.
    static HttpResponseImpl throttle(long bytesPerSecond, HttpResponse response)
    {
        HttpEntity entity = response.entity();
        if(entity!=null)
            entity = new ThrottledHttpEntity(entity, 8*1024, 0, bytesPerSecond);
        return new HttpResponseImpl(response).entity(entity);
    }
    // example:
    //     map( resp-> throttle(1000, resp) );
    // why not have a method
    //     FunctionX<HttpResponse, HttpResponseImpl> throttle(long bytesPerSecond)
    // so app can do
    //     map( throttle(1000) )
    // ? because we are not using wildcards in map(Func), this would often fail to compile.
    // throttle is probably not used often anyway, we don't care very much.


}