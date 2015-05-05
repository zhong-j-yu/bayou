package bayou.http;

import _bayou._http._HttpUtil;
import bayou.async.Async;
import bayou.mime.Headers;

import java.time.Duration;

class RedirectHandler implements HttpHandler
{
    HttpHandler h0;
    int autoRedirectMax;

    RedirectHandler(HttpHandler h0, int autoRedirectMax)
    {
        this.h0 = h0;
        this.autoRedirectMax = autoRedirectMax;
    }

    @Override
    public Async<HttpResponse> handle(HttpRequest request)
    {
        return send(request, autoRedirectMax);
    }



    Async<HttpResponse> send(HttpRequest request, int redirectCountDown)
    {
        Async<HttpResponse> asyncRes = h0.handle(request);
        if(redirectCountDown<=0)
            return asyncRes;
        else
            return asyncRes.then(response->onReceive(response, request, redirectCountDown));
    }
    Async<HttpResponse> onReceive(HttpResponse response, HttpRequest request, int redirectCountDown)
    {
        HttpRequestImpl redirectReq;
        try
        {
            redirectReq = checkRedirect(request, response);
        }
        catch (HttpResponseException e)
        {
            HttpClient.closeRes(response);
            return Async.failure(e);
        }

        if(redirectReq==null)
            return _HttpUtil.toAsync(response);

        HttpRequestImpl redirectReqF = redirectReq; // workaround IntelliJ bug
        return HttpClient.drain(response)
            .transform(r -> send(redirectReqF, redirectCountDown-1));
    }







    // if the response is redirect that we can follow, return the redirect request
    static HttpRequestImpl checkRedirect(HttpRequest request, HttpResponse response) throws HttpResponseException
    {
        // see http://tools.ietf.org/html/rfc7231#section-6.4
        //
        // if request is HEAD/GET, do HEAD/GET redirect, for all codes (300 301 302 303 307 308)
        // otherwise, if 303, do GET redirect, for all other request methods (usually POST)
        // otherwise, if POST and 301/302, do GET redirect. most clients do that.
        // otherwise, no auto redirect

        int code = response.statusCode();

        boolean isRedirectCode = code>=300 && code<=308 && (code<=303 || code>=307);
        // true: 300 301 302 303 307 308
        // note: 304/305/306 are definitely false.
        //       other 3xx could be considered true, but we don't know the detailed meaning.
        //       leave it to app to handle. such codes are not supposed to appear anyway.
        //
        // short circuits:
        // most common codes are probably 2xx, failing the 1st clause.
        // the next common are probably 4xx/5xx, failing the 2nd clause
        // 303 is the most common redirect codes, satisfying the 3rd clause.

        if(!isRedirectCode)
            return null;

        String location = response.headers().get(Headers.Location);
        if(location==null) // 300 response may not contain Location. others SHOULD.
            return null;
        // remove #fragment
        int iFrag = location.indexOf('#');
        if(iFrag!=-1)
            location = location.substring(0, iFrag);

        // copy properties of prev request.
        // todo may not be correct. e.g. request is POST with Expect:100-continue. that header should not persist.
        HttpRequestImpl redirectReq = new HttpRequestImpl(request);

        // must call method(...) before uri(...)
        redirectReq.method("GET"); // may become HEAD later.
        redirectReq.entity(null);

        try
        {
            redirectReq.uri(location); // throws
            // if location is absolute, this call may change isHttps/Host
        }
        catch (RuntimeException e)
        {
            throw new HttpResponseException("Bad Location: "+location, response);
        }

        String reqMethod = request.method();

        if(reqMethod.equals("GET"))
            return redirectReq;

        if(reqMethod.equals("HEAD"))
            return redirectReq.method("HEAD");

        if(code==303)
            return redirectReq;

        if(reqMethod.equals("POST") && (code==301 || code==302))
            return redirectReq;

        return null;
    }

}
