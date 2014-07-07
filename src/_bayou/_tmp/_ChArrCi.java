package _bayou._tmp;

// wrapper of char array, case insensitive
public final class _ChArrCi implements CharSequence
{
    final char[] chars;
    final int length;
    final int hashCode;
    public _ChArrCi(char[] chars, int length)
    {
        this.chars = chars;
        this.length = length;
        this.hashCode = _StrUtil.calcHashCodeInLowerCases(this);
    }

    // we only implement CharSequence for to pass `this` to internal util
    // only length()/charAt() are needed
    @Override public int length() { return length; }
    @Override public char charAt(int index) { return chars[index]; }
    @Override public CharSequence subSequence(int start, int end) { throw new UnsupportedOperationException(); }

    @Override
    public String toString()
    {
        return new String(chars, 0, length);
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
