package _bayou._tmp;

// host[:port]
// host = domain / ipv4 / "[" ipv6 "]"

import java.net.Inet6Address;
import java.net.InetAddress;

public class _HttpHostPort
{
    // either domain or ip is non-null
    public String domain;  // lower case
    public byte[] ip;      // 4 or 16 bytes

    public int port = -1;   // -1 means missing

    public String toString(int implicitPort)
    {
        String s=domain;
        if(s==null)
        {
            InetAddress ia = _Ip.toInetAddress(ip);
            s = ia.getHostAddress();
            if(ia instanceof Inet6Address)
                s = "["+s+"]";
        }

        if(port!=-1 && port!=implicitPort)
            s = s + ":" + port;

        return s;
    }

    public static _HttpHostPort parse(String string)
    {
        if(string==null || string.isEmpty())
            return null;

        _HttpHostPort hp = new _HttpHostPort();

        int iPort = string.length();
        if(string.charAt(0)=='[')
        {
            int x2 = string.lastIndexOf(']');
            if(x2==-1)
                return null;
            if(x2<string.length()-1)
            {
                if(string.charAt(x2+1)!=':')
                    return null;
                iPort = x2+2;
            }

            hp.ip = _Ip.parseIpv6(string, 1, x2);
            if(hp.ip==null)
                return null;
        }
        else // domain or ipv4
        {
            int iSep = string.lastIndexOf(':');
            if(iSep==-1)
                iSep = string.length();
            else
                iPort = iSep + 1;

            hp.ip = _Ip.parseIpv4(string, 0, iSep);
            if(hp.ip==null) // not ipv4
            {
                if(!_Dns.isValidDomain(string, 0, iSep))
                    return null;
                hp.domain = string.substring(0, iSep).toLowerCase();
            }
        }


        // rfc3986: port can be empty, e.g. "example.com:"
        if(iPort<string.length())
        {
            try
            {
                hp.port = Integer.parseInt(string.substring(iPort));
            }
            catch (NumberFormatException e)
            {
                return null;
            }
            if(hp.port<0 || hp.port>0xFFFF)
                return null;
        }

        return hp;
    }
}
