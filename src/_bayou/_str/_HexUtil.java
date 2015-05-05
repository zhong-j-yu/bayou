package _bayou._str;

import java.util.Arrays;

public class _HexUtil
{

    // hex2int['A'] = 0xA
    public static int[] hex2int = new int[256];
    static
    {
        Arrays.fill(hex2int, -1);
        for(int i=0; i<10; i++) hex2int['0'+i]=i;
        for(int i=0; i< 6; i++) hex2int['A'+i]=hex2int['a'+i]=10+i;
    }

    public static int hex2int(char c)
    {
        if(c>255)
            return -1;
        else
            return hex2int[c];
    }

    // s[i+1] s[i+2] should be HH. return -1 if not. otherwise return 0xHH
    public static int hh2int(String string, int L, int i)
    {
        if(!(i+2<L))
            return -1;
        int h1 = hex2int(string.charAt(i+1));
        if(h1==-1)
            return -1;
        int h2 = hex2int(string.charAt(i+2));
        if(h2==-1)
            return -1;
        return ( h1<<4 | h2 );
    }



    // int2hex[0xA] = 'A'
    public static char[] int2hex = "0123456789ABCDEF".toCharArray();

    public static char byte2hexHi(byte b)
    {
        return int2hex[ (b >> 4) & 0x0f ];
    }
    public static char byte2hexLo(byte b)
    {
        return int2hex[ b & 0x0f ];
    }

    public static String byte2hex(byte b)
    {
        char[] chars = { byte2hexHi(b), byte2hexLo(b) };
        return new String(chars);
    }

    public static String toHexString(byte[] bytes, int start, int length)
    {
        StringBuilder sb = new StringBuilder(length*2);
        for(int i=0; i<length; i++)
        {
            byte b = bytes[start+i];
            sb.append(byte2hexHi(b));
            sb.append(byte2hexLo(b));
        }
        return sb.toString();
    }
    public static String toHexString(byte[] bytes)
    {
        return toHexString(bytes, 0, bytes.length);
    }
}
