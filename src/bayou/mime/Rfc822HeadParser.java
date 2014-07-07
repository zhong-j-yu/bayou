package bayou.mime;

import _bayou._tmp._CharDef;
import _bayou._tmp._HexUtil;
import _bayou._tmp._KnownHeaders;
import bayou.util.OverLimitException;

import java.nio.ByteBuffer;
import java.text.ParseException;

// copied from http request head parser. probably a little too complex than necessary.
class Rfc822HeadParser
{
    static final int SP=' ', HT='\t', CR='\r', LF='\n', COLON=':';

    // ---------------------------------------------------------------------------------

    final int maxHeaderNameBytes;
    final int maxHeaderValueBytes;
    final int maxHeadTotalBytes;
    Rfc822HeadParser(int maxHeaderNameBytes, int maxHeaderValueBytes, int maxHeadTotalBytes)
    {
        this.maxHeaderNameBytes = maxHeaderNameBytes;
        this.maxHeaderValueBytes = maxHeaderValueBytes;
        this.maxHeadTotalBytes = maxHeadTotalBytes;

        chars = new char[Math.max(maxHeaderNameBytes, maxHeaderValueBytes)];

        this.headers = new HeaderMap();
    }


    char[] chars;
    int iChar = 0;
    String trim_string()
    {
        int end=iChar;
        while(end>0 && chars[end-1]<=SP)
            end--;
        int start=0;
        while(start<end && chars[start]<=SP)
            start++;
        iChar=0;
        return new String(chars, start, end-start);
    }

    // ParserException or OverLimitException
    Exception err;   // not null if state==State.ERROR
    State err(Exception err)
    {
        this.err = err;
        return state= State.ERROR;
    }
    State err(String parseErrMsg)
    {
        return err(new ParseException(parseErrMsg,0));
    }

    HeaderMap headers;
    String currHeaderName;

    int bb_pos0;
    int cTotal=0;

    void parse(ByteBuffer bb)
    {
        assert state!= State.END && state!= State.ERROR;  // otherwise parse is done, no more inputs.

        bb_pos0 = bb.position();
        try
        {
            parse2(bb);
        }
        catch (NeedMoreBytes e)
        {
            // ok
        }
        catch (FieldTooLong e)
        {
            err(state == State.NAME ?
                    new OverLimitException("maxHeaderNameBytes", maxHeaderNameBytes) :
                    new OverLimitException("maxHeaderValueBytes", maxHeaderValueBytes) );
        }

        cTotal += bb.position()-bb_pos0;
    }

    // control exceptions. we don't expect them to be thrown in typical scenarios.
    static public class NeedMoreBytes extends Exception
    {
        private NeedMoreBytes(){ super("need more bytes", null, false, false); }
        static final NeedMoreBytes instance = new NeedMoreBytes();
    }
    static public class FieldTooLong extends Exception
    {
        private FieldTooLong(){ super("field too long", null, false, false); }
        static final FieldTooLong instance = new FieldTooLong();
    }

    static int read(ByteBuffer bb) throws NeedMoreBytes
    {
        // experimented: get() without checking hasRemaining(), check (rare) BufferUnderflowException instead.
        // turns out that's not any faster(even if exception is never thrown). hasRemaining()+get() is optimized well.
        if(bb.hasRemaining())
            return 0xff & bb.get();
        else
            throw NeedMoreBytes.instance; // rare. whole head usually comes in one packet
    }
    static void unread(ByteBuffer bb)
    {
        bb.position(bb.position()-1);
    }
    void collect(long[] charDef, ByteBuffer bb, int max) throws NeedMoreBytes, FieldTooLong
    {
        while(true)
        {
            int c = read(bb);
            if(_CharDef.check(c, charDef))
            {
                save(c, max);
            }
            else
            {
                unread(bb);
                return;  // if this method returns normally, at least one char is available for next read()
            }

        }
    }
    void save(int c, int max) throws FieldTooLong
    {
        if(!( iChar<max ))
            throw FieldTooLong.instance;

        // iChar<max<=chars.length
        chars[iChar++]=(char)c;
    }

