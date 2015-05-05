package bayou.http;

import _bayou._http._SimpleRequestEntity;
import bayou.form.FormData;
import bayou.form.FormParser;
import bayou.mime.ContentType;
import bayou.mime.Headers;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
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
     * @see #scheme()
     */
    boolean isHttps();

    /**
     * The scheme, either "https" or "http".
     * <p>
     *     <code>scheme()</code> and <code>isHttps()</code> must be consistent with each other.
     * </p>
     * <p>
     *     The default implementation is
     * </p>
     * <pre>
     *     return isHttps() ? "https" : "http";
     * </pre>
     */
    default String scheme()
    {
        return isHttps() ? "https" : "http";
    }

    /**
     * Certificates of the client. See {@link javax.net.ssl.SSLSession#getPeerCertificates()}.
     * <p>
     *     Return an empty list if this information is not available.
     * </p>
     * <p>
     *     The default implementation returns an empty list.
     * </p>
     *
     */
    default List<java.security.cert.X509Certificate> certs()
    {
        return Collections.emptyList();
    }
    // we only expose the certs, instead of the whole SSLSession.
    // other methods in SSLSession are probably uninteresting to app.
    //
    // it's arguable that the SSL session ID can be used as the browser session ID
    // (because major browsers will try to reuse the same SSL session for multiple connections)
    // therefore it can be used instead of creating a session-id cookie;
    // furthermore SSLSession(mutable) can store session data key-value pairs (tho only in this VM).
    // but the session-id cookie practice is simple, widely understood, and works with HTTP too.
    //
    // HttpResponse.certs() is not needed, because the server cert is not very interesting to the client app.


    /**
     * Request method.
     * <p>
     *     Request methods are case sensitive, and typically in all upper cases, e.g. "GET".
     * </p>
     * <p>
     *     If the method is "HEAD", an HttpServer application can usually treat it as "GET".
     * </p>
     * <p>
     *     See also {@link HttpServerConf#supportedMethods(String...)}.
     * </p>
     */
    String method();

    /**
     * The "Host" header of the request.
     * <p>
     *     This method must return a non-null, valid <code>"host[:port]"</code>, in lower case.
     *     e.g. <code>"example.com", "localhost:8080"</code>.
     * </p>
     * <p>
     *     The "host" part could be
     * </p>
     * <ul>
     *     <li>a domain name, e.g. <code>"example.com"</code>, <code>"localhost"</code></li>
     *     <li>an IPv4 literal, e.g. <code>"192.168.68.100"</code></li>
     *     <li>an IPv6 literal enclosed in <code>[]</code>, e.g. <code>"[2001:db8::ff00:42:8329]"</code></li>
     * </ul>
     * <p>
     *     The "port" part is mandatory if the request method is "CONNECT".
     * </p>
     * <p>
     *     The default implementation returns <code>header("Host").toLowerCase()</code>.
     * </p>
     */
    // it's confusing to call this method "host" while it may contain port.
    default String host()
    {
        return header(Headers.Host).toLowerCase();
    }

    /**
     * The request URI.
     * <p>
     *     In most cases, <code>uri()</code> returns <b>path-query</b>, which is defined here as
     *     (in the context of <a href="http://tools.ietf.org/html/rfc7230#section-2.7">RFC7230</a>)
     * </p>
     * <pre>
     *     path-query = absolute-path [ "?" query ]   </pre>
     * <p>
     *     for example: <code>"/", "/page1", "/search?q=cat"</code>.
     *     They are non-empty and always start with "/".
     * </p>
     * <p>
     *     However, there are 2 special cases:
     * </p>
     * <ul>
     *     <li><p>
     *         for a <a href="http://tools.ietf.org/html/rfc7231#section-4.3.6">"CONNECT"</a> request,
     *         <code>uri()</code> returns <code>host:port</code>, same as the {@link #host()} method.
     *     </p></li>
     *     <li><p>
     *         for an <a href="http://tools.ietf.org/html/rfc7230#section-5.3.4">"OPTIONS *"</a> request,
     *         <code>uri()</code> returns "*"
     *     </p></li>
     * </ul>
     * <p>
     *     <i>( Note that CONNECT and OPTIONS are disabled by default in
     *     {@link bayou.http.HttpServerConf#supportedMethods(String...)} )</i>
     * </p>
     * <p>
     *     The <code>uri()</code> method never returns an absolute URI;
     *     see {@link #absoluteUri()} instead.
     * </p>
     * <p>
     *     Our definition of <code>uri()</code> is consistent with
     *     <i><a href="http://tools.ietf.org/html/rfc2616#section-5.1.2">Request-URI</a></i> in RFC2616,
     *     as well as
     *     <i><a href="http://tools.ietf.org/html/rfc7230#section-5.3">request-target</a></i> in RFC7230.
     * </p>
     *
     * <p><b>Chars in URI</b></p>
     * <p>
     *     We use java <code>String</code> to represent URIs,
     *     however, a URI is really a sequence of octets.
     *     Each <code>char</code> in the <code>String </code>
     *     should be interpreted as a <code>byte</code>. Applications may need to convert the String
     *     to byte[] (using ISO-8859-1 charset), convert "%HH" to byte `0xHH`, and convert bytes
     *     to unicode characters (typically as UTF-8).
     * </p>
     * <p>
     *     According to RFC3986, the only allowed octets in  URI path and query are<br>
     * </p>
     * <pre>
     *     ALPHA DIGIT - . _ ~
     *     ! $ ' &amp; ( ) * + , ; = : @ / ?
     *     %
     * </pre>
     * <p>
     *     Applications should generate URIs using only these octets for maximum interoperability.
     *     However, the reality is that major browsers and other HTTP implementations could also
     *     use other octets in URIs. To accommodate that, our library allows the following octets
     *     in URI path and query
     * </p>
     * <pre>     0x21-0xFF, excluding 0x23(#) </pre>
     *
     */
    String uri();

    /**
     * The absolute request URI.
     * <p>
     *     In most cases, absolute URI includes scheme, host, and path-query (see {@link #uri()} doc):
     * </p>
     * <pre>
     *     absoluteUri() = scheme()+"://"+host()+uri() </pre>
     * <p>
     *     for example, <code>"https://example.com/", "http://localhost:8080/search?q=cat"</code>
     * </p>
     * <p>
     *     However, in the 2 special cases of
     *     <a href="http://tools.ietf.org/html/rfc7231#section-4.3.6">"CONNECT"</a> and
     *     <a href="http://tools.ietf.org/html/rfc7230#section-5.3.4">"OPTIONS *"</a> requests
     *     (see {@link #uri()} doc)
     * </p>
     * <pre>
     *     absoluteUri() = scheme()+"://"+host() </pre>
     * <p>
     *     for example, <code>"https://example.com"</code>. Notice the lack of a trailing slash.
     * </p>
     * <p>
     *     Our definition of <code>absoluteUri()</code> is consistent with
     *     <i><a href="http://tools.ietf.org/html/rfc7230#section-5.5">Effective Request URI</a></i>
     *     in RFC7230.
     * </p>
     *
     */
    default String absoluteUri()
    {
        String s = isHttps() ? "https://" : "http://";
        String host = host(); // non-null

        String uri = uri();
        if(uri.startsWith("/")) // most common
            return s+host+uri;
        else // request must be either "OPTIONS *" or "CONNECT <host>"
            return s+host;
    }


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
     *     It should be two integers separated by a dot, e.g. "1.1".
     * </p>
     * <p>
     *     The default implementation returns "1.1".
     * </p>
     */
    default String httpVersion()
    {
        return "1.1";
    }



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
     *     There's no strict rules when a request must or must not contain an entity,
     *     though typically,
     * </p>
     * <ul>
     *     <li>
     *         A <code>GET/HEAD/DELETE</code> request should not have an entity
     *     </li>
     *     <li>
     *         A <code>POST/PUT</code> request should have an entity
     *     </li>
     * </ul>
     * <p>
     *     The recipient of a request needs to prepare for the possibility
     *     that <code>request.entity()==null</code> (even for POST/PUT).
     * </p>
     */
    HttpEntity entity();





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
     *     `uri` can be absolute (preferred) or just {@link #uri() path-query}.
     *     If not absolute, the caller should set the {@link bayou.http.HttpRequestImpl#host(String) host} afterwards.
     * </p>
     * <p>
     *     Examples:
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
     *     `uri` can be absolute (preferred) or just {@link #uri() path-query}.
     *     If not absolute, the caller should set the {@link bayou.http.HttpRequestImpl#host(String) host} afterwards.
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
        SimpleHttpEntity entity = new _SimpleRequestEntity(ct, entityBody);
        return new HttpRequestImpl("POST", uri, entity);
    }

}
