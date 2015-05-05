package _bayou._str;

// case insensitive wrapper of String
public final class _StrCi
{
    final String string;
    final int hashCode;
    public _StrCi(String string)
    {
        this.string = string;
        this.hashCode = _StrUtil.calcHashCodeInLowerCases(string);
    }
    @Override public int hashCode()
    {
        return hashCode;
    }
    @Override public boolean equals(Object obj)
    {
        if(!(obj instanceof _StrCi))
            return false;
        String s1 = this.string, s2 = ((_StrCi) obj).string;
        if(s1==s2) // probably common. e.g. look up http header with interned name
            return true;
        return
            _StrUtil.equalIgnoreCase(s1, s2);
    }
}