    // input may end at any point, we must always save the parser state.
    enum State
    {
        NL, NAME, VALUE, CR2, LF2, POST_LF2, LF3,
        END, ERROR
    }
    State state = State.NL;

    // return END or ERROR, or throw
    State parse2(ByteBuffer bb) throws FieldTooLong, NeedMoreBytes
    {
        // at the beginning of each state-case, we read one or more chars.
        // read may fail with NeedMoreBytes, then next parse2() will jump to the same place and resume the read.

        // line break: CRLF per spec; we also accept a single LF

        GOTO_STATE: while(true)
        {
            int c;
            switch(state)
            {
                case NL:  // next line (no folding). new name / end of head. test for CRLF
                    c = read(bb);
                    if(c==CR) {
                        state = State.LF3;
                        continue GOTO_STATE;
                    } else if(c==LF) {
                        state = State.END;
                        continue GOTO_STATE;
                    } else {
                        unread(bb);
                        state= State.NAME;
                        // goto NAME
                    }

                case NAME:  // 1*name-char COLON
                    collect(_CharDef.Rfc822.fieldNameChars, bb, maxHeaderNameBytes);
                    c = read(bb); // won't fail after collect(). the 1st non-name char.

                    if(iChar==0) // 1st char is not name-char
                        return err("invalid header name");
                    if(c!=COLON) // maybe WSP (which is not allowed)
                        return err("colon expected after header name");

                    currHeaderName = _KnownHeaders.lookup(chars, iChar);
                    if(currHeaderName!=null) // common case. well-known header.
                        iChar=0;
                    else // not a well-known header.
                        currHeaderName = trim_string();  // actually no trimming needed

                    state = State.VALUE;
                    // goto VALUE

                case VALUE: // *body-char
                    collect(_CharDef.Rfc822.fieldBodyCharsX, bb, maxHeaderValueBytes);
                    // next char should be CR/LF

                    // check total head size after each line.
                    if(cTotal+(bb.position()-bb_pos0)>maxHeadTotalBytes)
                        return err(new OverLimitException("maxHeadTotalBytes", maxHeadTotalBytes));

                    state= State.CR2;
                    // goto CR2

                case CR2: // CR | LF
                    c = read(bb);
                    if(c==CR) {
                        state= State.LF2;
                        // goto LF2
                    } else if(c==LF) {
                        state = State.POST_LF2;
                        continue GOTO_STATE;
                    } else {  // some really weird char
                        return err("invalid char in header value: 0x"+ _HexUtil.byte2hex((byte)c));
                    }

                case LF2: // LF
                    c = read(bb);
                    if(c==LF) {
                        state= State.POST_LF2;
                        // goto POST_LF2
                    } else {
                        return err("LF is expected after CR");
                    }

                case POST_LF2:
                    // we need to peek a char to see if there's line folding ahead
                    c = read(bb);
                    if(_CharDef.wsp(c)){ // line folding. obsolete. continue VALUE
                        state= State.VALUE;
                        save(SP, maxHeaderValueBytes); // obs-fold as one SP
                        continue GOTO_STATE;
                    } else {  // not line folding. value ends.
                        unread(bb);

                        String value = trim_string(); // usually there's a leading SP
                        // note: even if value is empty, we'll include it in the `headers`.
                        // a header present with an empty value can be different from a header missing.
                        headers.put(currHeaderName, value); // usually a well known header
                        // we don't support multiple headers with same name

                        state= State.NL;
                        continue GOTO_STATE;
                    }

                case LF3 :  // LF
                    c = read(bb);
                    if(c==LF) {
                        state= State.END;
                        // goto END
                    } else {
                        return err("LF is expected after CR");
                    }

                case END :
                    // not much to do here. only a syntax parser.
                    return State.END;  // head is done

                case ERROR : // should never reach here
                    throw new AssertionError();

                default:
                    throw new AssertionError();

            } // switch state
        } // GOTO_STATE: while(true)
    } // parse2()

}
