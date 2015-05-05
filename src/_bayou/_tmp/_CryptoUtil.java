package _bayou._tmp;


import _bayou._str._HexUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class _CryptoUtil
{
    // inputs to string, to UTF8 bytes, md5 on them, salt first if salt!=null
    // result is 16 bytes. take 1st n bytes, 1<=n<=16, return hex string
    public static String md5(byte[] salt, int n, Object... inputs)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("MD5");

            if(salt!=null)
                md.update(salt);

            for(Object o : inputs)
            {
                String s =  String.valueOf(o);
                byte[] bs = s.getBytes(StandardCharsets.UTF_8);
                md.update(bs);
            }

            byte[] bytes = md.digest();

            return _HexUtil.toHexString(bytes, 0, n);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new AssertionError(e);
        }

    }
}
