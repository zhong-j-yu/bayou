package _bayou._tmp;

// wrapper of char array
public final class _ChArr
{
    final char[] chars;
    final int length;
    final int hashCode;
    public _ChArr(char[] chars, int length)
    {
        this.chars = chars;
        this.length = length;

        int hc = 0;
        for(int i=0; i<length; i++)
            hc = hc*31 + chars[i];
        this.hashCode = hc;
    }
    @Override public int hashCode()
    {
        return hashCode;
    }
    @Override public boolean equals(Object obj)
    {
        if(!(obj instanceof _ChArr))
            return false;
        _ChArr that = (_ChArr)obj;
        if(this.length!=that.length)
            return false;
        for(int i=0; i<this.length; i++)
            if(this.chars[i]!=that.chars[i])
                return false;
        return true;
    }
}
