package bayou.http;

import bayou.form.FormData;
import bayou.form.FormParser;
import bayou.mime.ContentType;
import bayou.mime.Headers;

import java.net.InetAddress;
import java.util.Map;

/**
 * Http request.
 * <p>
 *     An http request contains a method, a URI, some headers, and optionally an entity.
 * </p>
 * <p>
 *     See {@link HttpRequestImpl} for a mutable implementation.
 * </p>
 */

// later: maybe a common interface HttpMessage for HttpRequest/Response. not sure if it's useful
// the only things common are headers() and entity().

public interface HttpRequest
{
    /**
     * IP address of the client.
     * <p>
     *     This IP address may reflect the <code>"X-Forwarded-For"</code> header,
     *     see {@link HttpServerConf#xForwardLevel(int)}.
     * </p>
     */
    InetAddress ip();

    /**
     * Whether this is an HTTPS request.
     * <p>
     *     This value may reflect the <code>"X-Forwarded-Proto"</code> header,
     *     see {@link HttpServerConf#xForwardLevel(int)}.
     * </p>
     */
    boolean isHttps();

    /**
     * Request method.
     * <p>
     *     Request methods are case sensitive, and typically in all upper cases, e.g. "GET".
     * </p>
     * <p>
     *     If the method is "HEAD", the server app can usually treat is as "GET".
     * </p>
     * <p>
     *     See also {@link HttpServerConf#supportedMethods(String...)}.
     * </p>
     */
    String method();

    /**
     * The "Host" header of the request.
     * <p>
     *     This method must return a valid <code>host[:port]</code> in lower case.
     *     for example, <code>"example.com", "localhost:8080"</code>.
     * </p>
     * <p>
     *     The default implementation returns <code>header("Host").toLowerCase()</code>.
     * </p>
     */
    default String host()
    {
        return header(Headers.Host).toLowerCase();
    }

    /**
     * The request URI.
     * <p>
     *     The request URI must start with a "/", for example, <code>"/query?term=cat"</code>.
     *     It does not contain scheme or host, see {@link #isHttps()} and {@link #host()} instead.
     * </p>
     * <p>
     *     This URI must not contain "fragment", for example, <code>"/path#frag"</code>.
     * </p>
     */
    String uri();
    // Request-URI in rfc2616, or "request-target" in new bis draft.
    // in the spec this part can be in 4 forms, this interface only supports the origin-form: abs-path[?query]
    //     "*" not supported. the only use case is with OPTION, which is handled by server
    //     authority-form not supported. the only use case is with CONNECT, which server always rejects
    //     absolute-form not supported. server always converts it to origin-form


    /**
     * The path component of the request URI.
     * <p>
     *     The path is the substring before the first question mark ("?").
     *     For example, if <code>uri()=="/query?term=cat"</code>, then <code>uriPath()=="/query"</code>.
     * </p>
     */
    default String uriPath()
    {
        String uri = uri();
        int q = uri.indexOf('?');
        if(q==-1)
            return uri;
        return uri.substring(0, q);
    }

    /**
     * Get the value of a parameter in the request URI.
     * <p>
     *     For example, if <code>uri()=="/query?term=cat"</code>, then <code>uriParam("term")=="cat"</code>.
     * </p>
     * <p>
     *     The query component of the request URI is parsed as <code>"application/x-www-form-urlencoded"</code>
     *     with UTF-8 charset. If there's any parsing error, this method returns <code>null</code>.
     * </p>
     * <p>
     *     See {@link FormParser} for more options.
     * </p>
     */
    // this impl should be overridden
    default String uriParam(String name)
    {
        try
        {
            return FormData.parse(uri()).param(name);
        }
        catch (Exception e)
        {
            return null; // null if parse error
        }
    }

    /**
     * The HTTP version of the request.
     * <p>
     *     A server app usually has no use of this information, except perhaps for logging.
     * </p>
     * <p>
     *     The default implementation returns "HTTP/1.1".
     * </p>
     */
    default String httpVersion()
    {
        return "HTTP/1.1";
    }
    // don't call it simply "version". this method not used often, ok to be verbose
    // we need this info for access log (common/combined format)
    // otherwise mostly useless to app.
    //   when processing request, headers are enough.
    //   when generating response, can make headers so that both 1.1 and 1.0 clients are happy.



    /**
     * Request headers.
     * <p>
     *     The returned Map is case insensitive for lookup.
     *     The caller should treat the Map as read-only.
     * </p>
     * <p>
     *     A request must contains a "Host" header with a valid <code>host[:port]</code> value.
     *     Note that the value is case insensitive.
     *     See also {@link #host()}.
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
     *         <code>"Content-Length"</code> and <code>"Transport-Encoding"</code> headers.
     *         They are handled automatically by underlying libraries.
     *     </li>
     * </ul>
     * <p>
     *     See {@link Headers} for common header names.
     *     See {@link bayou.mime.HeaderMap} for a suitable implementation.
     *     See {@link bayou.mime.TokenParams} for a certain type of header values.
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
    // the meaning of null and empty value can be different. e.g. the Accept header
    //   if a header in request cannot be empty per spec, and V is empty,
    //   app logic should effectively either treat it as null or reject as bad request.

    /**
     * Get the value of a header.
     * <p>
     *     The default implementation returns <code>headers().get(name)</code>.
     * </p>
     */
    default String header(String name)
    {
        return headers().get(name);
    }

