package _bayou._str;

import java.util.ArrayList;
import java.util.function.Consumer;

// save some CharSequences
public class _CharSeqSaver extends ArrayList<CharSequence> implements Consumer<CharSequence>
{
    public int charCount = 0;

    public _CharSeqSaver(int initialCapacity)
    {
        super(initialCapacity);
    }

    // inheriting ArrayList for impl detail,
    // do not use its mutation methods like  add() etc

    @Override // Consumer<CharSequence>
    public void accept(CharSequence csq)
    {
        super.add(csq);
        charCount += csq.length();
    }

    // for method chaining
    public _CharSeqSaver append(CharSequence csq)
    {
        super.add(csq);
        charCount += csq.length();
        return this;
    }

    public char[] toCharArray()
    {
        char[] chars = new char[charCount];
        int x=0;

        for(CharSequence csq : this)
            for(int i=0; i<csq.length(); i++)
                chars[x++] = csq.charAt(i);

        return chars;
    }
    public CharSequence toCharSequence()
    {
        char[] chars = toCharArray();
        return new _CharArray2Seq(chars);
    }
    public String toString()
    {
        char[] chars = toCharArray();
        return new String(chars);
    }

    public byte[] toLatin1Bytes()
    {
        byte[] bytes = new byte[charCount];
        int x=0;

        for(CharSequence csq : this)
            for(int i=0; i<csq.length(); i++)
                bytes[x++] = (byte)csq.charAt(i);

        return bytes;
    }

}
