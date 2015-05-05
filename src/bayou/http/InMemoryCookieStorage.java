package bayou.http;

import _bayou._http._HttpHostPort;
import _bayou._tmp._PublicSuffix;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.util.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static _bayou._tmp._PublicSuffix.getPublicSuffix;

// http://tools.ietf.org/html/rfc6265
class InMemoryCookieStorage implements CookieStorage
{
    // domain->cookies
    ConcurrentHashMap<String,CookieSet> map = new ConcurrentHashMap<>();


    // set of cookies for the same `domain`.
    // for any cookie in the set,
    //   if cookie.domain=null, it only matches requests to the same domain. (host-only-flag=true)
    //   otherwise, cookie.domain=this.domain, and it matches requests to sub-domains as well.
    static class CookieSet
    {
        // String domain;

        final ArrayList<CK> list = new ArrayList<>(); // may contain nulls
        // we don't expect too many cookies for one domain
        // and we expect that most cookies will match the request in getCookies()
        // therefore traversing the whole list is optimal.

        CookieSet(String domain)
        {
            // this.domain = domain;
        }

        static class CK
        {
            Cookie cookie;
            String id;
            long expires;  // ms

            CK(Cookie cookie, String id, long now)
            {
                this.cookie = cookie;

                this.id = id;

                if(cookie.maxAge()==null)
                    expires = Long.MAX_VALUE;
                else // maxAge>0, in seconds
                    expires = now + cookie.maxAge().getSeconds()*1000;
            }

            boolean sameId(String id2)
            {
                return id.hashCode()==id2.hashCode()  // fast negative
                    && id.equals(id2);
            }
        }


        void setCookie(long now, Cookie cookie)
        {
            assert cookie.path()!=null;

            // add/replace/delete the cookie, based on id={name, domain, path}.
            // domain is omitted here (rfc6265: host-only-flag is not part of the id)
            // simple concat name+path works, because name contains no "/", and path starts with "/".
            String id = cookie.name()+cookie.path();

            //noinspection ResultOfMethodCallIgnored
            id.hashCode(); // pre-compute hash

            CK x = cookie.toDelete()? null : new CK(cookie, id, now);

            synchronized (list)
            {
                int i_hole=-1;
                for(int i=0; i<list.size(); i++) // find existing cookie, to delete/update
                {
                    CK ck = list.get(i);
                    if(ck==null)
                    {
                        i_hole = i;
                    }
                    else if(ck.sameId(id))
                    {
                        list.set(i, x);
                        return; // done
                    }
                }
                if(x==null)
                    return;
                if(i_hole!=-1)
                    list.set(i_hole, x);
                else
                    list.add(        x);
            }
        }

        // delete expired cookies in getCookies()
        // if a client no longer sends requests to a domain, the expired cookies linger. TBD.

        // sameDomain
        //    true - request domain is the same as this domain
        //   false - request domain is a parent of this domain
        void getCookies(long now, ArrayList<Cookie> results, boolean sameDomain, String reqPath, boolean reqSecure)
        {
            // do a quick copy
            ArrayList<Cookie> copy;
            synchronized (list)
            {
                int N = list.size();
                copy = new ArrayList<>(N);
                for(int i=0; i<N; i++)
                {
                    CK ck = list.get(i);
                    if(ck==null) continue;
                    if(ck.expires<now)
                        list.set(i, null);
                    else
                        copy.add(ck.cookie);
                }
            }

            for(Cookie cookie : copy)
                if(match(cookie, sameDomain, reqPath, reqSecure))
                    results.add(cookie);
        }
        static boolean match(Cookie cookie, boolean sameDomain, String reqPath, boolean reqSecure)
        {
            // if cookie.domain=null, exact domain match only
            // if cookie.secure=true, request must be secure

            return (sameDomain || cookie.domain()!=null)
                && (reqSecure  || !cookie.secure())
                && Cookie.pathMatches(reqPath, cookie.path())
                ;
        }
    } // class CookieSet





