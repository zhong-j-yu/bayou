package _bayou._str;

// wrapper of char array, case insensitive
public final class _ChArrCi implements CharSequence
{
    final char[] chars;
    final int offset;
    final int length;
    final int hashCode;
    public _ChArrCi(char[] chars, int offset, int length)
    {
        this.chars = chars;
        this.offset = offset;
        this.length = length;
        this.hashCode = _StrUtil.calcHashCodeInLowerCases(this);
    }
    public _ChArrCi(char[] chars)
    {
        this(chars, 0, chars.length);
    }

    // we only implement CharSequence for to pass `this` to internal util
    // only length()/charAt() are needed
    @Override public int length() { return length; }
    @Override public char charAt(int index) { return chars[offset +index]; }
    @Override public CharSequence subSequence(int start, int end) { throw new UnsupportedOperationException(); }

    @Override
    public String toString()
    {
        return new String(chars, offset, length);
    }

    @Override public int hashCode()
    {
        return hashCode;
    }
    @Override public boolean equals(Object obj)
    {
        return obj instanceof _ChArrCi
                && _StrUtil.equalIgnoreCase(this, (_ChArrCi) obj);
    }
}
