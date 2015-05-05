package _bayou._str;

// usually we don't want to call CharSequence.subSequence(), since its exact behavior is unclear.
// some impl will copy the chars in range, which we want to avoid.
// this class create a sub sequence without copying chars
public class _CharSubSeq implements CharSequence
{
    public static CharSequence of(CharSequence source, int start, int end)
    {
        // known subclasses that are safe to invoke subSequence()
        if(source instanceof _CharSubSeq)
            return source.subSequence(start, end);

        // don't invoke subSequence(), wrap instead
        return new _CharSubSeq(source, start, end);
    }

    final CharSequence source;
    final int start;
    final int length;

    private _CharSubSeq(CharSequence source, int start, int end)
    {
        this.source = source;
        this.start = start;
        this.length = end-start;
    }

    @Override
    public int length()
    {
        return length;
    }

    @Override
    public char charAt(int index)
    {
        return source.charAt(start+index);
    }

    @Override
    public _CharSubSeq subSequence(int start, int end)
    {
        int s0 = this.start;
        return new _CharSubSeq(source, s0+start, s0+end);
    }

    @Override
    public String toString()
    {
        return source.subSequence(start, start+length).toString();
    }
}
