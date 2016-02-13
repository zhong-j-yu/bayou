package bayou.http;

import _bayou._str._ByteArr;
import _bayou._str._CharDef;
import _bayou._str._HexUtil;
import _bayou._tmp.*;
import bayou.mime.HeaderMap;

import java.nio.ByteBuffer;

import static java.lang.System.arraycopy;

// performance: about 25 cycles for each byte in request head.
class ImplReqHeadParser
{
    static final int nameMax = 64;
    // hard coded limit of 64 bytes for: method, header name, spaces.
    // This-is-how-long-sixty-four-bytes-string-looks-like-it--is--long

    ImplHttpRequest request;

    ImplReqHeadParser(ImplHttpRequest request)
    {
        this.request=request;
    }



    @SuppressWarnings("deprecation")
    static String string(byte[] array, int p0, int px)
    {
        return new String(array, 0, p0, px-p0); // byte->char, ISO-8859-1
    }
    static String trim_string(byte[] array, int p0, int px)  // trim only SP and HT
    {
        while(p0<px && wsp(array[p0]))
            p0++;
        while(p0<px && wsp(array[px-1]))
            px--;
        return string(array, p0, px);
    }

    static final byte SP=' ', HT='\t', CR='\r', LF='\n', COLON=':';
    
    static boolean wsp(byte ch){ return ch==SP || ch==HT; }

    static public class NeedMoreBytes extends _ControlException
    {
        int p0; // current field starts at p0; need more bytes for the field.

        public NeedMoreBytes(int p0)
        {
            this.p0 = p0;
        }
    }

    static public class ParseError extends _ControlException
    {
        int px;  // error found at px. // px is consumed.
        HttpStatus status;
        String msg;

        public ParseError(int px, HttpStatus status, String msg)
        {
            this.px = px;
            this.status = status;
            this.msg = msg;
        }
    }
    static ParseError err(int px, HttpStatus status, String msg)
    {
        return new ParseError(px, status, msg);
    }
    static ParseError errBadVersion(int px)  // syntax error
    {
        return err(px, HttpStatus.c400_Bad_Request,  "Invalid HTTP-Version");
    }

    ParseError fieldTooLong(int px, int max)
    {
        switch(state)
        {
            case METHOD: return err(px, HttpStatus.c400_Bad_Request,  "Method token too long");
            case URI:    return err(px, HttpStatus.c414_URI_Too_Long, "Request-URI length > "+max);
            case VERSION:return errBadVersion(px);
            case NAME:   return err(px, HttpStatus.c400_Bad_Request,  "Header Name too long");
            case VALUE:  return err(px, HttpStatus.c400_Bad_Request,  "Header Value length > "+max);
            default:     return err(px, HttpStatus.c400_Bad_Request,  "Bad Request");  // WSP1, WSP2, VERSION
        }
    }

    static byte read(byte[] bb, int px, int L) throws NeedMoreBytes
    {
        if(px<L)
            return bb[px];
        else // p==L
            throw new NeedMoreBytes(px);
    }

    int collect(byte[] bb, int p0, int L, long[] charDef, int max) throws NeedMoreBytes, ParseError
    {
        int px = p0;
        int M = Math.min(L, p0+max+1);
        while(px<M)
        {
            int c = 0xff & bb[px];
            if(!_CharDef.check(c, charDef))
                return px; // if this method returns normally, at least one char is available for next read

            px++;
        }

        if(px-p0>max)
            throw fieldTooLong(px-1, max);
        else // px==L
            throw new NeedMoreBytes(p0);
    }






    enum State
    {
        LF0, START, METHOD, WSP1, URI, WSP2, VERSION, CR1, LF1,
        NL, NAME, VALUE, CR2, LF2, POST_LF2,
        LF3, DONE
    }
    State state = State.START;

    // errors should be rare. no point to omit/reuse error responses. attackers have better ways to stress us.
    // (beware ImplRespMod will write to errorResponse.headers, so errorResponse can't be shared)
    HttpResponseImpl errorResponse;

