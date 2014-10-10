package _bayou._tmp;

import bayou.async.Async;

import java.net.InetAddress;

public class _Dns
{
    public static boolean isValidDomain(CharSequence domain)
    {
        return isValidDomain(domain, 0, domain.length());
    }
    public static boolean isValidDomain(CharSequence domain, int start, int end)
    {
        // syntax:
        // http://tools.ietf.org/html/rfc1123#page-13
        // http://tools.ietf.org/html/rfc1034#section-3.5
        // 1 or more labels separated by ".". each label contains 1 or more letter/digit/hyphen.
        // each label cannot start/end with hyphen. last label cannot be all digits.

        int state=0;
        boolean allDigits=true;
        for(int i=start; i<end; i++)
        {
            char c = domain.charAt(i);
            if(c>0xff)
                return false;

            allDigits = allDigits && '0'<=c && c<='9';

            if(_CharDef.check(c, _CharDef.alphaDigitChars))
                state=1;
            else if(c=='-' && state!=0)
                state=2;
            else if(c=='.' && state==1)
            {   state=0; allDigits=true;   }
            else
                return false;
        }
        return state==1 && !allDigits;
    }

    public static Async<InetAddress> resolve(String domain)
    {
        return Async.execute( ()->InetAddress.getByName(domain) );
        // this will spawn a new thread
        // todo: actual async dns impl
    }

}
