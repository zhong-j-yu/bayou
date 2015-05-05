package _bayou._http;

import _bayou._str._ChArrCi;
import _bayou._str._CharDef;
import _bayou._tmp._Util;
import bayou.http.Cookie;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;

public class _Rfc6265
{
    // based on
    // http://tools.ietf.org/html/rfc6265#section-5.1.1
    // we are a little stricter than it.
    // our goal is to at least accept these 3 date formats:
    // http://tools.ietf.org/html/rfc7231#section-7.1.1.1
    //    Sun, 06 Nov 1994 08:49:37 GMT    ; IMF-fixdate
    //    Sunday, 06-Nov-94 08:49:37 GMT   ; obsolete RFC 850 format
    //    Sun Nov  6 08:49:37 1994         ; ANSI C's asctime() format

    static final long[] dateTokenChars = _CharDef.plus(_CharDef.alphaDigitChars, _CharDef.set(":"));
    // "non-delimiter" in RFC include other odd chars, which seems unnecessary.

    public static Instant parseDate(String string)
    {
        char[] chars = string.toCharArray();
        return parseDate(chars, 0, chars.length);
    }

    public static Instant parseDate(char[] chars, int start, int end)
    {
        int year=-1, month=-1, day=-1, seconds=-1;

        int endToken = start-1;
        LOOP: while(true)
        {
            start=endToken+1;
            // skip delimiters, reach next token
            while(start<end && !_CharDef.check(chars[start], dateTokenChars) )
                start++;
            if(start>=end)
                break;
            endToken = start+1;
            while(endToken<end && _CharDef.check(chars[endToken], dateTokenChars) )
                endToken++;
            int tokenLength = endToken-start; // at least 1

            // parse token
            // the only ambiguity is between day DD and 2 digit year YY. assume DD comes before YY.

            DAY: if(day==-1)
            {
                // match 1*2DIGIT
                if(tokenLength>2) break DAY;
                day = _Util.digits(chars, start, endToken);
                if(day==-1) break DAY;
                if(day<1||day>31) return null;
                continue LOOP;
            }
            MONTH: if(month==-1)
            {
                month = month(chars, start, endToken);
                if(month==-1) break MONTH;
                // month=1-12
                continue LOOP;
            }
            YEAR: if(year==-1)
            {
                // match 2DIGIT / 4DIGIT  (no 3-digit year)
                if(tokenLength!=2 && tokenLength!=4) break YEAR;
                year = _Util.digits(chars, start, endToken);
                if(year==-1) break YEAR;
                if(tokenLength==2) // YY
                    year += year>=70? 1900 : 2000;
                // accept all 4-digit years (0000-9999)
                continue LOOP;
            }
            TIME: if(seconds==-1)
            {
                // match HH:MM:SS (each field contains exactly 2 digits)
                if(tokenLength!=8) break TIME;
                if(chars[start+2]!=':') break TIME;
                if(chars[start+5]!=':') break TIME;
                int hh = _Util.digits(chars, start  , start+2);
                if(hh==-1) break TIME;
                int mm = _Util.digits(chars, start+3, start+5);
                if(mm==-1) break TIME;
                int ss = _Util.digits(chars, start+6, start+8);
                if(ss==-1) break TIME;
                if(hh>23||mm>59||ss>59) return null;
                seconds = ss + mm*60 + hh*3600;
                continue LOOP;
            }

            // unknown token, ignore
        }

        if(year==-1||month==-1||day==-1||seconds==-1)
            return null;

        try
        {
            long epochDays = LocalDate.of(year, month, day).toEpochDay();
            // fail if `day` does not exist in that year and month
            // not sure about pre-Gregorian dates
            return Instant.ofEpochSecond( epochDays*86400 + seconds );
        }
        catch (Exception e)
        {
            return null;
        }
    }


    static final HashMap<_ChArrCi,Integer> monthMap = new HashMap<>();  // "Jan"->1 ...
    static
    {
        int m=1;
        for(char[] mmm : _HttpDate.MONTHS)
            monthMap.put( new _ChArrCi(mmm), m++ );
    }
    static int month(char[] chars, int start, int end)
    {
        // match first 3 letters of months
        if(end-start<3) return -1;
        Integer x = monthMap.get( new _ChArrCi(chars, start, 3) );
        if(x==null) return -1;
        return x.intValue();
    }


