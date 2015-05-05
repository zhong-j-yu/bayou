package bayou.file;

import _bayou._str._HexUtil;

import java.util.Arrays;

import static _bayou._str._CharDef.*;

// uri path (e.g. "/abc/xyz" ), or part of it (e.g. "bc/xy")
// currently used only by StaticHandler, whose main concern is to normalize a request uri as a key for lookup.
class UriPath
{
    // a uri is basically a series of octets. we want to represent it in a byte[],
    // and represent a "%HH" in uri as a byte 0xHH.
    // however, if %HH is mapped to a "reserved" char, it is semantically diff from the char itself.
    // e.g. "/x/y" and "/x%2Fy" are two distinct URIs.
    // so we need to escape "%2F" internally, as 0x00 0x2F.
    //
    //    0xhh   <->  0xhh        // path char, any
    //    "%hh"   ->  0xhh        // path char, unreserved (note: one way)
    //    "%HH"  <->  0x00 0xHH   // path char, reserved
    //    "%00"  <->  0x00 0x00   // not path char
    //    "%HH"  <->  0xHH        // not path char, not 0x00
    //
    // this class is only for uri path. for query, usually people presume it's x-www-form-urlencoded,
    // in which %2F and "/" are the considered same.

    public static final long[] reservedPathChars = set(":/@!$&'()*+,;="); // delims sans ?#[]

    final byte[] bytes;
    final int len;
    final int hashCode;

    UriPath(byte[] bytes, int len)
    {
        this.bytes = bytes; // no copy
        this.len = len;

        // calc hash code, like String's
        int h = 0;
        for(int i=0; i<len; i++)
            h = h * 31 + (bytes[i] & 0xFF);
        this.hashCode = h;
    }

    @Override
    public int hashCode()
    {
        return hashCode;
    }

    @Override
    public boolean equals(Object obj)
    {
        if(!(obj instanceof UriPath))
            return false;
        UriPath that = (UriPath)obj;
        if(this.len!=that.len)
            return false;
        for(int i=0; i<len; i++)
            if(this.bytes[i]!=that.bytes[i])
                return false;
        return true;
    }

    // don't need to call pack() if this is a tmp obj as a key for lookup
    public UriPath pack()
    {
        if(len==bytes.length)
            return this;
        byte[] bytes2 = Arrays.copyOf(this.bytes, len);
        return new UriPath(bytes2, bytes2.length);
    }

    public String string() // string representation of URI
    {
        StringBuilder sb = new StringBuilder(len); // often there's no encoding needed
        for(int i=0; i<len; i++)
        {
            byte b = bytes[i];
            if(b==0)
                percentEncode(bytes[++i], sb);
            else if(!check( b&0xFF, Rfc3986.pathChars))
                percentEncode(b, sb);
            else // path char
                sb.append((char)(b&0xFF));
        }
        return sb.toString();
    }
    static void percentEncode(byte b, StringBuilder sb)
    {
        sb.append('%');
        sb.append(_HexUtil.byte2hexHi(b));
        sb.append(_HexUtil.byte2hexLo(b));
    }


    // char in string:
    //     path char: as is. won't %-encode
    //    other char: to UTF-8 byte. will %-encode
    public UriPath append(String string)
    {
        byte[] bytes = Arrays.copyOf(this.bytes, this.len+string.length()*3); // big enough for worse case scenario
        int pos = this.len;
        for(int code, i = 0; i < string.length(); i += Character.charCount(code)) // iterate code points
        {
            code = string.codePointAt(i);

            if(code==0) // highly unlikely
                bytes[pos++]=bytes[pos++]=0; // escape
            else
                pos = toUtf8(code, bytes, pos);
        }
        return new UriPath(bytes, pos);
    }

    // note: if code>7F, all bytes will required %-encoding.
    static int toUtf8(int code, byte[] out, int pos)
    {
        assert code >= 0;
        if(code<=0x007F)
        {
            out[pos++] = (byte)code;
        }
        else if(code<=0x07FF)
        {
            out[pos++] = (byte)(code>> 6        | 0xC0 );
            out[pos++] = (byte)(code     & 0x3F | 0x80);
        }
        else if(code<=0xFFFF) // code can be a lone surrogate.
        {
            out[pos++] = (byte)(code>>12        | 0xE0);
            out[pos++] = (byte)(code>> 6 & 0x3F | 0x80);
            out[pos++] = (byte)(code     & 0x3F | 0x80);
        }
        else // beyond BMP
        {
            out[pos++] = (byte)(code>>18        | 0xF0);
            out[pos++] = (byte)(code>>12 & 0x3F | 0x80);
            out[pos++] = (byte)(code>> 6 & 0x3F | 0x80);
            out[pos++] = (byte)(code     & 0x3F | 0x80);
        }
        return pos;
    }



    // return null if malformed
    public static UriPath parseStrict(String string, int L)
    {
        byte[] bytes = new byte[L]; // big enough
        int len=0;
        for(int i=0; i<L; i++)
        {
            char c = string.charAt(i);
            if(c=='%') // must be followed by HH
            {
                int hh = _HexUtil.hh2int(string, L, i);
                if(hh==-1) return null;
                i+=2;
                if(hh==0 || check(hh, reservedPathChars))
                    bytes[len++] = 0; // escape
                bytes[len++] = (byte)hh;
            }
            else if( c<='\u00ff' && check((int)c, UriLoose.pathChars) )
                bytes[len++] = (byte)c;
            else
                return null; // invalid char
        }
        return new UriPath(bytes, len);
    }

    static UriPath parseStrict(String string)
    {
        return parseStrict(string, string.length());
    }
}
