package _bayou._tmp;

import bayou.mime.Headers;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;

public class _KnownHeaders
{
    static final HashMap<String       ,String> map0 = new HashMap<>();
    static final HashMap<_ChArrCi,String> map1 = new HashMap<>();
    static final HashMap<_StrCi,String> map2 = new HashMap<>();

    static
    {
        final int modPublicStaticFinal = Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL;
        for(Field field : Headers.class.getDeclaredFields())
        {
            if((field.getModifiers() & modPublicStaticFinal) != modPublicStaticFinal)
                continue;
            if(field.getType()!=String.class)
                continue;
            // public static final String
            String s;
            try{ s = (String)field.get(null); } catch(Exception e){ throw new AssertionError(e); }

            map0.put(s, s);
            _ChArrCi k1 = new _ChArrCi(s.toCharArray(), s.length());
            map1.put(k1, s);
            _StrCi k2 = new _StrCi(s);
            map2.put(k2, s);
        }
    }
    // look up a well-known header. chars may be in different cases.
    public static String lookup(char[] chars, int length)
    {
        _ChArrCi k1 = new _ChArrCi(chars, length);
        return map1.get(k1);
    }
    public static String lookup(String s)
    {
        String v = map0.get(s); // likely to succeed, with s==key
        if(v!=null)
            return v;

        _StrCi k2 = new _StrCi(s);
        return map2.get(k2);   // unlikely to succeed(s is known header but in diff cases )
    }

}
