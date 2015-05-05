package _bayou._str;

import bayou.util.OverLimitException;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;

public class _CharsetDecoder
{
    final int maxChars;  // CAUTION: not code points!
    CharsetDecoder decoder;
    StringBuilder sb;
    byte[] leftover;

    public _CharsetDecoder(int maxChars, Charset charset)
    {
        this.maxChars = maxChars;
        this.decoder = charset.newDecoder();

        sb = new StringBuilder();
    }

    public void decode(ByteBuffer bb) throws CharacterCodingException, OverLimitException
    {
        if(leftover!=null)
        {
            int n = leftover.length + bb.remaining();
            if(n<0) // integer overflow. not likely.
                throw new RuntimeException(); // deal with it later if it's a real problem

            ByteBuffer nbb = ByteBuffer.allocate(n);
            nbb.put(leftover);
            nbb.put(bb);
            nbb.flip();
            bb=nbb;
            leftover=null;
        }

        char[] chars = new char[1024];
        while(true)
        {
            CharBuffer cb = CharBuffer.wrap(chars);
            CoderResult result = decoder.decode(bb, cb, false);
            if(result.isError())
                result.throwException();

            sb.append(chars, 0, cb.position());
            if(sb.length()>maxChars)
                throw new OverLimitException("maxChars", maxChars);

            if(result.isUnderflow())
            {
                if(bb.hasRemaining()) // well this is really retarded. bad bad decoder!
                {
                    leftover = new byte[bb.remaining()];
                    bb.get(leftover);
                }
                return;
            }

            assert result.isOverflow();
            // continue
        }
    }

    public void finish() throws CharacterCodingException, OverLimitException
    {

        char[] chars = new char[1024];
        CharBuffer cb = CharBuffer.wrap(chars);

        ByteBuffer in = leftover!=null? ByteBuffer.wrap(leftover) : ByteBuffer.allocate(0);
        CoderResult result = decoder.decode(in, cb, true);
        if(!result.isUnderflow())
            result.throwException();

        result = decoder.flush(cb);
        if(!result.isUnderflow())
            result.throwException();

        sb.append(chars, 0, cb.position());
        if(sb.length()>maxChars)
            throw new OverLimitException("maxChars", maxChars);
    }

    public String getString()
    {
        return sb.toString();
    }
}
