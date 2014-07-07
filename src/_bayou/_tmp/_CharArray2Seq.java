package _bayou._tmp;

// wrap char[] as CharSequence
public class _CharArray2Seq implements CharSequence
{
    final char[] source;
    final int start;
    final int length;

    public _CharArray2Seq(char[] source, int start, int length)
    {
        this.source = source;
        this.start = start;
        this.length = length;
    }
    public _CharArray2Seq(char[] source)
    {
        this.source = source;
        this.start = 0;
        this.length = source.length;
    }

    @Override
    public int length()
    {
        return length;
    }

    @Override
    public char charAt(int index)
    {
        return source[start+index];
    }

    @Override
    public _CharArray2Seq subSequence(int start, int end)
    {
        return new _CharArray2Seq(source, this.start+start, end-start);
    }

    @Override
    public String toString()
    {
        return new String(source, start, length);
    }
}
