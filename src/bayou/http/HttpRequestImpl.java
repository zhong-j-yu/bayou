package bayou.http;

import _bayou._tmp._HttpUtil;
import bayou.form.FormData;
import bayou.mime.HeaderMap;
import bayou.mime.Headers;

import java.net.InetAddress;
import java.util.Collections;
import java.util.Map;

/**
 * A mutable implementation of HttpRequest.
 * <p>
 *     An <code>HttpRequestImpl</code> can be modified after construction, e.g. by {@link #header(String, String)}.
 *     These mutation methods return `this` for method chaining.
 * </p>
 *
 */
public class HttpRequestImpl implements HttpRequest
{
    InetAddress ip;
    boolean isHttps;
    String method;
    String uri;
    String httpVersion;
    final HeaderMap headers = new HeaderMap();
    HttpEntity entity;

    /**
     * Create an http request.
     *
     * <div>
     *     <p  id=uri-arg>
     *         The <code>`uri`</code> argument can be
     *     </p>
     *     <ul>
     *         <li id=uri-arg-full>
     *             a full URI like <code>"http://localhost:8080/abc"</code>.<br>
     *             The URI is parsed to extract <code>isHttps, host, uri</code> properties.
     *             Note that request {@link #uri()} returns only a partial URI without scheme/host,
     *             in this example, "/abc". <br> &nbsp;
     *         </li>
     *         <li id=uri-arg-partial>
     *             a partial URI like <code>"/query?term=cat"</code>. It is what {@link #uri()} will return. <br>
     *             The constructor will set default <code>isHttps=false, host="unknown-host"</code>.
     *             The request producer may want to change them afterwards, for example,
     *             <code>new HttpRequestImpl("GET", "/page1", null).host("localhost:8080");</code>
     *         </li>
     *     </ul>
     * </div>
     */
    public HttpRequestImpl(String method, String uri, HttpEntity entity)
    {
        // determine Host, uri, http/s
        String uriLo = uri.toLowerCase();
        int iHost;
        if(uriLo.startsWith("http://"))
        {
            isHttps = false;
            iHost = 7;
        }
        else if(uriLo.startsWith("https://"))
        {
            isHttps = true;
            iHost = 8;
        }
        else
        {
            this.uri = uri;  // assumed to be in the form abs-path[?query], validated later
            isHttps = false;
            iHost = 0;
            headers.put(Headers.Host, "unknown-host");
            // there must be a Host header. app can call host(string) after constructor.
        }
        if(iHost>0)  // extract Host from uriLo
        {
            String host;

            int iSlash = uriLo.indexOf('/', iHost);
            int iQuest = uriLo.indexOf('?', iHost);
            if(iSlash==-1&&iQuest==-1) //  http://abc.com
            {
                host = uriLo.substring(iHost);
                this.uri = "/";
            }
            else if(iSlash!=-1 &&(iQuest==-1 || iQuest>iSlash))  // http://abc.com/foo,  http://abc.com/foo?bar
            {
                host = uriLo.substring(iHost, iSlash);
                this.uri = uriLo.substring(iSlash);
            }
            else
            {
                //  http://abc.com?foo ,  http://abc.com?foo/bar
                // they are invalid according to RFC2616. but they are in the generic form of RFC3986.
                // let's accept them anyway, not much harm.
                host = uriLo.substring(iHost, iQuest);
                this.uri = "/" + uriLo.substring(iQuest);
            }

            if(host.isEmpty())
                throw new IllegalArgumentException("invalid uri: "+uri);
            headers.put(Headers.Host, host);  // not validated
            // Origin header: do not add automatically. simulate browsers that don't do that.
            // for unit testing POST requests, we want to fail if CSRF is not set up correctly,
            // so that app will fail if it omits to add CSRF field in form
        }

        if(!_HttpUtil.isOriginFormUri(this.uri))
            throw new IllegalArgumentException("invalid uri: "+uri);

        this.method = method; // not validated
        this.entity = entity;

        // defaults of other fields
        ip = InetAddress.getLoopbackAddress();
        httpVersion = "HTTP/1.1";
    }

