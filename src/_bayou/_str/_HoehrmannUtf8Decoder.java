package _bayou._str;

// Copyright (c) 2008-2010 Bjoern Hoehrmann <bjoern@hoehrmann.de>
// See http://bjoern.hoehrmann.de/utf-8/decoder/dfa/ for details.

/*

License

Copyright (c) 2008-2009 Bjoern Hoehrmann <bjoern@hoehrmann.de>

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.


 */

import bayou.util.OverLimitException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;

public class _HoehrmannUtf8Decoder
{
    public static final int UTF8_ACCEPT = 0;
    public static final int UTF8_REJECT = 12;

    static final byte utf8d[] = {
        // The first part of the table maps bytes to character classes that
        // to reduce the size of the transition table and create bitmasks.
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,  0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,  0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,  0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,  0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,  9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,
        7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,  7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
        8,8,2,2,2,2,2,2,2,2,2,2,2,2,2,2,  2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,
        10,3,3,3,3,3,3,3,3,3,3,3,3,4,3,3, 11,6,6,6,5,8,8,8,8,8,8,8,8,8,8,8,

        // The second part is a transition table that maps a combination
        // of a state of the automaton and a character class to a state.
        0,12,24,36,60,96,84,12,12,12,48,72, 12,12,12,12,12,12,12,12,12,12,12,12,
        12, 0,12,12,12,12,12, 0,12, 0,12,12, 12,24,12,12,12,12,12,24,12,24,12,12,
        12,12,12,12,12,12,12,24,12,12,12,12, 12,24,12,12,12,12,12,12,12,24,12,12,
        12,12,12,12,12,12,12,36,12,36,12,12, 12,36,12,12,12,12,12,36,12,36,12,12,
        12,36,12,12,12,12,12,12,12,12,12,12,
    };

    public static int decode(int state, int[] codePoint, byte b)
    {
        int _byte = 0xff & b;
        int type = utf8d[_byte];

        codePoint[0] = (state != UTF8_ACCEPT) ?
        (_byte & 0x3f) | (codePoint[0] << 6) :
        (0xff >> type) & (_byte);

        state = utf8d[256 + state + type];
        return state;
    }







    // -------------------------------------------------------------------------------

    int state = UTF8_ACCEPT;
    int codePoint;
    final int maxChars;  // CAUTION: not code points!
    StringBuilder sb;

    public _HoehrmannUtf8Decoder(int maxChars)
    {
        this.maxChars = maxChars;
        sb = new StringBuilder();
    }

    public CharSequence getChars()
    {
        return sb;
    }
    public String getString()
    {
        return sb.toString();
    }

    public void decode(ByteBuffer bb) throws CharacterCodingException, OverLimitException
    {
        while(bb.hasRemaining())
        {
            byte b = bb.get();

            int _byte = 0xff & b;
            int type = utf8d[_byte];

            codePoint = (state != UTF8_ACCEPT) ?
                (_byte & 0x3f) | (codePoint << 6) :
                (0xff >> type) & (_byte);

            state = utf8d[256 + state + type];

            if(state==UTF8_REJECT)
            {
                sb = null;
                throw new CharacterCodingException();
            }

            if(state==UTF8_ACCEPT)
                sb.appendCodePoint(codePoint);

            if(sb.length()>maxChars)
                throw new OverLimitException("maxChars", maxChars);
        }
    }

    public void finish() throws CharacterCodingException
    {
        if(state!=UTF8_ACCEPT)
        {
            sb = null;
            throw new CharacterCodingException();
        }
    }

}
