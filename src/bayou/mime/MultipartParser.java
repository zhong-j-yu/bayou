package bayou.mime;

import _bayou._async._Asyncs;
import _bayou._bytes._DelimitedByteSource;
import _bayou._tmp._ByteBufferUtil;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.bytes.ByteSource;
import bayou.bytes.PushbackByteSource;
import bayou.util.End;
import bayou.util.Result;

import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.Map;

/**
 * Parses a multipart body.
 * <p>
 *     Given a sequence of bytes in the multipart format
 *     (see <a href="https://www.ietf.org/rfc/rfc2046.txt">RFC2046</a>),
 *     this class parses it into {@link MultipartPart parts}.
 * </p>
 */
public class MultipartParser
{
    PushbackByteSource source;
    ByteBuffer DELIM;  // CRLF--boundary

    // in the old rfc 1341, the syntax is simpler
    //    preamble
    //    1*(
    //      delimiter CRLF
    //      body-part
    //    )
    //    delimiter --
    //    epilogue
    //
    // but in rfc 2046, the syntax is messier, to make the leading CRLF optional.
    // we simply prepend CRLF to the origin bytes, to achieve the prev simpler syntax.
    //     CRLF [preamble CRLF]  =>  preamble CRLF
    //
    // rfc 2046 also allows extra spaces : delimiter (SP|HT)* CRLF    we currently don't support that.
    //
    // we don't complain if there are 0 parts.

    /**
     * Create a MultipartParser.
     * <p>
     *     The `origin` should be a sequence of bytes in the multipart format, using `boundary`.
     * </p>
     */
    public MultipartParser(ByteSource origin, CharSequence boundary)
    {
        this.DELIM = _ByteBufferUtil.wrapLatin1("\r\n--" + boundary);

        // prepend CRLF to origin bytes
        PushbackByteSource crlf_origin = new PushbackByteSource(origin);
        crlf_origin.unread(_ByteBufferUtil.wrapLatin1("\r\n"));

        _DelimitedByteSource delimSrc = new _DelimitedByteSource(crlf_origin, DELIM);
        this.source = new PushbackByteSource(delimSrc);
    }

    enum State{ preamble, delim, delimCR, partHead, partBody, delimDash, epilogue, ERROR, CLOSED }
    State state = State.preamble;

    Rfc822HeadParser headParser;

    Exception err; // ParseException or OverLimitException
    Void err(Exception err)
    {
        state = State.ERROR;
        this.err = err;
        closeOrigin();

        headParser=null;
        return null;
    }
    Void err(String parseErrMsg)
    {
        return err(new ParseException(parseErrMsg,0));
    }

    PartImpl currPart;
    void killCurrPart()
    {
        if(currPart!=null)
        {
            currPart.kill();
            currPart=null;
        }
    }

    void closeOrigin() // we'll close origin as soon as it's no longer needed by us
    {
        if(source !=null)
        {
            source.close();
            source =null;
        }
    }

    // conf methods can be set before each nextPart() call
    // if these limits are exceeded, OverLimitException.
    // REFACTOR CAUTION: these names are referenced in other places as string


    int maxHeaderNameBytes = 64;
    /**
     * Max length of part header names.
     * <p><code>
     *     default: 64
     * </code></p>
     * <p>
     *     This method can be invoked before each {@link #getNextPart()}.
     * </p>
     * @return `this`
     */
    public MultipartParser maxHeaderNameBytes(int maxHeaderNameBytes)
    {
        this.maxHeaderNameBytes=maxHeaderNameBytes;
        return this;
    }

    int maxHeaderValueBytes = 256;
    /**
     * Max length of part header values.
     * <p><code>
     *     default: 256
     * </code></p>
     * <p>
     *     This method can be invoked before each {@link #getNextPart()}.
     * </p>
     * @return `this`
     */
    public MultipartParser maxHeaderValueBytes(int maxHeaderValueBytes)
    {
        this.maxHeaderValueBytes=maxHeaderValueBytes;
        return this;
    }

    int maxHeadTotalBytes = 1024;
    /**
     * Max length of the head section of parts.
     * <p><code>
     *     default: 1024
     * </code></p>
     * <p>
     *     This method can be invoked before each {@link #getNextPart()}.
     * </p>
     * @return `this`
     */
    public MultipartParser maxHeadTotalBytes(int maxHeadTotalBytes)
    {
        this.maxHeadTotalBytes=maxHeadTotalBytes;
        return this;
    }

