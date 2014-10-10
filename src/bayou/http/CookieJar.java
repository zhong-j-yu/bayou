package bayou.http;

import _bayou._tmp._Dns;
import bayou.async.FiberLocal;
import bayou.mime.Headers;

import java.time.Duration;
import java.util.*;

/**
 * For managing cookies in a request-response cycle.
 * <p>
 *     A server application can read {@link HttpRequest#cookies()}
 *     and generate {@link HttpResponse#cookies()}.
 *     However, it might be inconvenient to manage cookies that way,
 *     particularly where request/response is not available to the code.
 * </p>
 * <p>
 *     <code>CookieJar</code> models client cookies as a map-like structure that
 *     the application can read from and write to. For example
 * </p>
 * <pre>
 *     CookieJar cookieJar = CookieJar.current();
 *     String foo = cookieJar.get("foo");
 *     if(foo==null)
 *         cookieJar.put("foo", foo="bar", Cookie.SESSION);
 *
 *     String flashMsg = CookieJar.current().remove("flashMsg");
 * </pre>
 * <p>
 *     A CookieJar is a fiber-local concept, for a single request-response cycle.
 * </p>
 * <p>
 *     A CookeJar is initialized with cookies from the fiber-local request;
 *     the cookies in the jar can be changed by <code>put/remove/clear</code> methods.
 *     At the end of the request-response cycle,
 *     HttpServer will collect all changes and generate proper response cookies.
 * </p>
 * <h4>{domain,path}</h4>
 * <p>
 *     Each CookieJar is intended for a specific cookie {domain,path};
 *     it manages cookies only of that {domain,path}. See {@link Cookie}.
 * </p>
 * <p>
 *     Method {@link #current() CookieJar.current()} uses default {domain=null, path="/"}.
 *     If the application uses cookies of different domain/path,
 *     call {@link #current(String, String) CookieJar.current(domain,path)} instead.
 * </p>
 * <p>
 *     Unfortunately, cookie domain/path are not available in request Cookie header,
 *     therefore a CookieJar cannot know which {domain,path} a request cookie belongs to.
 *     It is best if the application uses the same {domain,path} for all cookies.
 *     Otherwise, never use the same cookie name in different {domain,path},
 *     or CookieJar may not function properly.
 * </p>
 *
 * @see Cookie
 */

// a cookie jar gets initial cookies from the request,
// some of the request cookies are not in the same domain/path as the jar's domain/path,
// if app do operate on cookies of different domain/path.
// there's no way for the jar to tell the domain/path of request cookies.
// not a big problem for get()/put()/remove(), since app operates on the cookie by name in this jar,
// the cookie must be in the same domain/path.
// for toMap()/clear(), cookies not in the same domain/path got involved too, but nothing too bad.

public class CookieJar
{
    // fiber local
    static class LocalJars
    {
        static final FiberLocal<LocalJars> fiberLocal = new FiberLocal<>();

        CookieJar mainJar; // for domain=null, path="/". most often used

        HashMap<String,CookieJar> otherJars; // for jars with different domain/path. not used often.
    }

    /**
     * Get the default fiber-local CookieJar.
     * <p>
     *     This method is equivalent to <code>current(null, "/")</code>,
     *     i.e. the jar has {domain=null, path="/"}.
     * </p>
     * @see #current(String, String) current(domain,path)
     */
    public static CookieJar current()
    {
        return current(/*cookieDomain*/ null, /*cookiePath*/ "/");
    }

