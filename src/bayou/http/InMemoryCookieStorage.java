package bayou.http;

import _bayou._http._HttpHostPort;
import _bayou._tmp._Dns;
import bayou.async.Async;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static _bayou._tmp._PublicSuffix.isPublicSuffix;

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
        //   false - request domain is a child  of this domain
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

    @Override
    public Async<Void> setCookies(HttpRequest request, HttpResponse response)
    {
        long now = System.currentTimeMillis();

        _HttpHostPort hp = _HttpHostPort.parse(request.host());
        if(hp==null) return Async.VOID; // not likely
        String reqDomain = hp.hostString(); // could be IP literal

        for(Cookie cookie : response.cookies())
        {
            String cookieDomain = cookie.domain();
            if(cookieDomain!=null)
            {
                if(!Cookie.covers(cookieDomain, reqDomain))
                    continue;   // ignore the cookie entirely.
            }
            // cookie.domain=null => the cookie is only applicable to reqDomain

            if(cookie.path()==null) // set it to default path
            {
                String defaultPath=Cookie.defaultPath(request.uriPath());
                cookie = new Cookie(cookie, defaultPath);
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

        // parent domains that cover reqDomain. see Cookie.covers()
        while(true)
        {
            reqDomain = _Dns.parent(reqDomain);
            if(reqDomain==null)
                break;
            if(isPublicSuffix(reqDomain))
                break;

            set = map.get(reqDomain);
            if(set!=null)
                set.getCookies(now, results, false, reqPath, reqSecure);  // exclude cookies that cookie.domain=null
        }

        return Async.success(results);
    }



}
