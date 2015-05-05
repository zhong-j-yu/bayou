package _bayou._tmp;

import _bayou._str._HexUtil;

import java.net.InetAddress;
import java.net.UnknownHostException;


public class _Ip
{
    static final _ControlException parseError = new _ControlException("parse error");

    public static InetAddress toInetAddress(String ip)
    {
        byte[] bytes = parseIp(ip, 0, ip.length());
        if(bytes==null) return null;
        return toInetAddress(bytes);
    }

    public static InetAddress toInetAddress(byte[] bytes)
    {
        try
        {
            return InetAddress.getByAddress(bytes);
        }
        catch (UnknownHostException e) // caller ensures that bytes are good
        {
            throw new IllegalArgumentException(e);
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////

    public static byte[] parseIp(CharSequence chars, int start, int end)
    {
        // colon => ipv6
        for(int i=start; i<end; i++)
            if(chars.charAt(i)==':')
                return parseIpv6(chars, start, end);

        return parseIpv4(chars, start, end);
    }

    public static byte[] parseIpv4(CharSequence chars, int start, int end)
    {
        try
        {
            return parseIpv4E(chars, start, end);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    public static byte[] parseIpv4E(CharSequence chars, int start, int end) throws Exception
    {
        // e.g. 192.168.68.1
        byte[] bytes = new byte[4];
        int n = v4Parse(chars, start, end, bytes, 0);
        if(n!=4) throw parseError;
        return bytes;
    }

    public static byte[] parseIpv6(CharSequence chars, int start, int end)
    {
        try
        {
            return parseIpv6E(chars, start, end);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    // http://tools.ietf.org/html/rfc4291#section-2.2
    public static byte[] parseIpv6E(CharSequence chars, int start, int end) throws Exception
    {
        // to simplify parser, scan once to get the general structure
        // - is there a double-colon? is it ended with ipv4?
        int iDoubleColon = -2;
        boolean hasIpv4 = false;
        int iLastColon = -2;
        int nColon = 0;
        for(int i=start; i<end; i++)
        {
            char ch = chars.charAt(i);
            if(ch==':')
            {
                if(i-1==iLastColon)
                    iDoubleColon = iLastColon;
                iLastColon = i;
                nColon++;
            }
            else if(ch=='.')
            {
                hasIpv4 = true;
                break;
            }
        }
        if(iLastColon<0) throw parseError;

        byte[] bytes = new byte[16];
        int n=0;

        // parse sans ipv4
        int toX = end;
        int NX = 16;
        if(hasIpv4) // trailing ipv4 is preceded by :: or x:
        {
            toX = (iLastColon == iDoubleColon + 1) ? iDoubleColon + 2 : iLastColon;
            NX = 12;
        }

        if(iDoubleColon<0)
        {
            n = v6Parse(chars, start, toX, bytes, n);
            if(n!=NX) throw parseError;
        }
        else if(iDoubleColon+2==toX)  // double-colon at the end
        {
            n = v6Parse(chars, start, iDoubleColon, bytes, n); // before double-colon
            int nz = NX-n; // num of bytes for double-colon
            if(nz<2) throw parseError;
            n = NX;
        }
        else // double-colon, followed by another segment
        {
            n = v6Parse(chars, start, iDoubleColon, bytes, n); // before double-colon
            int nc = nColon - (n==0?0:n/2-1) - 2 - (hasIpv4?1:0); // num of colons in the following segment
            int nz = NX-n-2*(nc+1);  // num of bytes for double-colon
            if(nz<2) throw parseError;
            n = v6Parse(chars, iDoubleColon+2, toX, bytes, n+nz);
            assert n==NX;
        }

        if(hasIpv4) // append ipv4
        {
            n = v4Parse(chars, iLastColon + 1, end, bytes, n);
            if(n!=16) throw parseError;
        }

        return bytes;
    }
    static int v6Parse(CharSequence chars, int start, int end, byte[] bytes, int n) throws Exception
    {
        // input: empty, or x *(":" x)
        if(start==end)
            return n;

        while(true)
        {
            int nDigit=0;
            int value=0;
            char ch;
            while(start<end && (ch=chars.charAt(start))!=':')
            {
                if(++nDigit>4) throw parseError;
                int v = _HexUtil.hex2int(ch);
                if(v==-1) throw parseError;
                value = (value<<4) + v;
                start++;
            }
            if(nDigit==0) throw parseError;

            if(n+2>bytes.length) throw parseError;
            bytes[n++] = (byte)(value>>8);
            bytes[n++] = (byte)(value);

            if(start==end)
                return n;
            // else, chars[start]==':'
            start++; // continue
        }
    }

    static int v4Parse(CharSequence chars, int start, int end, byte[] bytes, int n) throws Exception
    {
        // input: d *("." d)
        while(true)
        {
            int nDigit=0;
            int value=0;
            char ch;
            while(start<end && (ch=chars.charAt(start))!='.')
            {
                if(++nDigit>3) throw parseError;
                if(ch<'0'||ch>'9') throw parseError;
                value = value*10 + (ch-'0');
                start ++;
            }
            if(nDigit==0) throw parseError;
            if(value>255) throw parseError;

            if(n+1>bytes.length) throw parseError;
            bytes[n++] = (byte)value;

            if(start==end)
                return n;
            // else, chars[start]=='.'
            start++; // continue
        }
    }

}