    /**
     * Get the fiber-local CookieJar for the cookie {domain,path}.
     * <p>
     *     The `cookiePath` must not be null; we strongly recommend to always use "/" for cookie path.
     * </p>
     * <p>
     *     The jar for the current request-response cycle;
     *     it is initialized with cookies from the {@link HttpRequest#current() fiber-local request}.
     * </p>
     * <p>
     *     The jar is persistent in the fiber, until {@link #clearAll()} is called.
     * </p>
     */
    public static CookieJar current(String cookieDomain, String cookiePath)
    {
        // note: depends on the current request.
        // may be a problem for transformers/filters.

        HttpRequest request = HttpRequest.current();
        if(request==null)
            throw new IllegalStateException("HttpRequest.current()==null");

        LocalJars jars = LocalJars.fiberLocal.get();
        if(jars==null)
            LocalJars.fiberLocal.set(jars=new LocalJars());
        // maybe: ensure jars was created for the same request. for now, leave it to app discretion.

        // example key:
        //     /
        //     example.com/
        //     /path
        //     example.com/path
        String key = cookiePath;
        if(cookieDomain!=null)
            key=cookieDomain.toLowerCase()+key;
        // otherwise don't prefix "null", since string "null" is a legit domain

        // if cookieDomain/Path is invalid, new CookieJar() will fail
        // caller can craft invalid cookieDomain/Path that lead to valid key here. but what do we care.

        CookieJar jar;
        if(key.equals("/")) // most often
        {
            jar = jars.mainJar;
            if(jar==null)
                jars.mainJar = jar = new CookieJar(request, cookieDomain, cookiePath);
        }
        else
        {
            if(jars.otherJars==null)
                jars.otherJars = new HashMap<>();
            jar = jars.otherJars.get(key);
            if(jar==null)
                jars.otherJars.put(key, jar=new CookieJar(request, cookieDomain, cookiePath));
        }
        return jar;
    }

    /**
     * Get all changes in all fiber-local CookieJars.
     *
     * @see CookieJar#getChanges() getChanges() - get changes in a single jar
     */
    public static Collection<Cookie> getAllChanges()
    {
        return getAllChanges2();
    }

    // for internal code requiring a new ArrayList.
    static ArrayList<Cookie> getAllChanges2()
    {
        ArrayList<Cookie> all = new ArrayList<>();
        LocalJars jars = LocalJars.fiberLocal.get();
        if(jars==null)
            return all;
        if(jars.mainJar !=null)
            all.addAll(jars.mainJar.responseCookies.values());
        if(jars.otherJars!=null)
            for(CookieJar jar : jars.otherJars.values())
                all.addAll(jar.responseCookies.values());
        return all;
    }

    /**
     * Clear all fiber-local CookieJars.
     * <p>
     *     All the jars will be removed from the fiber. This is usually called by HttpServer
     *     at the end of a request-response cycle.
     * </p>
     */
    public static void clearAll()
    {
        LocalJars.fiberLocal.set(null);
    }





    // currently we don't expect many cookies or many cookie operations. not well optimized.

    final String cookieDomain; // null or domain (no IP).
    // if domain, lower cased. either same as requestHost, or sub domain of requestHost

    final String cookiePath; // not null. starts with "/"

    final Map<String,String> requestCookies;
    // parsed from request Cookie header. one cookie name is mapped to only one value (e.g. n=v1;n=v2 becomes n=v2)
    // we know that requestHost must domain-matches requestCookie.domain (in client's model, domain could be an IP)
    // and requestPath must path-matches requestCookie.path,
    // but we don't know the exact domain/path of requestCookie.

    final HashMap<String,Cookie> responseCookies;
    // to be set in response Set-Cookie headers.
    // all have the same domain and path ( = cookieDomain and cookiePath)

    // for a responseCookie, based on whether the name is in requestCookies, and whether it is expired
    //
    // Y N : cookie updated (value, maxAge etc)
    // Y Y : cookie deleted
    // N N : cookie created
    // N Y : spurious delete



    // may throw if request has a bad Host header. app usually don't watch for that,
    // the exception will propagate up and appear to be an internal error. that's fine.
    // may become public later: user can use this in fiber-less environment
    CookieJar(HttpRequest request, String cookieDomain, String cookiePath) throws IllegalArgumentException
    {
        // Host header is non-null and non-empty
        this(request.host(), request.header(Headers.Cookie), cookieDomain, cookiePath);
    }