    void parse(ByteBuffer bb, HttpServerConf conf, HeaderMap prevHeaders)
    {
        assert state!=State.DONE;  // otherwise parse is done, no more inputs.

        assert bb.hasArray(); // does not support direct ByteBuffer
        byte[] array = bb.array();
        int o = bb.arrayOffset();
        int p = o + bb.position();
        int L = o + bb.limit();

        int px;
        try
        {
            px = parse2(array, p, L, conf, prevHeaders);
        }
        catch (ParseError e)
        {
            px = e.px + 1;

            errorResponse = HttpHelper.simpleResp(e.status, e.msg);
            state = State.DONE;
        }
        bb.position(px-o);

    }

    // request head can be fragmented, coming in multiple packets. we may need to save some bytes in between.
    //
    // [A]  parse bb[p...L]
    // [B]  when parsing a field starting at p0, if bb[L] is reached and more bytes are needed for the field
    //        save bb[p0...L] to buf[0...bufN]
    // [C]  when next bb comes, copy bb[p...] to buf[bufN...bufL], as many bytes as possible.
    //        buf[] capacity is big enough to hold one max field, +1 for next delimiter
    // [D]  parse buf[0...bufL]
    // [E]  if more bytes are needed after [D]
    //      [F]  if bb was exhausted (all copied to buf)
    //              shift buf[p0...bufL] to buf[0...], goto [C]
    //      [G]  if bb has more bytes, adjust `p+=delta`, and goto [A].
    //              `delta>0`, because buf is full, contains at least one field, [D] must have passed buf[bufN].
    //
    // note that [D] always parse from [0], even though [0-bufN] was already parsed earlier.
    // this is to keep parse3() simple, so that it's slightly faster in the common case of no fragmentation.
    // this can lead to O(N^2), if a malicious client sends one byte at a packet.
    // but, each parse3() is still cheap relative to the overhead of the packet.
    // a real defense against this attack is to limit how fragmented a head can be.
    // it's reasonable to expect that a packet contains at least a line (e.g. sent by telnet)
    // if max number of lines allowed is 100, we should only try 100 read()+parse() at most.
    // TBA: config.maxNumberOfHeaders=64  -- use it to derive max number of packets.

    byte[] buf;
    int bufN;

    int pTotal;

    int parse2(byte[] bb, int p, int L, HttpServerConf conf, HeaderMap prevHeaders) throws ParseError
    {
        if(bufN>0)  // [C]
        {
            int copy = Math.min(L-p, buf.length-bufN);
            arraycopy(bb, p, buf, bufN, copy);
            int bufL = bufN + copy;

            try
            {
                int px = parse3(buf, 0, bufL, conf, prevHeaders);  // [D]
                return px + (p-bufN); // DONE
            }
            catch (ParseError e)  // e.px is relative to buf; make it relative to bb.
            {
                e.px += (p-bufN);
                throw e;
            }
            catch (NeedMoreBytes e)   // [E]
            {
                pTotal += e.p0;

                if(copy==L-p)       // [F] no more bytes in bb
                {
                    bufN = bufL-e.p0;
                    arraycopy(buf, e.p0, buf, 0, bufN);  // possible: bufN==0 || e.p0==0
                    return L; // goto [C]
                }
                else // copy<L-p   // [G] more bytes in bb
                {
                    p = e.p0 + (p-bufN);
                    // goto [A]
                }
            }
        }

        try
        {
            int px = parse3(bb, p, L, conf, prevHeaders);  // [A]
            return px; // DONE
        }
        catch (NeedMoreBytes e)       // [B]
        {
            pTotal += e.p0-p;

            bufN = L-e.p0; // possible: bufN==0
            if(bufN>0)
            {
                // TBA: start `buf` at a smaller size (128); expand as necessary.
                if(buf==null) buf = new byte[ Math.max(nameMax, conf.requestHeadFieldMaxLength) + 1 ];
                arraycopy(bb, e.p0, buf, 0, bufN);
            }
            return L; // goto [C]
        }
    }


    String currHeaderName;
    boolean currHeaderX;
    String currHeaderValue;

