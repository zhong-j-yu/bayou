package bayou.http;

import bayou.async.Async;
import bayou.mime.Headers;
import bayou.util.Result;

import java.util.List;


// issue: 1xx responses won't reach this handler; their cookies will not be handled.

class CookieHandler implements HttpHandler
{
    HttpHandler h0;
    CookieStorage store;

    CookieHandler(HttpHandler h0, CookieStorage store)
    {
        this.h0 = h0;
        this.store = store;
    }

    @Override
    public Async<HttpResponse> handle(HttpRequest request)
    {
        Async<String> cookieStringA = getCookieString(store, request);
        Result<String> cookieStringR = cookieStringA.pollResult();
        if(cookieStringR!=null) // common
            return handle(request, cookieStringR);
        return cookieStringA.transform(r->handle(request, r));
    }

    Async<HttpResponse> handle(HttpRequest request, Result<String> cookieStringR)
    {
        String cookieString = cookieStringR.getValue();
        if(cookieString==null) // failure. unlikely.
            return cookieStringR.covary(); // erasure

        if(!cookieString.isEmpty())  // otherwise no cookie from store
        {
            String old = request.header(Headers.Cookie);
            if(old!=null) // app already set some cookies. keep them.
                cookieString = old + ";" +cookieString;
            request = new HttpRequestImpl(request).header(Headers.Cookie, cookieString);
        }

        HttpRequest requestF = request;
        return h0
            .handle(requestF)
            .transform(r->onResponse(r, requestF))
            ;
    }
    Async<HttpResponse> onResponse(Result<HttpResponse> responseR, HttpRequest request)
    {
        HttpResponse response = responseR.getValue();
        if(response==null) // failure
            return responseR;

        Async<Void> v = store.setCookies(request, response);
        if(v.isCompleted()) // common
            return responseR;
        // otherwise wait for its completion
        return v.transform(r->responseR); // don't care if `r` is failure.
    }


    //////////////////////////////////////////////////////////////////////////////////////////////////////
    // result is "" if there's no cookies
    static Async<String> getCookieString(CookieStorage store, HttpRequest request)
    {
        Async<List<Cookie>> listA = store.getCookies(request);
        Result<List<Cookie>> listR = listA.pollResult();
        if(listR!=null) // common
            return toCookieString2(listR);
        return listA.transform(CookieHandler::toCookieString2);
    }
    static Async<String> toCookieString2(Result<List<Cookie>> listR)
    {
        List<Cookie> list = listR.getValue();
        if(list==null) // failure
            return listR.covary(); // erasure
        else
            return Result.success(toCookieString3(list));
    }
    static String toCookieString3(List<Cookie> list)
    {
        if(list.isEmpty())
            return "";

        StringBuilder sb = new StringBuilder();
        for(int i=0;i<list.size();i++)
        {
            if(i>0) sb.append(';');
            Cookie c = list.get(i);
            sb.append(c.name()).append('=').append(c.value());
        }
        return sb.toString();
    }


}