    /**
     * Whether the client accepts "gzip" Content-Encoding.
     * <p>
     *     This method returns true if the "Accept-Encoding" header contains "gzip" with non-zero "q" value.
     *     See <a href="http://tools.ietf.org/html/rfc2616#section-14.3">RFC2616</a>.
     * </p>
     * <p>
     *     Almost all browsers accept "gzip".
     * </p>
     */
    // if app is targeting browser users, it's a safe bet that this property is true.
    // app may opt to assume that and omit checking this property.
    default boolean acceptsGzip()
    {
        String hAcceptEncoding = header(Headers.Accept_Encoding);
        return +1 == ImplRespMod.acceptEncoding(hAcceptEncoding, "gzip") ;
    }
    // we could also expose a more general acceptEncoding(hAcceptEncoding, encoding); probably not very useful.


    /**
     * Request cookies.
     * <p>
     *     The cookies are expressed in the "Cookie" request header;
     *     <code>cookies()</code> and <code>header("Cookie")</code> must be consistent with each other.
     * </p>
     * <p>
     *     Cookie names are case sensitive. The returned Map should be treated as read-only.
     * </p>
     * <p>
     *     The default implementation parses the "Cookie" header according to RFC6265;
     *     ill-formed entries are ignored. If there are multiple cookies with the same name,
     *     the last one wins.
     * </p>
     *
     * @see #cookie(String)
     */
    default Map<String,String> cookies()
    {
        return Cookie.parseCookieHeader( header(Headers.Cookie) );  // no throw. ok if header is null
    }

    /**
     * Get the value of a cookie.
     * <p>
     *     The default implementation returns <code>cookies().get(name)</code>.
     * </p>
     */
    default String cookie(String name)
    {
        return cookies().get(name);
    }

    /**
     * Request entity; null if none.
     * <p>
     *     A <code>GET/HEAD/DELETE</code> request should not have an entity;
     *     if it does, the server app should ignore the entity.
     * </p>
     * <p>
     *     A <code>POST/PUT</code> request should have an entity;
     *     if it does not, the server app should reject the request.
     * </p>
     * <p>
     *     A server app can read a request's entity body only once.
     * </p>
     */
    HttpEntity entity();
    // sharable? for client app
    // server app: do not read the body after the response is generated





    // static ===================================================================================


    /**
     * The current fiber-local request; null if none.
     * <p>
     *     See {@link #setFiberLocal(HttpRequest)}.
     * </p>
     * @throws IllegalStateException
     *         if `Fiber.current()==null`
     */
    public static HttpRequest current()
    {
        return HttpHelper.fiberLocalRequest.get();
    }

    /**
     * Set the fiber-local request.
     * <p>
     *     After this method is called on a fiber,
     *     the {@link #current() HttpRequest.current()} call on the same fiber will return `request`.
     * </p>
     * <p>
     *     The `request` arg can be null to clear the fiber-local request.
     * </p>
     * @throws IllegalStateException
     *         if `Fiber.current()==null`
     * @see bayou.async.FiberLocal
     */
    public static void setFiberLocal(HttpRequest request)
    {
        HttpHelper.fiberLocalRequest.set(request);
    }

    /**
     * Create a GET request.
     * <p>
     *     See <a href="HttpRequestImpl.html#uri-arg">explanation on uri</a>.
     * </p>
     * <p>
     *     Example Usage:
     * </p>
     * <pre>
     *     HttpRequest.toGet("http://example.com");
     *
     *     HttpRequest.toGet("/page1").host("localhost:8080");
     * </pre>
     * @see bayou.form.FormData#toRequest()
     */
    public static HttpRequestImpl toGet(String uri)
    {
        return new HttpRequestImpl("GET", uri, null);
    }

    /**
     * Create a POST request.
     * <p>
     *     The request contains an entity with the specified Content-Type and body.
     * </p>
     * <p>
     *     See <a href="HttpRequestImpl.html#uri-arg">explanation on uri</a>.
     * </p>
     * <p>
     *     Example usage:
     * </p>
     * <pre>
     *     HttpRequest.toPost("http://localhost:8080/action",
     *         "application/x-www-form-urlencoded", "a=1&amp;b=2".getBytes()
     *     ).header("Origin", "http://localhost:8080");
     * </pre>
     * <p>
     *     Note that to avoid the request being
     *     <a href="../form/FormParser.html#csrf">treated as CSRF by FormParser</a>,
     *     the simplest way is to set the "Origin" header.
     * </p>
     * @see bayou.form.FormData#toRequest()
     */
    public static HttpRequestImpl toPost(String uri, String entityContentType, byte[] entityBody)
    {
        ContentType ct = ContentType.parse(entityContentType); // throws
        SimpleHttpEntity entity = new SimpleHttpEntity(ct, entityBody);
        return new HttpRequestImpl("POST", uri, entity);
    }

}