    // return px if parsing is done
    int parse3(byte[] bb, int p, int L, HttpServerConf conf, HeaderMap prevHeaders) throws NeedMoreBytes, ParseError
    {
        // at the beginning of each state-case, we read one or more chars.
        // read may fail with NeedMoreBytes, then next parse3() will jump to the same state and resume the read.

        // line break: CRLF per spec; we also accept a single LF

        int px = p;  // pointer to the next byte to be read
        int p0;      // start position of the current field
        byte c;      // a single char read

        _ByteArr ba = new _ByteArr();
        HeaderMap headers = request.headers;

        GOTO_STATE: while(true)
        {
            switch(state)
            {
                // ignore one empty line before the request line. [ CRLF / LF ]
                case LF0: // LF    // uncommon. it's here to be out of the common path START->METHOD
                    c = read(bb, px++, L);
                    if(c==LF) {
                        state= State.METHOD;
                        continue GOTO_STATE;
                    } else {
                        throw err(--px, HttpStatus.c400_Bad_Request, "LF is required after CR"); // client uses single CR
                    }

                case START:
                    c = read(bb, px, L);
                    if(c==CR) {  // rare
                        px++;
                        state= State.LF0;
                        continue GOTO_STATE;
                    } else if(c==LF) {  // rare
                        px++;
                        state= State.METHOD;
                        // goto METHOD
                    } else {  // common
                        state= State.METHOD;
                        // goto METHOD
                    }

                case METHOD: // 1*token-char

                    if(L-px>=4 && conf.supportGET && matchGET(bb, px))  // "GET ". fast path, avoid `supportedMethods`
                    {
                        px+=4;
                        request.method = "GET";
                    }
                    else
                    {
                        px = collect(bb, p0=px, L, _CharDef.Http.tokenChars, nameMax);
                        if(px-p0==0)
                            throw err(px, HttpStatus.c400_Bad_Request, "Invalid Method token");
                        if(!wsp(bb[px]))
                            throw err(px, HttpStatus.c400_Bad_Request, "Expect white space after Method");
                        String method=conf.supportedMethods.get( ba.reset(bb, p0, px-p0) );
                        if(method==null)
                            throw err(px, HttpStatus.c501_Not_Implemented, "Method not supported: "+string(bb, p0, px));
                        request.method = method;
                        px++; // consume WSP
                    }

                    state=State.WSP1;
                    // goto WSP1

                // in practice, almost all requests use single SP between METHOD,URI,VERSION

                case WSP1:  // *WSP   // METHOD consumed one WSP already
                    px = collect(bb, p0=px, L, _CharDef.wspChars, nameMax-1);
                    state=State.URI;
                    // goto URI

                case URI: // 1*uri-char WSP
                    px = collect(bb, p0=px, L, _CharDef.Http.reqUriChars, conf.requestHeadFieldMaxLength);
                    c = bb[px]; // the non-uri char that terminates the uri field. should be WSP.

                    // we are being very strict about uri, don't allow any unsanctioned chars.
                    // it might surprise a sloppy client. send a more sensible error message.
                    if(!wsp(c))  // probably an illegal char that client intends to have in the uri.
                        throw err(px, HttpStatus.c400_Bad_Request,
                            "Request-URI contains illegal character: 0x"+ _HexUtil.byte2hex(c));

                    assert px-p0>0; // otherwise WSP1 had consumed c=WSP.
                    request.uri=string(bb, p0, px);
                    px++; // consume WSP
                    state=State.WSP2;
                    // goto WSP2

                case WSP2: // *WSP   // URI consumed one WSP already
                    px = collect(bb, p0=px, L, _CharDef.wspChars, nameMax-1);
                    state=State.VERSION;
                    // goto VERSION

                case VERSION: // HTTP/1.0 | HTTP/1.1

                    if(L-px>8) // 8*char. fast path, avoid `versionChars`
                    {
                        px = (p0=px) + 8; // matchVersion() will check these 8 chars
                    }
                    else
                    {
                        px = collect(bb, p0=px, L, _CharDef.Http.versionChars, 8);
                        // if more than 8, fieldTooLong() -> errBadVersion()
                        if( px-p0!=8 ) // less than 8
                            throw errBadVersion(px);
                    }
                    request.httpMinorVersion = matchVersion(bb, p0); // 0, 1, or throw

                    state= State.CR1;
                    // goto CR1

                case CR1: // CR | LF   // we accept a single LF as line terminator; that should be uncommon.
                    c = read(bb, px++, L);
                    if(c==CR) {
                        state= State.LF1;
                        // goto LF1
                    } else if(c==LF) {
                        px--;
                        state= State.LF1;
                        // goto LF1
                    } else {  // might be a forgivable mistake (WSP?)
                        throw err(--px, HttpStatus.c400_Bad_Request, "Bad char after HTTP-Version");
                    }

                case LF1: // LF
                    c = read(bb, px++, L);
                    if(c==LF) {
                        state= State.NL;
                        // goto NL
                    } else {
                        throw err(--px, HttpStatus.c400_Bad_Request, "LF is required after CR");
                    }

                // end of request line -------------------------------------------------------------

                case NL:  // next line (no folding). new name / end of head. test for CR/LF
                    c = read(bb, px, L);

                    // check total head size after each line. no need to be too precise.
                    if(pTotal+(px-p) > conf.requestHeadTotalMaxLength)
                        throw err(px, HttpStatus.c400_Bad_Request, "Request head total length > "+conf.requestHeadTotalMaxLength);

                    if(c==CR) {
                        px++;
                        state = State.LF3;
                        continue GOTO_STATE;
                    } else if(c==LF) {
                        px++;
                        state = State.DONE;
                        continue GOTO_STATE;
                    } else { // common

                        state=State.NAME;
                        // goto NAME
                    }

                case NAME:  // 1*toke-char COLON
                    px = collect(bb, p0=px, L, _CharDef.Http.tokenChars, nameMax);
                    c = bb[px]; // won't fail after collect(). the 1st non token-char.

                    if(px-p0==0) // 1st char is not token-char. could be WSP (first header after request line)
                        throw err(px, HttpStatus.c400_Bad_Request, "Invalid header name");
                    if(c!=COLON)
                    {
                        if(wsp(c)) // WSP after name is explicitly forbidden
                            throw err(px, HttpStatus.c400_Bad_Request, "White space not allowed after header name");
                        else  // other weird char
                            throw err(px, HttpStatus.c400_Bad_Request, "COLON expected after header name");
                    }

                    currHeaderName = _KnownHeaders.lookup(ba.reset(bb, p0, px-p0));
                    currHeaderX = (currHeaderName!=null); // usually true
                    if(!currHeaderX) // not a well-known header, (or known header in wrong case)
                        currHeaderName = string(bb, p0, px);

                    px++; // consume COLON

                    if(ImplConn.PREV_HEADERS)
                    {
                        px = matchPrevHeader(currHeaderName, currHeaderX, prevHeaders, headers, bb, p0=px, L);
                        if(px>p0) // match. header saved. px advanced after CR LF.
                        {
                            state = State.NL;
                            continue GOTO_STATE;
                        }
                    }

                    state = State.VALUE;
                    // goto VALUE

                case VALUE: // *value-char
                    px = collect(bb, p0=px, L, _CharDef.Http.headerValueChars, conf.requestHeadFieldMaxLength);
                    // next char should be CR/LF

                    // rfc7230$3.2 - OWS field-value OWS
                    String value = trim_string(bb, p0, px); // usually there's a leading SP
                    // note: even if value is empty, we'll include it in the `headers`.
                    // a header present with an empty value can be different from a header missing. see `Accept`

                    if(currHeaderValue==null)
                        currHeaderValue = value;
                    else // previous obs-fold. rare. "sender MUST NOT" use obs-fold.
                    {
                        // obs-fold is still not clearly defined. see thread
                        //     https://lists.w3.org/Archives/Public/ietf-http-wg/2014OctDec/0788.html
                        // we follow Simon's formulation. FWS is trimmed. field-ows is replaced by one SP.
                        if(currHeaderValue.isEmpty())
                            currHeaderValue = value;
                        else if(!value.isEmpty())
                            currHeaderValue = currHeaderValue + ' ' + value;
                    }


                    state=State.CR2;
                    // goto CR2

                case CR2: // CR | LF
                    c = read(bb, px++, L);
                    if(c==CR) {
                        state= State.LF2;
                        // goto LF2
                    } else if(c==LF) {
                        px--;
                        state= State.LF2;
                        // goto LF2
                    } else {  // some really weird char
                        throw err(--px, HttpStatus.c400_Bad_Request, "Invalid char in header value");
                    }

                case LF2: // LF
                    c = read(bb, px++, L);
                    if(c==LF) {
                        state= State.POST_LF2;
                        // goto POST_LF2
                    } else {
                        throw err(--px, HttpStatus.c400_Bad_Request, "LF is required after CR");
                    }

                case POST_LF2:
                    // we need to peek a char to see if there's line folding ahead
                    c = read(bb, px, L);
                    if(wsp(c)){ // line folding. obsolete. continue VALUE
                        px++;
                        state= State.VALUE;
                        continue GOTO_STATE;
                    } else {  // not line folding. value ends.

                        if(currHeaderX)
                        {
                            String oldValue = headers.xPut(currHeaderName, currHeaderValue);
                            if(oldValue!=null)   // headers with same name
                                headers.xPut(currHeaderName, oldValue + ", " + currHeaderValue);
                        }
                        else
                        {
                            String oldValue = headers.put(currHeaderName, currHeaderValue);
                            if(oldValue!=null)   // headers with same name
                                headers.put(currHeaderName, oldValue +", "+ currHeaderValue);
                        }
                        currHeaderName = currHeaderValue = null;

                        state= State.NL;
                        continue GOTO_STATE;
                    }

                // end of name-value -----------------------------------------------------

                case LF3 :  // LF
                    c = read(bb, px++, L);
                    if(c==LF) {
                        state= State.DONE;
                        // goto DONE
                    } else {
                        throw err(--px, HttpStatus.c400_Bad_Request, "LF is required after CR");
                    }

                case DONE:
                    // not much to do here. only a syntax parser.
                    return px;  // head is done

                default:
                    throw new AssertionError();

            } // switch state
        } // GOTO_STATE: while(true)
    } // parse


