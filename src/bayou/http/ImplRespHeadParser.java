package bayou.http;

import _bayou._http._Rfc6265;
import _bayou._str._CharDef;
import _bayou._tmp.*;
import bayou.mime.HeaderMap;
import bayou.mime.Headers;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;

// this is copied from request head parser; the two are very similar. todo: refactor to share common code.
class ImplRespHeadParser
{
    static final int fieldInitSize = 256;
    int fieldMax, totalMax;
    // hard coded limit of 64 bytes for: reason, header name, spaces.
    // This-is-how-long-sixty-four-bytes-string-looks-like-it--is--long
    ImplRespHeadParser(int fieldMax, int totalMax)
    {
        this.fieldMax = fieldMax;
        this.totalMax = totalMax;

        // chars starts small, can grow.
        // usually fieldMax is set pretty big (probably for cookies)
        // but user may specify a fieldMax smaller than fieldInit
        int initSize = Math.min(fieldInitSize, fieldMax);
        chars = new char[initSize];

        this.response = new HttpResponseImpl();
        response.headers = new HeaderMap();
        response.cookies = new ArrayList<>();
        this.headers = response.headers;
    }


    char[] chars;
    int iChar = 0;
    String string()
    {
        String string = new String(chars,0,iChar);
        iChar=0;
        return string;
    }
    String trim_string()
    {
        int end=iChar;
        while(end>0 && isWS(chars[end-1]))
            end--;
        int start=0;
        while(start<end && isWS(chars[start]))
            start++;
        iChar=0;
        return new String(chars, start, end-start);
    }
    static boolean isWS(char ch) // only for SP and HT
    {
        return ch==SP || ch==HT;
    }

    static final int SP=' ', HT='\t', CR='\r', LF='\n', COLON=':';

    // errors should be rare. no point to omit/reuse error responses. attackers have better ways to stress us.
    // (beware ImplRespMod will write to errorResponse.headers, so errorResponse can't be shared)
    String errorMessage;   // not null if state==State.ERROR
    State err(String msg)
    {
        this.errorMessage = msg;
        return state= State.ERROR;
    }

