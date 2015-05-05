package _bayou._str;

public class _CharDef
{
    public static final String a_zA_Z0_9 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    // a set of chars is defined on 256 bits in long[4]
    // pretty fast to use long[] to check if a char is allowed; beats testing 'a'<=c&&... for usual inputs
    // caution: must ensure char<=0xff



    public static boolean check(String str, long[] set)
    {
        for(int i=0; i<str.length(); i++)
        {
            int ch = str.charAt(i);
            if(ch>0xff || !check(ch, set))
                return false;
        }
        return true;
    }

    // caller ensures that ch is 0x00-FF
    public static boolean check(int ch, long[] set)
    {
        assert 0<=ch && ch<=0xFF;

        return 0L != ( set[ch>>6] & (1L<<ch) );
    }

    static void mark(long[] set, int ch)
    {
        set[ch>>6] |= (1L<<ch);
    }

    public static long[] set(String... chars)
    {
        long[] set = new long[4];
        for(String str : chars)
            for(int i=0; i<str.length(); i++)
                mark(set, str.charAt(i));
        return set;
    }
    static long[] range(int from, int to) // inclusive
    {
        long[] set = new long[4];
        for(int i=from; i<=to; i++)
            mark(set, i);
        return set;
    }
    public static long[] plus(long[] set0, long[]... sets)
    {
        set0 = set0.clone();
        for(long[] set : sets)
            for(int i=0; i<4; i++)
                set0[i] |= set[i];
        return set0;
    }
    static long[] minus(long[] set0, long[]... sets)
    {
        set0 = set0.clone();
        for(long[] set : sets)
            for(int i=0; i<4; i++)
                set0[i] &= ~set[i];
        return set0;
    }

    // see also rfc5234

    public static final long[] lowerAlphaChars = range('a', 'z');
    public static final long[] upperAlphaChars = range('A', 'Z');
    public static final long[] digitChars = range('0', '9');
    public static final long[] alphaChars = plus(lowerAlphaChars, upperAlphaChars);
    public static final long[] alphaDigitChars = plus(alphaChars, digitChars);

    public static final long[] wspChars = set(" \t");
    public static boolean wsp(int ch){ return ch==' ' || ch=='\t'; }

    public static final long[] printChars = range(0x21, 0x7E);
    // AKA: visible chars; ascii chars excluding CTLs and SP

    public static final long[] xChars = range(0x80, 0xFF);

    public static class Rfc822
    {
        public static final long[] fieldNameChars = minus(printChars, set(":"));
        // pretty loose, may contain all kinds of punctuations

        // field body: spec says 0x00-0x7F, including bare CR or LF, but not CRLF.
        // for modern app, we need 8-bit for non ascii chars. also, we allow no CR or LF.
        public static final long[] fieldBodyCharsX = minus(range(0x00, 0xFF), set("\r\n"));
        // really loose, everything except CR LF
    }

    public static class Rfc3986 // RFC 3986
    {
        // all chars that can appear legally in a uri, including %
        public static final long[] legalChars = set(
            a_zA_Z0_9, "-._~",  // unreserved
            ":/?#[]@",          // gen-delims
            "!$&'()*+,;=",      // sub-delims
            "%"                 // %
        );

        // note:
        // [] can only appear in "host" around an IP v6 address
        // #[] cannot appear in uri path and query. ? can't be appear in path.

        public static final long[] pathChars =  minus(legalChars, set("%#[]?"));
        public static final long[] queryChars = minus(legalChars, set("%#[]"));
        // queryChars also contain all path chars, so queryChars=pathQueryChars
        // fragment chars are identical to query chars
    }
    public static class UriLoose // allow more octets in URI path and query. 0x21-0xFF
    {
        // all chars that can appear in a uri, including % and #
        public static final long[] legalChars = range(0x21, 0xFF);

        public static final long[] pathChars =  minus(legalChars, set("%#?"));
        public static final long[] queryChars = minus(legalChars, set("%#"));
        // queryChars also contain all path chars, so queryChars=pathQueryChars
        // fragment chars are identical to query chars
    }

    public static class Html  // 4.01
    {
        // in application/x-www-form-urlencoded chars that need no escaping.
        // we are looser than spec, it's ok to not escape :/?@!$'()*,;
        public static final long[] safeQueryChars = minus( Rfc3986.queryChars, set("+&="));
    }

    public static class Http   // RFC 2616
    {

        public static final long[] tokenChars = set(a_zA_Z0_9, "!#$%&'*+-.^_`|~");

        // fragment is not allowed
        public static final long[] reqUriChars = minus(UriLoose.legalChars, set("#"));

        public static final long[] versionChars = set("HTTP/.0123456789");

        // HT SP VCHAR obs-text
        public static final long[] textChars = plus(wspChars, printChars, xChars);

        public static final long[] headerValueChars = textChars;
        public static final long[] reasonPhraseChars = textChars;

        public static final long[] etagChars = plus(range(0x21,0x21), //http bis: %x21 / %x23-7E / obs-text
                range(0x23,0x7E), xChars);
    }

    public static class Cookie  // RFC 6265
    {
        public static final long[] nameChars = Http.tokenChars;

        public static final long[] valueChars = plus(range(0x21, 0x21),
                range(0x23,0x2B), range(0x2D,0x3A), range(0x3C,0x5B), range(0x5D,0x7E));

    }
}