    // it's very likely that the header is the same as in the last request. optimize for that

    // return p0 if no match; otherwise, return px after CR LF
    static int matchPrevHeader(String currHeaderName, boolean currHeaderX, HeaderMap prevHeaders, HeaderMap headers,
                        byte[] bb, final int p0, int L)
    {
        if(prevHeaders==null) return p0;
        if(!currHeaderX) return p0;
        String prevValue = prevHeaders.xGet(currHeaderName);
        if(prevValue==null) return p0;

        // match if bb[p0...] contains exactly:  *WSP prevValue [CR] LF non-WSP

        int px = p0;
        // skip leading spaces
        while(px<L && wsp(bb[px])) px++;

        int V = prevValue.length();
        if(px+V+3>L) return p0;  // not enough bytes to peek

        for(int i=0; i<V; i++)
            if( (byte)prevValue.charAt(i) != bb[px++] )
                return p0;

        byte c = bb[px++];
        if(c!=LF && !(c==CR && bb[px++]==LF))
            return p0;

        if(wsp(bb[px])) return p0;  // line folding

        String v = headers.xPut(currHeaderName, prevValue);
        if(v!=null) // unlikely. back off.
        {
            headers.xPut(currHeaderName, v);
            return p0;
        }

        // match! CRLF is consumed
        return px;
    }

    // match: "GET "
    static boolean matchGET(byte[] bb, int p0)
    {
        long x = _Util.bytes2long(bb, p0, p0+4);

        //     G  E  T  SP
        x ^= 0x47_45_54_20L;
        return x==0;
    }

    // match: "HTTP/1.1" or "HTTP/1.0"
    // return 1 or 0. may throw
    static byte matchVersion(byte[] bb, int p0) throws ParseError
    {
        long x = _Util.bytes2long(bb, p0, p0+8);

        //     H  T  T  P  /  1  .  1
        x ^= 0x48_54_54_50_2F_31_2E_31L ;
        if(x==('1'^'1'))  // most cases
            return 1;
        if(x==('1'^'0'))
            return 0;

        // does it match "HTTP/*.*"
        if( 0L != (x&0xFF_FF_FF_FF_FF_00_FF_00L))
            throw errBadVersion(p0+8);

        // version seems good, but we don't support it
        throw err(p0+8, HttpStatus.c505_HTTP_Version_Not_Supported,
            "Only supports HTTP/1.0 and HTTP/1.1");
    }


}