    /**
     * Get the next part.
     * <p>
     *     This is an async action. If there are no more parts, the action fails with
     *     {@link End}.
     * </p>
     */
    // upon calling this method, the body of the prev part is closed and should not be read again.
    // parser errors will be wrapped in ParseException
    // part header name/values are Latin1 charset
    // many parts may be immediately available.
    public Async<MultipartPart> getNextPart()
    {
        killCurrPart();

        switch(state)
        {
            case epilogue:
                return End.async();
            case ERROR:  // prev unrecoverable parser error. user should not have called getNextPart() again
                return Result.failure(new IllegalStateException("prev error", err));
            case CLOSED:
                throw new IllegalStateException("closed");
        }

        return _Asyncs.scan(source::read,
            this::parseReadResult,
            eof ->
            {
                // origin reached EOF while parsing
                err("premature EOF");
                throw err;
            });
    }

    /**
     * Close this parser.
     * <p>
     *     This method will also close the `origin` ByteSource.
     * </p>
     */
    // why do we need this method? user can simply close `origin`
    public void close()
    {
        if(state == State.CLOSED)
            return;
        state = State.CLOSED;

        killCurrPart();

        closeOrigin();

        headParser = null;
    }


    // return null  : insufficient data, read again
    MultipartPart parseReadResult(ByteBuffer bb) throws End, Exception
    {
        if(state == State.preamble || state == State.partBody )
        {
            if(bb==DELIM)
            {
                // preamble or prev part body is ended
                state = State.delim;
                return null;
            }
            else
            {
                // skip bytes in preamble or prev part body
                return null;
            }
        }

        // state: delim, delimCR, partHead, delimDash

        if(bb==DELIM)
        {
            err("unexpected delimiter @" + state);
            throw err;
        }

        parseAfterDelim(bb);  // origin can be closed

        if(bb.hasRemaining() && source !=null) // very likely
            source.unread(bb);

        switch(state)
        {
            case ERROR:
                throw err;

            case epilogue:
                throw End.instance();

            case partBody:
                currPart = new PartImpl();
                currPart.headers = headParser.headers;
                headParser = null;
                return currPart;

            default:
                return null; // still parsing, need more bytes
        }
    }

    Void parseAfterDelim(ByteBuffer bb)
    {
        if(state == State.partHead)
            return parseHead(bb);

        while(bb.hasRemaining())
        {
            int b = 0xff & bb.get();
            switch(state)
            {
                case delim:
                    if(b=='-')
                        state = State.delimDash;
                    else if(b=='\r')
                        state = State.delimCR;
                    else
                        return err("CR or DASH expected after delimiter");
                    break;

                case delimDash:
                    if(b!='-')
                        return err("DASH expected after delimiter DASH");

                    state = State.epilogue;
                    closeOrigin();
                    return null;

                case delimCR:
                    if(b!='\n')
                        return err("LF expected after CR");

                    state = State.partHead;
                    headParser = new Rfc822HeadParser(maxHeaderNameBytes, maxHeaderValueBytes, maxHeadTotalBytes);
                    return parseHead(bb);

                default:
                    throw new AssertionError("unexpected state: "+ state);
            }
        }
        // need more bytes
        return null;
    }

    Void parseHead(ByteBuffer bb)
    {
        headParser.parse(bb);

        if(headParser.state==Rfc822HeadParser.State.ERROR)
            err(headParser.err);
        else if(headParser.state==Rfc822HeadParser.State.END)
            state = State.partBody;
        // otherwise still in head

        return null;
    }








    class PartImpl implements MultipartPart
    {
        Map<String,String> headers;

        @Override
        public Map<String, String> headers()
        {
            return headers;
        }

        boolean killed;
        void kill()
        {
            killed = true;
            if(body!=null)
                body.close();
        }

        PartBody body;

        @Override
        public ByteSource body()
        {
            if(killed) // getBody() invoked after next part is requested, or parser closed. programming error.
                throw new IllegalStateException("closed");

            if(body!=null)
                throw new IllegalStateException("getBody() can only be called once");

            return body = new PartBody();
        }
    }
    class PartBody implements ByteSource
    {
        int status; //[0] normal [1] eof [2] closed

        // skip()
        // can't skip without checking DELIM

        @Override
        public Async<Void> close()
        {
            status = 2;
            return Async.VOID;
        }

        @Override
        public Async<ByteBuffer> read() throws IllegalStateException
        {
            if(status==2)
                throw new IllegalStateException("closed");

            if(status==1)
            {
                return _Util.EOF;
            }

            // next DELIM in origin as EOF for this part
            return source.read()
                .catch_(End.class, end -> {
                    status = 2;
                    err("premature EOF"); // stream corrupt, parser error
                    throw err;
                }).map(input -> {
                    if(input!=DELIM)
                        return input;

                    state = State.delim;
                    status = 1;
                    throw End.instance();
                });
        }

    }


}