    /**
     * Copy an http request.
     * <p>
     *     This can be used for request transformation, for example to change the URI, add a header, etc.
     *     The origin request is treated as read-only and will not be modified.
     * </p>
     */
    public HttpRequestImpl(HttpRequest originRequest)
    {
        // trust that request, no validation of its method/uri/headers.
        // if that request is broken, this request is broken in the same way.

        this.method = originRequest.method();
        this.uri = originRequest.uri();
        this.httpVersion = originRequest.httpVersion();
        this.entity = originRequest.entity();

        this.ip = originRequest.ip();
        this.isHttps = originRequest.isHttps();
        this.headers.putAll(originRequest.headers());  // make a copy.

    }

    /**
     * Get the client IP.
     * <p>
     *     See {@link #ip(java.net.InetAddress)} for changing the IP.
     * </p>
     */
    @Override
    public InetAddress ip()
    {
        return ip;
    }

    /**
     * Whether this is an HTTPS request.
     * <p>
     *     See {@link #isHttps(boolean)} for changing this property.
     * </p>
     */
    @Override
    public boolean isHttps()
    {
        return isHttps;
    }

    /**
     * Get the request method.
     * <p>
     *     See {@link #method(String)} for changing the method.
     * </p>
     */
    @Override
    public String method()
    {
        return method;
    }

    /**
     * Get the request URI.
     * <p>
     *     See {@link #uri(String)} for changing the URI.
     * </p>
     */
    @Override
    public String uri()
    {
        return uri;
    }
    // no methods like param(name,value) to add param to uri.
    // user can create FormData, call toGetRequest

    volatile FormData uriFormData_volatile; // lazy; derived from `uri`. will be reset if uri is changed.
    // FormData is not immutable, so we must be careful with safe publication
    // we want this request to be thread-safe if used in read-only fashion (post construction+modifications)
    FormData uriFormData() throws Exception
    {
        FormData f = uriFormData_volatile;
        if(f==null)
            uriFormData_volatile = f = FormData.parse(uri);
        return f;
    }

    // inherit javadoc
    @Override
    public String uriPath()
    {
        try
        {
            return uriFormData().action();
        }
        catch (Exception e)
        {
            return HttpRequest.super.uriPath();
        }
    }

