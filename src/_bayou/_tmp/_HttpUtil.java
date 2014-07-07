package _bayou._tmp;

import bayou.mime.HeaderMap;

import java.time.Instant;
import java.util.function.Consumer;

import static bayou.mime.Headers.Accept_Encoding;
import static bayou.mime.Headers.Vary;

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

        long[] pathQueryChars = _CharDef.Uri.queryChars; // query chars also contain all path chars
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


    // header value can be a comma separated list
    // not very efficient. todo
    public static boolean containsToken(String headerValue, String token)
    {
        if(headerValue==null)
            return false;

        for(String s : headerValue.split(","))
        {
            s = s.trim();
            if(_StrUtil.equalIgnoreCase(s, token))
                return true;
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
        String hVary = headers.get(Vary);  // assume it has been checked to be valid header value
        if(hVary==null || hVary.isEmpty())
            headers.put(Vary, hv);
        else if(hVary.equals("*"))
            ; // still *
        else
            headers.put(Vary, hVary + ", " + hv);   // hVary was checked

    }

}
