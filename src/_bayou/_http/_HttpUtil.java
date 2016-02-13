package _bayou._http;

import _bayou._str._CharDef;
import _bayou._str._HexUtil;
import _bayou._str._StrUtil;
import bayou.async.Async;
import bayou.http.HttpEntity;
import bayou.http.HttpRequest;
import bayou.http.HttpResponse;
import bayou.http.HttpResponseImpl;
import bayou.mime.ContentType;
import bayou.mime.HeaderMap;

import java.time.Instant;

import static bayou.mime.Headers.*;

public class _HttpUtil
{
    // whether name: value can be a syntactically correct http header
    public static void checkHeader(String name, String value) throws IllegalArgumentException
    {
        if(name==null)
            throw new IllegalArgumentException("header name cannot be null");
        if(name.isEmpty())
            throw new IllegalArgumentException("header name cannot be empty");
        if(!_CharDef.check(name, _CharDef.Http.tokenChars))
            throw new IllegalArgumentException("invalid header name: "+name);

        checkHeaderValue(name, value);
    }

    public static void checkHeaderValue(String name, String value) throws IllegalArgumentException
    {
        if(value==null)
            throw new IllegalArgumentException("invalid header value: "+name+": "+"null");
        // value can be empty.
        if(!_CharDef.check(value, _CharDef.Http.headerValueChars))  // especially, no CR/LF allowed
            throw new IllegalArgumentException("invalid header value: "+name+": "+value);
    }

    // whether uri is in the form of abs-path[?query]
    public static boolean isOriginFormUri(String uri)
    {
        if(uri.isEmpty())
            return false;
        if(uri.charAt(0)!='/')
            return false;

        long[] pathQueryChars = _CharDef.UriLoose.queryChars; // query chars also contain all path chars
        int L = uri.length();
        for(int i=1; i<L; i++)
        {
            char c = uri.charAt(i);
            if(c=='%')
            {
                int hh = _HexUtil.hh2int(uri, L, i);
                if(hh==-1) return false;
                i+=2;
            }
            else if (c > '\u00ff' || !_CharDef.check((int) c, pathQueryChars))
            {
                return false; // invalid char
            }
        }
        return true;
    }


    // whether url contains the exact host.
    // url scheme must be http or https.
    // for example, host is abc.com, url is http://abc.com or https://abc.com/xyz
    public static boolean matchHost(String host, String url)
    {
        // url examples:
        //     http://abc.com
        //     https://abc.com/xyz

        host = host.toLowerCase();
        url = url.toLowerCase();

        int x;
        if(url.startsWith("http://"))
            x = 7;
        else if(url.startsWith("https://"))
            x = 8;
        else
            return false;

        if(!url.startsWith(host, x))
            return false;

        x += host.length();  // <= url.length()
        if(x==url.length())  // e.g. http://abc.com
            return true;
        if(url.charAt(x)=='/')  // e.g. http://abc.com/xyz
            return true;
        if(url.charAt(x)=='?')  // e.g. http://abc.com?xyz  (not clear if it's a valid http uri. accept it anyway)
            return true;

        return false; // e.g. url=http://abc.com.xyz.com
    }


    // tokenList is a list of simple tokens separated by comma
    // impl is fast there's no comma (single token)
    public static boolean containsToken(String tokenList, String token)
    {
        if(tokenList==null)
            return false;

        int i=0;
        while(i<tokenList.length())
        {
            int j = tokenList.indexOf(',', i);
            if(j==-1)
                j = tokenList.length();
            String t = tokenList.substring(i,j).trim();
            if(_StrUtil.equalIgnoreCase(t, token))
                return true;
            i = j+1;
        }
        return false;
    }

