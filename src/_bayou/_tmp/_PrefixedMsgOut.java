package _bayou._tmp;

import _bayou._str._CharSubSeq;

import java.io.PrintStream;
import java.util.function.Consumer;

// prefix each line with a prefix string
public class _PrefixedMsgOut implements Consumer<CharSequence>
{
    final Consumer<CharSequence> out;
    final String prefix;

    public _PrefixedMsgOut(Consumer<CharSequence> out, String prefix)
    {
        this.out = out;
        this.prefix = prefix;
    }

    public _PrefixedMsgOut(PrintStream out, String prefix)
    {
        this(out::println, prefix);
    }

    @Override
    public void accept(CharSequence msg)
    {
        int i1=0;
        int i2=0;
        int L = msg.length();
        while(i2<L)
        {
            char c = msg.charAt(i2++);
            if(c=='\r' || c=='\n')
            {
                CharSequence line = _CharSubSeq.of(msg, i1, i2 - 1);
                out.accept(prefix+line);
                if(c=='\r' && i2<L && msg.charAt(i2)=='\n')
                    i2++;
                i1=i2;
            }
        }
        CharSequence line = _CharSubSeq.of(msg, i1, i2);
        out.accept(prefix+line);
    }
}