    //////////////////////////////////////////////////////////////////////////////////////////////////////

    // parse response Set-Cookie header value
    // http://tools.ietf.org/html/rfc6265#section-5.2
    // return null if parse error

    public static Cookie parseSetCookieHeader(String string)
    {
        char[] chars = string.toCharArray();
        return parseSetCookieHeader(chars, 0, chars.length);
    }

    public static Cookie parseSetCookieHeader(char[] chars, int start, int end)
    {
        String name;
        String value;
        {
            int iS = find(';', chars, start, end);
            int iE = find('=', chars, start, iS);
            if(iE==iS) return null; // no "="
            name = trim_string(chars, start, iE);
            if(name.isEmpty()) return null;
            value = trim_string(chars, iE+1, iS);
            start = iS+1;
        }

        Instant expires = null;
        Duration maxAge = null;
        String domain = null;
        String path = null;
        boolean secure = false;
        boolean httpOnly = false;

        while(start<end)
        {
            int iS = find(';', chars, start, end);
            int iE = find('=', chars, start, iS);

            Attr attr = attr(chars, start, iE); // non-null
            switch(attr)
            {
                case Expires :
                {
                    Instant instant = parseDate(chars, iE+1, iS);
                    if(instant != null) expires = instant;
                    break;
                }
                case Max_Age :
                {
                    String v = trim_string(chars, iE+1, iS);
                    try{ maxAge = Duration.ofSeconds( Long.parseLong(v) ); }
                    catch(Exception e){ /* parse error, ignore */ }
                    break;
                }
                case Domain:
                {
                    String s = trim_string(chars, iE+1, iS);
                    if(!s.isEmpty()) // ignore if empty
                    {
                        if(s.startsWith("."))
                            s=s.substring(1); // remove leading "."
                        domain = s;
                    }
                    break;
                }
                case Path:
                {
                    String s = trim_string(chars, iE+1, iS);
                    if(s.startsWith("/"))
                        path = s;
                    // otherwise we ignore it. however, rfc says we should set path=null in this case.
                    // rfc seems wrong, because it may overwrite an earlier legit Path attribute.
                    // of course, there should be only one Path attribute, then we and rfc agree in effect.
                    break;
                }
                case Secure:
                {
                    secure = true;
                    break;
                }
                case HttpOnly:
                {
                    httpOnly = true;
                    break;
                }
                default: // unknown, ignore
            }

            start = iS+1;
        }

        if(maxAge==null && expires!=null)
            maxAge = Duration.ofSeconds( expires.getEpochSecond()-System.currentTimeMillis()/1000 );

        try
        {
            return new Cookie(name, value, maxAge, domain, path, secure, httpOnly);
        }
        catch (Exception e) // invalid name, value, domain, or path; ignore this cookie entirely
        {
            return null;
            // client may have to tolerate imperfect server sending invalid name, value, domain, path.
            // we'll need a flag conf.looseCookieParsing, and call new Cookie( validate=false, ...)
        }
    }


    static int find(char ch, char[] chars, int start, int end)
    {
        for(int i=start; i<end; i++)
            if(chars[i]==ch)
                return i;
        return end; // not -1
    }

    static _ChArrCi trimArrCi(char[] chars, int start, int end)
    {
        if(start>end) // allowed
            start=end;

        while(end>start && isWS(chars[end-1]))
            end--;
        while(start<end && isWS(chars[start]))
            start++;
        return new _ChArrCi(chars, start, end-start);
    }
    static boolean isWS(char ch) // only for SP and HT
    {
        return ch==' ' || ch=='\t';
    }
    static String trim_string(char[] chars, int start, int end)
    {
        return trimArrCi(chars, start, end).toString();
    }


    enum Attr
    {
        Expires, Max_Age, Domain, Path, Secure, HttpOnly, UNKNOWN
    }

    static final HashMap<_ChArrCi, Attr> attrMap = new HashMap<>();
    static
    {
        for(Attr attr : Attr.values())
        {
            String name = attr.name().replace('_', '-');
            attrMap.put( new _ChArrCi(name.toCharArray()), attr );
        }
    }

    static Attr attr(char[] chars, int start, int end)
    {
        Attr attr = attrMap.get( trimArrCi(chars, start, end) );
        if(attr==null) attr = Attr.UNKNOWN;
        return attr;
    }







}