    CookieJar(String requestHost, String hvCookie, String cookieDomain, String cookiePath)
    {
        if(cookieDomain!=null)
        {
            cookieDomain = cookieDomain.toLowerCase();

            if(!_Dns.isValidDomain(cookieDomain)) // cannot be IP
                throw new IllegalArgumentException("invalid cookieDomain: "+cookieDomain);
            // domain must not be a public suffix, but we aren't checking that here

            // match requestHost and cookieDomain
            // remove port from requestHost. requestHost was already validated.
            if(requestHost.charAt(0)=='[') // ipv6. ip cannot match domain.
                throw new IllegalArgumentException("requestHost does not match cookieDomain");
            // ipv4/domain [:port]
            int iColon = requestHost.lastIndexOf(':');
            if(iColon!=-1)
                requestHost = requestHost.substring(0, iColon);

            if(!domainMatches(requestHost, cookieDomain))
                throw new IllegalArgumentException("requestHost does not match cookieDomain");
        }
        this.cookieDomain = cookieDomain;

        if(cookiePath==null) // we could support null, meaning default, but not worth it. caller can supply default-path
            throw new IllegalArgumentException("cookiePath cannot be null");
        if(!Cookie.checkPath2(cookiePath))
            throw new IllegalArgumentException("invalid cookiePath: "+cookiePath);
        this.cookiePath = cookiePath;

        this.requestCookies = Cookie.parseCookieHeader(hvCookie);

        this.responseCookies = new HashMap<>();
    }

    /**
     * Get all cookies in this jar as a Map.
     * <p>
     *     The returned Map is a read-only snapshot.
     * </p>
     */
    // note: a request cookie may be in a different domain+path. ideally this method should not
    //   return that cookie. but there's no way to tell the request cookie's domain+path.
    //   so the method may return the name-value of a cookie that's in a different domain+path.
    public Map<String,String> toMap()
    {
        HashMap<String,String> map = new HashMap<>(requestCookies);
        for(Cookie cookie : responseCookies.values())
        {
            if(cookie.expired())
                map.remove(cookie.name());
            else // [note]
                map.put(cookie.name(), cookie.value());
        }
        return map;
        // [note] no need to check cookie.httpOnly.
        // cookie.secure not checked - if a response cookie has secure=true, presumably this connection must be https.
    }

    /**
     * Get the value of a cookie.
     */
    // note: a request cookie with the same name may be in a different domain+path,
    //   ideally this method shouldn't return that cookie. but there's no way to tell the request cookie's domain+path.
    //   not a big problem, app should only query a cookie name in a jar with the correct cookieDomain/Path
    public String get(String cookieName)
    {
        Cookie cookie = responseCookies.get(cookieName);
        if(cookie!=null)
            return cookie.expired() ? null : cookie.value();

        return requestCookies.get(cookieName);
    }

    /**
     * Add a cookie to this jar.
     * <p>
     *     The cookie will have the same {domain,path} as this jar's,
     *     and <code>secure=httpOnly=false</code>.
     * </p>
     *
     * @return the previous value of the cookie, or null if none.
     * @see #put(Cookie)
     */
    public String put(String cookieName, String cookieValue, Duration maxAge)
    {
        return put(new Cookie(cookieName, cookieValue, maxAge,
            this.cookieDomain, this.cookiePath, false, false));
    }

    // do we want to publicize this method? to be analogous to Map.put().
    // probably not very useful. it's better to explicitly specific maxAge=Cookie.SESSION
    String put(String cookieName, String cookieValue)
    {
        return put(cookieName, cookieValue, Cookie.SESSION);
    }