    /////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // [public suffix]
    // it's possible that a parent of a public suffix is not a public suffix,
    // e.g. compute.amazonAws.com is a public suffix, but amazonAws.com is not.
    // foo.compute.amazonAws.com cannot set a cookie for compute.amazonAws.com or higher domains.
    // w1.amazonAws.com can set a cookie for amazonAws.com, which affects w2.amazonAws.com.
    // however the cookie must not affect compute.amazonAws.com and its subdomains.


    @Override
    public Async<Void> setCookies(HttpRequest request, HttpResponse response)
    {
        long now = System.currentTimeMillis();

        _HttpHostPort hp = _HttpHostPort.parse(request.host());
        if(hp==null) return Async.VOID; // not likely
        String reqDomain = hp.hostString(); // could be IP literal

        // lazy
        String defaultPath=null;
        int reqDomainPublicSuffix_length=-1;

        for(Cookie cookie : response.cookies())
        {
            String cookieDomain = cookie.domain();
            // cookie.domain=null => the cookie is only sent back to the origin server, i.e. exact domain match
            if(cookieDomain!=null)
            {
                // if cookie.domain is illegal,
                // it can be malicious, or an innocent mistake by the server app.
                // treat it as null domain. it's harmless to sent it back to the origin server.

                if(!Cookie.domainMatches(reqDomain, cookieDomain))
                {
                    // (reqDomain can be IP literal here, to which domainMatches() always return false)
                    cookie = new Cookie(cookie, cookieDomain=null, cookie.path());
                }
                else // cookieDomain is same as, or a parent of, reqDomain. (reqDomain won't be IP literal here)
                {
                    if(reqDomainPublicSuffix_length==-1)
                        reqDomainPublicSuffix_length=getPublicSuffix(reqDomain, true).length();

                    if(cookieDomain.length() <= reqDomainPublicSuffix_length)
                    {
                        cookie = new Cookie(cookie, cookieDomain=null, cookie.path());
                    }
                    // don't test whether cookie.domain is a public suffix.
                    // it's possible that it is not, yet it's a parent of a public suffix. for example
                    //     req.domain="foo.compute.amazonAws.com", cookie.domain="amazonAws.com"
                    // this case is illegal because "compute.amazonAws.com" is a public suffix.
                }
            }

            if(cookie.path()==null) // set it to default path
            {
                if(defaultPath==null)
                    defaultPath=Cookie.defaultPath(request.uriPath());
                cookie = new Cookie(cookie, cookieDomain, defaultPath);
                // defaultPath could contain ";" which is illegal in Set-Cookie header.
                // but it's ok for client's purpose.
            }
            // cookie.path can be any path, with no regard to req.path

            map.computeIfAbsent(
                cookieDomain!=null ? cookieDomain : reqDomain,
                CookieSet::new
            ).setCookie(now, cookie);
        }

        return Async.VOID;
    }

    @Override
    public Async<List<Cookie>> getCookies(HttpRequest request)
    {
        long now = System.currentTimeMillis();

        ArrayList<Cookie> results = new ArrayList<>();

        _HttpHostPort hp = _HttpHostPort.parse(request.host());
        if(hp==null) return Async.success(results); // unlikely
        String reqDomain = hp.hostString();
        String reqPath = request.uriPath();
        boolean reqSecure = request.isHttps();

        // exact domain match
        CookieSet set = map.get(reqDomain);
        if(set!=null)
            set.getCookies(now, results, true, reqPath, reqSecure);

        if(hp.ip!=null) // request host is IP; exact domain match only.
            return Async.success(results);

        // match parent domains below the public suffix.
        int reqDomainPublicSuffix_length= getPublicSuffix(reqDomain, true).length();

        while(true)
        {
            int iDot = reqDomain.indexOf('.');
            if(iDot==-1)
                break;
            if(reqDomain.length()-iDot-1 <= reqDomainPublicSuffix_length )
                break;

            reqDomain = reqDomain.substring(iDot+1); // parent domain, below public suffix

            set = map.get(reqDomain);
            if(set!=null)
                set.getCookies(now, results, false, reqPath, reqSecure);  // exclude cookies that cookie.domain=null
        }

        return Async.success(results);
    }



}
