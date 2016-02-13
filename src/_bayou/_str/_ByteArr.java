package _bayou._str;

import java.nio.charset.StandardCharsets;

// mutable wrapper of byte array
public final class _ByteArr
{
    static final byte[] chars0 = new byte[0];

    byte[] chars;
    int offset;
    int length;
    int hashCode;

    public _ByteArr()
    {
        this.chars = chars0;
        // offset=length=hashCode=0
    }

    public _ByteArr(byte[] chars, int offset, int length)
    {
        reset(chars, offset, length);
    }

    public _ByteArr reset(byte[] chars, int offset, int length)
    {
        this.chars = chars;
        this.offset = offset;
        this.length = length;

        int hc = 0;
        int L = offset+length;
        while(offset<L)
            hc = hc*31 + chars[offset++];
        this.hashCode = hc;

        return this;
    }

    public static _ByteArr of(String str)
    {
        byte[] chars = str.getBytes(StandardCharsets.ISO_8859_1);
        return new _ByteArr(chars, 0, chars.length);
    }


    @Override public int hashCode()
    {
        return hashCode;
    }
    @Override public boolean equals(Object obj)
    {
        if(!(obj instanceof _ByteArr)) return false;
        _ByteArr that = (_ByteArr)obj;

        int L = this.length;
        if(that.length!=L) return false;

        byte[] a1 = this.chars, a2 = that.chars;
        int o1 = this.offset, o2 = that.offset;
        L = o1+L;
        while(o1<L)
            if(a1[o1++]!=a2[o2++]) return false;

        return true;
    }

}
