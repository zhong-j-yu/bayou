package bayou.http;

import _bayou._tmp._Ip;
import bayou.form.FormData;
import bayou.mime.HeaderMap;
import bayou.mime.Headers;

import java.net.InetAddress;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

// app cannot temper with this req, which is important.
//    we need to examine the original request info later during response mod.
// tho this class is similar to HttpRequestImpl, if we do subclass, app could cast then modify.
class ImplHttpRequest implements HttpRequest
{
    InetAddress ip;
    boolean isHttps;
    List<X509Certificate> certs = Collections.emptyList();
    String method;
    String uri;
    final HeaderMap headers = new HeaderMap();
    ImplHttpEntity entity;
    boolean sealed; // if sealed, request is good

    ImplHttpRequest()
    {
    }

    void seal()
    {
        headers.freeze();
        sealed = true;
    }




    @Override
    public InetAddress ip()
    {
        return ip;
    }

    @Override
    public boolean isHttps()
    {
        return isHttps;
    }

    @Override
    public List<X509Certificate> certs()
    {
        return certs;
    }

    @Override
    public String method()
    {
        return method;
    }

    @Override
    public String uri()
    {
        return uri;
    }

    volatile FormData uriFormData_volatile; // lazy; derived from `uri`
    // FormData is not immutable, so we must be careful with safe publication
    FormData uriFormData() throws Exception
    {
        FormData f = uriFormData_volatile;
        if(f==null)
            uriFormData_volatile = f = FormData.parse(uri);
        return f;
    }

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


    @Override
    public HeaderMap headers()
    {
        return headers; // app cannot modify it - freeze() was called
    }

    Map<String,String> cookieMap; // lazy, immutable, derived from headers[Cookie]
    @Override
    public Map<String,String> cookies()
    {
        Map<String,String> map = cookieMap;
        if(map==null)
        {
            String hCookie = headers.xGet(Headers.Cookie);
            map = Cookie.parseCookieHeader(hCookie); // no throw. ok if hCookie==null
            map = Collections.unmodifiableMap(map);  // immutable with final ref; publication is safe
            cookieMap = map;
        }
        return map;
    }


    public HttpEntity entity()
    {
        return entity;
    }


    byte httpMinorVersion=-1;   // 0 or 1. -1 if parse error.

    @Override
    public String httpVersion()
    {
        return (httpMinorVersion==0) ? "1.0" : "1.1";
    }




    // fix ip/isHttps based on X-Forwarded- headers
    void fixForward(int xForwardLevel)
    {
        assert xForwardLevel>=0;
        if(xForwardLevel==0)
            return;

        // if xForwardLevel>0, and X-Forwarded-For is not as expected, our load balancer is screwed up.
        // no need to check X-Forwarded-Proto in that case either, which is probably not trustworthy too.
        // however we don't raise error or make request as bad; just ignore X-Forwarded- headers.

        String xff = headers.xGet(Headers.X_Forwarded_For);
        if(xff==null)
            return;

        // xff contains N IPs, separated by comma. N>=1. each IP can be v4 or v6.
        // number the rightmost one the 1st IP, the leftmost one the N-th.
        // pick the xForwardLevel-th IP. if xForwardLevel>N, pick the N-th.
        xff = pick(xff, xForwardLevel);
        // it must be a valid IPv4 or IPv6 address. domain names are not allowed.
        byte[] ipBytes = _Ip.parseIp(xff, 0, xff.length());
        if(ipBytes==null) return;
        ip = _Ip.toInetAddress(ipBytes);

        String xfp = headers.xGet(Headers.X_Forwarded_Proto);
        if(xfp==null)
            return;

        // it's unclear what xfp contains. probably just one protocol, e.g. "https".
        // let's assume here that it can be a comma separated list as well, e.g. "http, https, http"
        // our pick() works even if N=1 and xForwardLevel>1.
        xfp = pick(xfp, xForwardLevel);
        if(xfp.equalsIgnoreCase("https"))
            isHttps=true;
        else if(xfp.equalsIgnoreCase("http"))
            isHttps=false;
        else
            ; // ignore
    }

    static String pick(String str, int level)
    {
        int end=str.length();
        while(true)
        {
            int comma = str.lastIndexOf(',', end-1);
            if(comma==-1 || level==1)
                return str.substring(comma+1, end).trim();
            --level;
            end = comma;
        }
    }




    // ---------------------------------------------------------------------------------------

    long timeReceived;

    // "HTTP/1.1 100 Continue"
    // 0: not expected, don't send.
    // 1: expected, not sent yet
    // 2: tried to send, but failed
    // 3: sent successfully.
    byte state100;


}