    // inherit javadoc
    @Override
    public String uriParam(String name)
    {
        try
        {
            return uriFormData().param(name);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Get the HTTP version.
     */
    @Override
    public String httpVersion()
    {
        return httpVersion;
    }

    /**
     * Get the headers.
     * <p>
     *     The returned map is mutable; the request producer can manipulate it at will.
     *     See also {@link #header(String, String) header(name,value)}.
     * </p>
     */
    // creator of this request can mutate this map.
    // but it's better to call header(n,v) which validates header name/value.
    @Override
    public HeaderMap headers()
    {
        return headers;
    }

    /**
     * Get the request cookies.
     * <p>
     *     The cookies are parsed from the "Cookie" header; see parent javadoc.
     * </p>
     * <p>
     *     <!-- ideally the Map should support write-through. it's a little tricky. maybe later. -->
     *     <!-- for now, provide `cookies(Map)` workaround; user can mutate that Map beforehand. -->
     *     The returned Map is read-only. To modify cookies,
     *     see {@link #cookie(String, String)} and {@link #cookies(java.util.Map)} methods;
     *     or you can always set the "Cookie" header directly.
     * </p>
     */
    @Override
    public Map<String,String> cookies()
    {
        // we want this request to be thread-safe if used in read-only fashion (post construction+modifications)
        CookieCache cache = cookieCache;
        String hCookie = headers.get(Headers.Cookie);
        if(cache==null || !cache.consistent(hCookie))
        {
            Map<String,String> map = Cookie.parseCookieHeader(hCookie); // no throw. ok if hCookie==null
            map = Collections.unmodifiableMap(map);
            cookieCache = cache = new CookieCache(hCookie, map);
        }
        return cache.map;
    }
    CookieCache cookieCache; // lazy; derived from headers[Cookie]
    static class CookieCache // immutable
    {
        final String hCookie;
        final Map<String,String> map;
        CookieCache(String hCookie, Map<String, String> map)
        {
            this.hCookie = hCookie;
            this.map = map;
        }
        boolean consistent(String hCookie)
        {
            //noinspection StringEquality
            return this.hCookie==hCookie; // note: either/both side can be null
            // use == instead of equals() for speed. may return false negative, that's fine.
        }
    }



    /**
     * Get the entity.
     * <p>
     *     See {@link #entity(HttpEntity)} for changing the entity;
     * </p>
     */
    @Override
    public HttpEntity entity()
    {
        return entity;
    }


    // creator of this request object can change any properties ------------------------

    /**
     * Set the client IP.
     * @return this
     */
    public HttpRequestImpl ip(InetAddress ip)
    {
        this.ip = ip;
        return this;
    }

    /**
     * Set the <code>isHttps</code> property..
     * @return this
     */
    public HttpRequestImpl isHttps(boolean isHttps)
    {
        this.isHttps = isHttps;
        return this;
    }

    /**
     * Set the request method.
     * @return this
     */
    public HttpRequestImpl method(String method)
    {
        this.method = method;
        return this;
    }

    /**
     * Set the "Host" header.
     * @return this
     */
    public HttpRequestImpl host(String host)
    {
        // don't do host.toLowerCase() here. be consistent with header("Host", host) behavior.
        return header(Headers.Host, host);
    }

    /**
     * Set the request URI.
     * @return this
     */
    // no method for setting uri params. use FormData.toGet/PostRequest instead.
    public HttpRequestImpl uri(String uri)
    {
        if(!_HttpUtil.isOriginFormUri(uri))
            throw new IllegalArgumentException("invalid uri: "+uri);

        this.uri = uri;
        this.uriFormData_volatile=null;
        return this;
    }

    // httpVersion
    // no setter for httpVersion. if app needs that, it'll have to subclass


    /**
     * Set a header.
     * <p>
     *     If <code>value==null</code>, the header will be removed.
     * </p>
     *
     * @return this
     */
    // name/value will be validated
    public HttpRequestImpl header(String name, String value)
    {
        if(value==null)
            headers.remove(name);
        else
        {
            _HttpUtil.checkHeader(name, value);
            headers.put(name, value);
        }
        return this;
    }


    /**
     * Set a cookie.
     * <p>
     *     If <code>value==null</code>, the cookie will be removed.
     * </p>
     * <p>
     *     See {@link Cookie} for valid cookie name/value.
     * </p>
     *
     * @return this
     */
    // name/value will be validated
    public HttpRequestImpl cookie(String name, String value) throws IllegalArgumentException
    {
        // to avoid any inconsistency, the only storage of cookies is in headers["Cookie"]
        // performance isn't ideal. but not a big deal
        String hCookie = headers.get(Headers.Cookie);
        hCookie = Cookie.modCookieHeader(hCookie, name, value); // will sanity check name/value
        if(hCookie==null)
            headers.remove(Headers.Cookie);
        else
            headers.put(Headers.Cookie, hCookie); // no need to check hCookie, it's good

        return this;
    }

    /**
     * Set the cookies.
     * <p>
     *     All existing cookies will be removed.
     *     This method sets a new "Cookie" header based on provided cookies.
     * </p>
     * <p>
     *     See {@link Cookie} for valid cookie name/value.
     * </p>
     *
     * @return this
     */
    // we need this method because currently cookies() returns an immutable map
    public HttpRequestImpl cookies(Map<String,String> cookies)
    {
        String hCookie = Cookie.makeCookieHeader(cookies); // will sanity check name/value
        if(hCookie==null)
            headers.remove(Headers.Cookie);
        else
            headers.put(Headers.Cookie, hCookie); // no need to check hCookie, it's good

        return this;
    }

    /**
     * Set the entity.
     * @return this
     */
    public HttpRequestImpl entity(HttpEntity entity)
    {
        this.entity = entity;
        return this;
    }
    // no method to set entity metadata, like entityEtag(etag). maybe later

}