    /**
     * Add a cookie to this jar.
     * <p>
     *     The cookie must have the same {domain,path} as this jar's.
     * </p>
     *
     * @return the previous value of the cookie, or null if none.
     */
    // return prev value of the same cookie
    public String put(Cookie cookie)
    {
        // enforce same domain/path
        if(!Objects.equals(cookie.domain(), this.cookieDomain))  // both domains are in lower case
            throw new IllegalArgumentException("cookie's domain must be "+cookieDomain);
        if(!Objects.equals(cookie.path(), this.cookiePath))
            throw new IllegalArgumentException("cookie's path must be "+cookiePath);

        // we may put a spurious delete - cookie expired, yet name not in requestCookies
        // doesn't matter. keep it if it's what the caller wants.

        // if cookie name exists in request, we'll add Set-Cookie in response anyway,
        // because we are not sure if the client cookie is same as this cookie in every aspect.
        // app can check get() before put() to avoid unnecessary Set-Cookie, if it knows that's ok.

        Cookie prev = responseCookies.put(cookie.name(), cookie);
        if(prev!=null)
            return prev.expired() ? null : prev.value();
        else
            return requestCookies.get(cookie.name());
        // if there's a request cookie with the same name, it should be in the same domain/path
    }

    void deleteReqCookie(String name)
    {
        responseCookies.put(name,
            new Cookie(name, "DELETE", Cookie.DELETE, cookieDomain, cookiePath, false, false));
    }

    /**
     * Remove a cookie from this jar.
     *
     * @return the previous value of the cookie, or null if none.
     */
    public String remove(String cookieName)
    {
        Cookie prev = responseCookies.remove(cookieName);
        String reqValue = requestCookies.get(cookieName);
        if(reqValue!=null)
            deleteReqCookie(cookieName); // the request cookie should be in the same cookieDomain/Path

        if(prev!=null)
            return prev.expired() ? null : prev.value();
        else
            return reqValue;
    }

    /**
     * Remove all cookies from this jar.
     */
    public void clear()
    {
        responseCookies.clear();

        for(String name : requestCookies.keySet())
            deleteReqCookie(name);
        // if a request cookie is in a diff domain/path, deleteReqCookie(name) won't work.
        // good - clear() only clear cookies in this cookieDomain/Path.
        //  bad - after clear(), toMap() will report all cookies removed, which is not exactly true.
    }

    /**
     * Get all changes to this jar.
     * <p>
     *     Return a list of response cookies that reflect changes done by
     *     previous <code>put/remove/clear</code> calls.
     * </p>
     */
    public Collection<Cookie> getChanges()
    {
        Collection<Cookie> changes = responseCookies.values(); // this is a live view
        return new ArrayList<>(changes); // return a snap shot instead
    }

    // undo all changes by prev put()/remove()/clear()
    // not public for now. do apps need this method?
    void undoChanges()
    {
        responseCookies.clear();
    }












    // whether a request host matches a cookie domain. e.g. "x.y.com" domain-matches "y.com"
    // http://tools.ietf.org/html/rfc6265#section-5.1.3
    // remember to remove port from requestHost.
    // cookieDomain must have been validated
    // both args must be in lower case
    static boolean domainMatches(String requestHost, String cookieDomain)
    {
        if(!requestHost.endsWith(cookieDomain))
            return false;
        int r = requestHost.length()-cookieDomain.length();
        if(r>0 && requestHost.charAt(r-1)!='.')
            return false;
        // requestHost must not an IP address. it's must be true here since cookieDomain is a valid domain
        return true;
    }





// not used

//    // http://tools.ietf.org/html/rfc6265#section-5.1.4
//    static boolean pathMatches(String requestPath, String cookiePath)
//    {
//        if(!requestPath.startsWith(cookiePath)) // case sensitive
//            return false;
//        // note: false==pathMatches("/abc", "/abc/")
//
//        if(requestPath.length()==cookiePath.length())
//            return true;
//
//        if(cookiePath.endsWith("/"))
//            return true;
//
//        if(requestPath.charAt(cookiePath.length())=='/')
//            return true;
//
//        return false;
//    }
//
//    // http://tools.ietf.org/html/rfc6265#section-5.1.4
//    static String defaultCookiePath(String requestPath)
//    {
//        int iRightMostSlash = requestPath.lastIndexOf('/'); // won't be -1
//        if(iRightMostSlash==0)
//            return "/";
//        else
//            return requestPath.substring(0, iRightMostSlash);
//    }

}