    int statusCode;
    HttpResponseImpl response;
    HeaderMap headers; //  == response.headers;
    String currHeaderName;  // in nice form

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
            errTooLong();
        }

        cTotal += bb.position()-bb_pos0;
    }

    // control exceptions. we don't expect them to be thrown in typical scenarios.
    static public class NeedMoreBytes extends _ControlException
    {
        private NeedMoreBytes(){ super("need more bytes"); }
        static final NeedMoreBytes instance = new NeedMoreBytes();
    }
    static public class FieldTooLong extends _ControlException
    {
        private FieldTooLong(){ super("field too long"); }
        static final FieldTooLong instance = new FieldTooLong();
    }
    State errTooLong()
    {
        switch(state)
        {
            case REASON: return err("reason-phrase too long");
            case NAME:   return err("Header Name too long");
            case VALUE:  return err("Header Value length > "+fieldMax);
            default:     return err("Bad status line");  // WSP1, WSP2, VERSION, CODE
        }
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

        if(iChar==chars.length) // both < max
            chars = Arrays.copyOf(chars, chars.length*2); // expand.
        // due to expansion copying, in worst case, we write 4 times for each char.
        // that's not too bad, relative to the performance of this parser.

        chars[iChar++]=(char)c;
    }

    // input may end at any point, we must always save the parser state.
    enum State
    {
        VERSION, WSP1, CODE, WSP2, REASON, CR1, LF1,
        NL, NAME, VALUE, CR2, LF2, POST_LF2,
        LF3, END, ERROR
    }
    State state = State.VERSION;

    // return END or ERROR, or throw
    State parse2(ByteBuffer bb) throws FieldTooLong, NeedMoreBytes
    {
        // at the beginning of each state-case, we read one or more chars.
        // read may fail with NeedMoreBytes, then next parse2() will jump to the same place and resume the read.

        // unread() seem stupid - we have the char, we can make a test and jump to a right place.
        // but that requires extra (and duplicate) code. not sure that's a good idea.
        // number of unread() is small compared to number of chars, so it's no big deal.

        // line break: CRLF per spec; we also accept a single LF

        GOTO_STATE: while(true)
        {
            int c;
            switch(state)
            {
                case VERSION: // HTTP/1.0 | HTTP/1.1
                    collect(_CharDef.Http.versionChars, bb, 8);  // if more than 8, throws FieldTooLong, -> err()
                    char[] cs=chars;
                    if(iChar!=8||cs[0]!='H'||cs[1]!='T'||cs[2]!='T'||cs[3]!='P'||cs[4]!='/'||cs[6]!='.')
                        return err("Bad HTTP-version"); // not HTTP at all
                    if(cs[5]!='1')  // 0 or 2?
                        return err("Only supports HTTP/1.0 and HTTP/1.1");
                    int httpMinorVersion = cs[7]-'0';
                    if(httpMinorVersion!=0 && httpMinorVersion!=1) // no such thing as 1.2 (as of today)
                        return err("Only supports HTTP/1.0 and HTTP/1.1");
                    response.httpVersion = httpMinorVersion==0 ? "1.0" : "1.1";
                    iChar=0;
                    state= State.WSP1;
                    // goto WSP1

                case WSP1:  // 1*WSP
                    collect(_CharDef.wspChars, bb, 64);
                    if(iChar==0)
                        return err("Expect white space after HTTP-version");
                    iChar=0;
                    state= State.CODE;
                    // goto CODE

                case CODE: // 3DIGIT
                    collect(_CharDef.digitChars, bb, 3); // if more than 3 digits, FieldTooLong, err
                    if(iChar!=3)
                        return err("Bad status-code");
                    statusCode = _Util.digits(chars, 0, 3);
                    if(statusCode<100 || statusCode>599) // rfc: category 1-5 only
                        return err("Invalid status-code: "+statusCode);
                    iChar=0;
                    state= State.WSP2;
                    // goto WSP2

                case WSP2: // 1*WSP
                    collect(_CharDef.wspChars, bb, 64);
                    if(iChar==0)
                        return err("Expect white space after status-code");
                    iChar=0;
                    state= State.REASON;
                    // goto REASON

                case REASON:
                    collect(_CharDef.Http.reasonPhraseChars, bb, 64);
                    response.status = new HttpStatus(statusCode, string(), 0);
                    state = State.CR1;
                    // goto CR1

                case CR1: // CR | LF   // we accept a single LF as line terminator; that should be uncommon.
                    c = read(bb);
                    if(c==CR) {
                        state= State.LF1;
                        // goto LF1
                    } else if(c==LF) {
                        state = State.NL;
                        continue GOTO_STATE;
                    } else {
                        return err("Expect CRLF after reason-phrase");
                    }

                case LF1: // LF
                    c = read(bb);
                    if(c==LF) {
                        state= State.NL;
                        // goto NL
                    } else {
                        return err("LF is required after CR");
                    }

                // end of response status line -------------------------------------------------------------

                case NL:  // next line (no folding). new name / end of head. test for CR/LF
                    c = read(bb);
                    if(c==CR) {
                        state = State.LF3;
                        continue GOTO_STATE;
                    } else if(c==LF) {
                        state = State.END;
                        continue GOTO_STATE;
                    } else { // common
                        unread(bb);
                        state= State.NAME;
                        // goto NAME
                    }

                case NAME:  // 1*toke-char COLON
                    collect(_CharDef.Http.tokenChars, bb, 64);
                    c = read(bb); // won't fail after collect(). the 1st non token-char.

                    if(iChar==0) // 1st char is not token-char. could be WSP (first header after request line)
                        return err("Invalid header name");
                    if(c!=COLON)
                    {
                        if(_CharDef.wsp(c)) // WSP after name is explicitly forbidden
                            return err("White space not allowed after header name");
                        else  // other weird char
                            return err("COLON expected after header name");
                    }

                    currHeaderName = _KnownHeaders.lookup(chars, iChar);
                    if(currHeaderName!=null) // common case. well-known header.
                        iChar=0;
                    else // not a well-known header.
                        currHeaderName = string();

                    state = State.VALUE;
                    // goto VALUE

                case VALUE: // *value-char
                    collect(_CharDef.Http.headerValueChars, bb, fieldMax);  // this is where we spend the most time
                    // next char should be CR/LF

                    // check total head size after each line.
                    if(cTotal+(bb.position()-bb_pos0)>totalMax-4)  // 4 bytes for the last CRLF CRLF
                        return err("Request head total length > "+totalMax);

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
                        return err("Invalid char in header value");
                    }

                case LF2: // LF
                    c = read(bb);
                    if(c==LF) {
                        state= State.POST_LF2;
                        // goto POST_LF2
                    } else {
                        return err("LF is required after CR");
                    }

                case POST_LF2:
                    // we need to peek a char to see if there's line folding ahead
                    c = read(bb);
                    if(_CharDef.wsp(c)){ // line folding. obsolete. continue VALUE
                        state= State.VALUE;
                        save(SP, fieldMax); // obs-fold as one SP
                        continue GOTO_STATE;
                    } else {  // not line folding. value ends.
                        unread(bb);

                        if(currHeaderName.equals(Headers.Set_Cookie)) // Set-Cookie is special. (it's the only one)
                        {
                            Cookie cookie = _Rfc6265.parseSetCookieHeader(chars, 0, iChar);
                            iChar=0;
                            if(cookie!=null)
                                response.cookies.add(cookie);
                            // if parse error, ignore this particular header, don't reject the whole response.
                        }
                        else
                        {
                            // rfc7230$3.2 - OWS field-value OWS
                            String value = trim_string(); // usually there's a leading SP
                            // note: even if value is empty, we'll include it in the `headers`.
                            // a header present with an empty value can be different from a header missing. see `Accept`
                            String oldValue = headers.get(currHeaderName);
                            if(oldValue!=null)   // headers with same name
                                value = oldValue +','+' '+ value;
                            headers.put(currHeaderName, value);
                        }

                        state= State.NL;
                        continue GOTO_STATE;
                    }

                // end of name-value -----------------------------------------------------

                case LF3 :  // LF
                    c = read(bb);
                    if(c==LF) {
                        state= State.END;
                        // goto END
                    } else {
                        return err("LF is required after CR");
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