    public static String defaultEtag(Instant lastMod, String contentEncoding)
    {
        if(lastMod==null)
            return null;
        StringBuilder sb = new StringBuilder();
        sb
            .append("t-")
            .append(Long.toHexString(lastMod.getEpochSecond()))
            .append("-")
            .append(Integer.toHexString(lastMod.getNano()));
        if(contentEncoding!=null)
        {
            sb.append('.');
            // usually contentEncoding is just one token. but it may contain multiple tokens, spaces, commas.
            // remove space; change comma to period. "ce1, ce2" => "ce1.ce2"
            for(int i=0; i<contentEncoding.length(); i++)
            {
                char c = contentEncoding.charAt(i);
                if(c==' ')
                    continue;
                if(c==',')
                    c='.';
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // null is allowed
    public static void validateEtag(String etag)
    {
        if( etag!=null && !_CharDef.check(etag, _CharDef.Http.etagChars))
            throw new IllegalArgumentException("invalid etag: "+etag);
    }

    public static void addVaryHeader(HeaderMap headers, String hv)
    {
        String hVary = headers.xGet(Vary);  // assume it has been checked to be valid header value
        if(hVary==null || hVary.isEmpty())
            headers.xPut(Vary, hv);
        else if(hVary.equals("*"))
            ; // still *
        else
            headers.xPut(Vary, hVary + ", " + hv);   // hVary was checked

    }


    public static void copyEntityHeaders(HttpEntity entity, HeaderMap headers)
    {
        // if a header already exist in `headers`, it may be overridden by value from entity.
        // note: must sanity check entity header values. may throw.

        ContentType contentType = entity.contentType();
        if(contentType!=null)
            headers.xPut(Content_Type, contentType.toString());  // content-type chars were checked

        String contentEncoding = entity.contentEncoding();
        if(contentEncoding!=null)
        {
            checkHeaderValue(Content_Encoding, contentEncoding);
            headers.xPut(Content_Encoding, contentEncoding);
        }

        // contentLength is not an entity header

        String etag = entity.etag();
        if(etag!=null)
        {
            // we follow rfc2616, treat entity-tag as quoted-string.
            // therefore, server produced etag may need escape, and client supplied etag may need un-escape.
            // we also advised in getEtag() to exclude " and \ to avoid the problem (see http bis)
            validateEtag(etag);

            String s = _StrUtil.doQuote(etag);
            if(entity.etagIsWeak())
                s = "W/"+s;
            headers.xPut(ETag, s);
        }

        Instant lastModified = entity.lastModified();
        if(lastModified!=null)
            headers.xPut(Last_Modified, _HttpDate.toHttpDate(lastModified));

        Instant expires = entity.expires();
        if(expires!=null)
        {
            // per rfc2616, it should not exceed 1 year from now.
            Instant oneYear = Instant.now().plusSeconds(365*24*3600); // will not overflow
            if(expires.isAfter(oneYear))
                expires = oneYear;
            // don't care if `Expires` is in the distant past, like year 0001.
            // worst case, client fails to parse or accept it.

            headers.xPut(Expires, _HttpDate.toHttpDate(expires));
        }
    }


    public static Async<HttpResponse> toAsync(HttpResponse response)
    {
        if(response instanceof HttpResponseImpl) // common
            return (HttpResponseImpl)response;
        return Async.success(response);
    }





    // set "close/keep-alive" in the Connection header.
    public static String modConnectionHeader(String header, boolean close)
    {
        if(header==null) // most likely request/response has no Connection header
            return close?"close":"keep-alive";

        // next likely: header is "close"
        if(close && _StrUtil.equalIgnoreCase(header, "close"))
            return header;

        // unlikely general case. header may already contain "close/keep-alive" tokens
        StringBuilder sb = new StringBuilder();
        boolean set = false;
        for(String token: header.split(","))
        {
            token = token.trim();
            if(_StrUtil.equalIgnoreCase(token, "close"))
            {
                if(!close)
                    token = "keep-alive"; // unlikely; caller won't modify `close` to `keep-alive`
                set = true;
            }
            else if(_StrUtil.equalIgnoreCase(token, "keep-alive"))
            {
                if(close)
                    token = "close";
                set = true;
            }
            if(sb.length()>0) sb.append(", ");
            sb.append(token);
        }
        if(!set)
        {
            if(sb.length()>0) sb.append(", ");
            sb.append(close?"close":"keep-alive");
        }
        return sb.toString();
    }


    // request-target, as it appears in the request-line
    public static String target(HttpRequest request, boolean forProxy)
    {
        if(request.method().equals("CONNECT"))
        {
            return request.host();                  // authority-form
        }
        else if(forProxy)
        {
            return request.absoluteUri();           // absolute-form
        }
        else
        {
            return request.uri();                   // origin-form or asterisk-form
        }
    }


}
