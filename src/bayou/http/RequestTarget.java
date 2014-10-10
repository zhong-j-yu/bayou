package bayou.http;

import _bayou._tmp._HttpUtil;

class RequestTarget
{
    boolean isHttps;
    String host;
    String reqUri; // for HttpRequest.uri(), not absolute

    static RequestTarget of(boolean sslConn, String method, String reqTarget, String hvHost)
    {
        assert !method.equals("CONNECT"); // CONNECT is handled elsewhere

        RequestTarget rt = new RequestTarget();

        rt.isHttps = sslConn; // may change.
        rt.host = hvHost;     // may change. may be null or empty

        // request-target in 3 forms: origin, absolute, asterisk
        if (reqTarget.charAt(0) == '/') // origin-form, most common for HttpServer
        {
            if (!_HttpUtil.isOriginFormUri(reqTarget))
                return null;
            rt.reqUri = reqTarget;
        }
        else if (reqTarget.equals("*")) // asterisk-form, only for OPTIONS. rare.
        {
            if (!method.equals("OPTIONS"))
                return null;
            rt.reqUri = reqTarget;
        }
        else // absolute-form. common for HttpRequestImpl. we only support absolute http/https URI.
        {
            String uriLo = reqTarget.toLowerCase();
            if (uriLo.startsWith("http://"))
                rt.isHttps = false;
            else if (uriLo.startsWith("https://"))
                rt.isHttps = true;
            else
                return null;
            // request scheme was determined by connection type; now it's determined by request-target scheme.
            // if the two disagree, we use request-target scheme; client may lie about it, we accept it.

            int iHost = rt.isHttps ? 8 : 7;
            int iSlash = uriLo.indexOf('/', iHost);
            int iQuest = uriLo.indexOf('?', iHost);
            if (iSlash == -1 && iQuest == -1) //  http://abc.com
            {
                rt.host = uriLo.substring(iHost);
                rt.reqUri = method.equals("OPTIONS") ? "*" : "/";
            }
            else if (iSlash != -1 && (iQuest == -1 || iQuest > iSlash))  // http://abc.com/foo,  http://abc.com/foo?bar
            {
                rt.host = uriLo.substring(iHost, iSlash);
                rt.reqUri = reqTarget.substring(iSlash);
                if (!_HttpUtil.isOriginFormUri(rt.reqUri))
                    return null;
            }
            else //  http://abc.com?foo ,  http://abc.com?foo/bar
            {
                rt.host = uriLo.substring(iHost, iQuest);
                rt.reqUri = "/" + reqTarget.substring(iQuest);
                if (!_HttpUtil.isOriginFormUri(rt.reqUri))
                    return null;
            }
            // HTTP/1.1 client should send Host header identical to host from request-target.
            // we'll simply add/override Host with host from request-target.
        }
        return rt;
        // host is not validated here.
    }

}
