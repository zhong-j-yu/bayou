package _bayou._tmp;

import java.util.ArrayList;
import java.util.List;

public class _StrUtil
{
    public static String lowerCase(String value)
    {
        char[] chars=null;
        for(int i=0; i<value.length(); i++)
        {
            int c = value.charAt(i);
            if('A'<=c && c<='Z')
            {
                if(chars==null)
                    chars = value.toCharArray();
                chars[i] = (char)(c + ('a'-'A'));
            }
        }
        if(chars==null)
            return value;
        else
            return new String(chars);
    }

    public static void appendQuoted(StringBuilder sb, String str) // quote string, escape " and \
    {
        sb.append('"');
        for(int i=0; i<str.length(); i++)
        {
            char c = str.charAt(i);
            if(c=='"' || c=='\\')
                sb.append('\\');
            sb.append(c);
        }
        sb.append('"');
    }

    public static String doQuote(String value)
    {
        StringBuilder sb = new StringBuilder(value.length() + 2 + 10);
        appendQuoted(sb, value);
        return sb.toString();
    }

    // syntax expects a token or a quoted string.
    // if value is not a token, return a quoted string, only escape " and \
    public static String mayQuote(String value)
    {
        if(value.isEmpty() || !_CharDef.check(value, _CharDef.Http.tokenChars))
            return doQuote(value);

        // all token chars, no quote
        return value;
    }

    public static int skipWhiteSpaces(String str, int i) // i<=N
    {
        for(; i<str.length(); i++)
        {
            char ch = str.charAt(i);
            if(ch!=' ' && ch!='\t')
                break;
        }
        return i; // i<=N
    }

    public static int calcHashCodeInLowerCases(CharSequence chars)
    {
        int hc = 0;
        for(int i=0; i<chars.length(); i++)
            hc = hc*31 + lowerCase(chars.charAt(i));
        return hc;
    }

    public static int lowerCase(int c)   // c is likely already in lower case
    {
        if('Z'>=c&&c>='A')  // 'Z'>=c is likely false.  c>='A' is likely true.
            return c+32;
        else
            return c;
    }

    public static boolean equalIgnoreCase(CharSequence charsA, CharSequence charsB)
    {
        int N = charsA.length();
        if(N!=charsB.length())
            return false;
        for(int i=0; i<N; i++)
            if(!sameCharIgnoreCase(charsA.charAt(i), charsB.charAt(i)))
                return false;
        return true;

    }

    public static boolean sameCharIgnoreCase(int c1, int c2)
    {
        if(c1==c2)
            return true;
        return sameCharIgnoreCase2(c1,c2);  // keep this method small for inlining
    }

    static private boolean sameCharIgnoreCase2(int c1, int c2)
    {
        int diff=c1-c2;
        if(diff==32)   // 'a'-'A'
            return 'a'<=c1 && c1<='z';   // likely true
        if(diff==-32)  // 'A'-'a'
            return 'A'<=c1 && c1<='Z';   // likely true
        return false;
    }


    public static ArrayList<String> splitComma(String string)
    {
        ArrayList<String> list = new ArrayList<>();
        final int L = string.length();
        int x=0;
        while(x<L)
        {
            int y = string.indexOf(',', x);
            if(y==-1)
                y=L;
            String s = string.substring(x, y).trim();
            if(!s.isEmpty())
                list.add(s);
            x=y+1;
        }
        return list;
    }
    // return read-only list
    public static List<String> splitCommaRO(String string)
    {
        ArrayList<String> list = splitComma(string);
        String[] array = list.toArray(new String[list.size()]);
        return new _Array2ReadOnlyList<>(array);
    }
}
