package bayou.http;

import _bayou._tmp._HttpUtil;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.async.AutoAsync;
import bayou.bytes.ByteSource;
import bayou.mime.ContentType;
import bayou.mime.HeaderMap;

import java.lang.NullPointerException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A mutable implementation of HttpResponse.
 * <p>
 *     An <code>HttpResponseImpl</code> can be modified after construction, e.g. by {@link #header(String, String)}.
 *     These mutation methods return `this` for method chaining.
 * </p>
 * <p>
 *     Many static methods in {@link HttpResponse} return <code>HttpResponseImpl</code> objects,
 *     on which the caller can make further modifications.
 * </p>
 * <p>
 *     <code>HttpResponseImpl</code> is a subtype of <code>Async&lt;HttpResponse&gt;</code>;
 *     it can be returned from {@link HttpHandler#handle(HttpRequest)};
 *     it can be invoked with <code>Async</code> methods like <code>.then()</code>.
 * </p>
 */
public class HttpResponseImpl implements HttpResponse, AutoAsync<HttpResponse>
{
    // AutoAsync creates confusion by inheriting methods of Async into this class.
    // probably NOT too much, since HttpResponseImpl is mainly for writing to, not reading from.
    // may cause ambiguity where both HttpResponse and Async<HttpResponse> are acceptable; up-cast this to one of them.
    //
    // ideally we should do HttpResponseImpl<:AutoAsync<HttpResponseImpl>
    // but we try to be lazy on wildcards, so a lot of places we have Async<HttpResponse> instead of
    // Async<? extends HttpResponse>. so we do HttpResponseImpl<:AutoAsync<HttpResponse> instead.
    // probably not a big deal.
    //
    // we don't do HttpResponse extends AutoAsync<HttpResponse> because it is more confusing. only impl is auto async.


    HttpStatus status;
    HttpEntity entity;
    HeaderMap headers;
    ArrayList<Cookie> cookies;

    HttpResponseImpl(){} // internal use

    /**
     * Create an http response.
     */
    public HttpResponseImpl(HttpStatus status, HttpEntity entity)
    {
        // should we check status!=1xx, and entity!=null(if not 204/304 response)?
        // allow users to do that here?
        this.status = status;
        this.entity = entity;
        headers = new HeaderMap();
        cookies = new ArrayList<>();
    }

    /**
     * Copy an http response.
     * <p>
     *     This can be used for response transformation,
     *     for example to add headers/cookies.
     *     The origin response is treated as read-only and will not be modified.
     * </p>
     */
    public HttpResponseImpl(HttpResponse originResponse)
    {
        this(originResponse.status(), originResponse.entity());

        this.headers.putAll(originResponse.headers());
        this.cookies.addAll(originResponse.cookies());
    }

    /**
     * Get the status.
     * <p>
     *     See {@link #status(HttpStatus)} for changing the status.
     * </p>
     */
    @Override
    public HttpStatus status()
    {
        return status;
    }

    /**
     * Get the headers.
     * <p>
     *     The returned map is mutable; the response producer can manipulate it at will.
     *     See also {@link #header(String, String) header(name,value)}.
     * </p>
     */
    // creator of this response can mutate this map.
    // but it's better to call header(n,v) which validates header name/value.
    @Override
    public HeaderMap headers()
    {
        return headers;
    }

    /**
     * Get the cookies.
     * <p>
     *     The returned list is mutable; the response producer can manipulate it at will.
     *     See also {@link #cookie(String, String, Duration) cookie(name,value,maxAge)}
     *     and {@link #cookie(Cookie)}.
     * </p>
     *
     * @see CookieJar
     */
    @Override
    public ArrayList<Cookie> cookies()
    {
        return cookies;
    }

    /**
     * Get the entity.
     * <p>
     *     See {@link #entity(HttpEntity)} for changing the entity;
     *     see {@link #entityEtag(String)} etc. for modifying entity metadata.
     * </p>
     */
    @Override
    public HttpEntity entity()
    {
        return entity;
    }

    // creator of this request object can change any properties ------------------------

    /**
     * Set the status.
     *
     * @return this
     */
    public HttpResponseImpl status(HttpStatus status)
    {
        // we don't validate status? e.g. it cannot be 1xx.
        this.status = status;
        return this;
    }
    // no status(int) method. it's probably uncommon to change status anyway.

    /**
     * Set a header.
     * <p>
     *     If <code>value==null</code>, the header will be removed.
     * </p>
     *
     * @return this
     */
    // name/value will be validated
    public HttpResponseImpl header(String name, String value)
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
     * Add a cookie.
     * <p>
     *     This method is equivalent to
     *     <code>cookie(new Cookie(name, value, maxAge))</code>.
     *     Note that the cookie <code>domain=null</code>, <code>path="/"</code>.
     *     See {@link Cookie#Cookie(String, String, Duration)}
     *     and {@link #cookie(Cookie)}.
     * </p>
     * <p>
     *     Example usage: <code>resp.cookie("name","value",Cookie.SESSION)</code>
     * </p>
     * @return this
     */
    public HttpResponseImpl cookie(String name, String value, Duration maxAge)
    {
        return cookie(new Cookie(name, value, maxAge));
    }

    /**
     * Add a cookie.
     * <p>
     *     If this response already contains a cookie with the same
     *     <code>{domain,path,name}</code> identity, that cookie will be removed.
     * </p>
     * @return this
     */
    public HttpResponseImpl cookie(Cookie cookie)
    {
        _Util.require(cookie!=null, "cookie!=null");

        for(int i=0; i<cookies.size(); i++)
        {
            Cookie prev = cookies.get(i);
            if(prev.sameId(cookie))
            {
                cookies.set(i, cookie); // replace prev
                return this;
            }
        }

        cookies.add(cookie);
        return this;
    }

    // values for Set-Cookie headers.
    List<String> headersSetCookie()
    {
        ArrayList<String> list = new ArrayList<>(cookies.size());
        for(Cookie cookie : cookies)
            list.add(cookie.toSetCookieString());
        return list;
    }

    /**
     * Set the entity.
     * @return this
     */
    public HttpResponseImpl entity(HttpEntity entity)
    {
        // entity can be null
        this.entity = entity;
        // all prev metadata mods are lost. e.g. a prev entityEtag() call becomes useless.
        return this;
    }

    HttpEntityMod entityMod()
    {
        if(entity==null)
            throw new NullPointerException("entity==null");

        if(entity instanceof HttpEntityMod)
            return (HttpEntityMod)entity;

        HttpEntityMod mod = new HttpEntityMod(entity);
        entity=mod;
        return mod;
    }

    /**
     * Set the entity <code>"lastModified"</code> property.
     * @return this
     * @throws NullPointerException if entity is null
     */
    public HttpResponseImpl entityLastModified(Instant lastModified)
    {
        entityMod().lastModified=lastModified;
        return this;
    }

    /**
     * Set the entity <code>"expires"</code> property.
     * @return this
     * @throws NullPointerException if entity is null
     */
    public HttpResponseImpl entityExpires(Instant expires)
    {
        entityMod().expires = expires;
        return this;
    }

    /**
     * Set the entity <code>"etag"</code> property.
     * @return this
     * @throws NullPointerException if entity is null
     */
    public HttpResponseImpl entityEtag(String etag)
    {
        _HttpUtil.validateEtag(etag);
        entityMod().etag = etag;
        return this;
    }


    static Async<HttpResponse> toAsync(HttpResponse response)
    {
        if(response instanceof HttpResponseImpl) // common
            return (HttpResponseImpl)response;
        return Async.success(response);
    }

}
