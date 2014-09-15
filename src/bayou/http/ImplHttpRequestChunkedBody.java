package bayou.http;

import _bayou._tmp._ByteBufferUtil;
import _bayou._tmp._HexUtil;
import bayou.ssl.SslConnection;
import bayou.tcp.TcpConnection;
import bayou.util.OverLimitException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.ParseException;

class ImplHttpRequestChunkedBody extends ImplHttpRequestEntity.Body
{
    long maxDataLength;

    ImplHttpRequestChunkedBody(ImplConn hConn, ImplHttpRequest request)
    {
        super(hConn, request);

        maxDataLength = hConn.conf.requestBodyMaxLength;
    }

    Parser parser = new Parser();

    Exception err; // unrecoverable

    long chunkBytesRead;

    long totalBytesRead;

    @Override
    public ByteBuffer nb_read() throws Exception
    {
        if(err!=null) // prev error; nb_read() may be called after prev error by ImplConn to drain request body
            throw err;

        // pass through framing bytes, reach DATA or END
        if(parser.state!=Parser.State.DATA && parser.state!=Parser.State.END)
        {
            while(true)
            {
                ByteBuffer bb = hConn.nbConn.read(); // throws

                if(bb== TcpConnection.STALL) // need more framing bytes
                {
                    checkThroughput();
                    return TcpConnection.STALL;
                }

                if(bb== TcpConnection.TCP_FIN || bb== SslConnection.SSL_CLOSE_NOTIFY)
                    throw new IOException("connection closed before end of entity body");

                try
                {
                    parser.parse(bb);
                }
                catch(ParseException t)
                {
                    err = t;
                    // unrecoverable, input stream is corrupt.
                    // stateBody==2. later we'll try to drain request body before writing response,
                    // however read() will fail immediately, so response won't be written. ok. very bad client.
                    throw t;
                }
                finally
                {
                    if(bb.hasRemaining()) // very likely
                        hConn.nbConn.unread(bb);
                }

                if(parser.state == Parser.State.DATA)
                {
                    // check max body length imm after we see each chunk-size
                    if(totalBytesRead+parser.chunkSize > maxDataLength)
                    {
                        err = new OverLimitException("confRequestBodyMaxLength", maxDataLength);
                        throw err;
                        // this error is sticky, unrecoverable. we are not willing to read further.
                        // since request can't be drained, response can't be written.
                    }
                    break;
                }
                if(parser.state == Parser.State.END)
                {
                    request.stateBody = 3;
                    break;
                }
                // continue
            }
        }

        // DATA or END

        if(parser.state == Parser.State.END)
            return END;

        // errors below may be recoverable, hConn remains ok. caller may try read() again, tho unlikely it will.

        // DATA
        ByteBuffer bb = hConn.nbConn.read();

        if(bb== TcpConnection.STALL)
        {
            checkThroughput();
            return TcpConnection.STALL;
        }

        if(bb== TcpConnection.TCP_FIN || bb== SslConnection.SSL_CLOSE_NOTIFY)
            throw new IOException("connection closed before end of entity body");

        long bbBytes = (long)bb.remaining();  // >0
        long chunkBytesLeft = parser.chunkSize - chunkBytesRead;  // >0, because chunkSize>0

        if(bbBytes<chunkBytesLeft) // more chunk data to come
        {
            chunkBytesRead += bbBytes;
            totalBytesRead += bbBytes;
            return bb;
        }

        // chunk-data ends. prepare for next chunk
        parser.afterChunkData();
        chunkBytesRead = 0;
        totalBytesRead += chunkBytesLeft;

        // bb contains exactly chunk data, nothing more. probably uncommon
        if(bbBytes==chunkBytesLeft)
            return bb;

        // more bytes available than current chunk-data needs. probably common.
        ByteBuffer slice = _ByteBufferUtil.slice(bb, (int)chunkBytesLeft);  // the long->int narrowing is legit
        // bb contains extra bytes, save as leftover, for next read
        hConn.nbConn.unread(bb);
        return slice;
        // we may consider to try to avoid slice(), by parsing the extra bytes; if we are lucky,
        // they are all framing bytes (say, CRLF after DATA). then we don't need to slice().
        // unsure if that's helpful in practice - unlikely `bb`s preserve boundaries of sender write()s.
    }

    void checkThroughput() throws Exception // (when read stalls)
    {
        long time = System.currentTimeMillis() - t0;
        if(time> 10_000) // don't check in the beginning
        {
            long minRead = minThroughput * time / 1000;
            if(totalBytesRead < minRead)
                throw new IOException("Client upload throughput too low");
        }
        // we only count chunk-data bytes, not any framing bytes
    }

    static class Parser // parser for framing bytes - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    {
        static final int CR='\r', LF='\n';

        enum State { HEX, LF1, DATA, CR2, LF2, TRAILER, LF3, POST_LF3, LF4, END }
        State state = State.HEX;
        //boolean error;

        int nDigit;
        long chunkSize;

        void afterChunkData()
        {
            state = State.CR2; // seek CRLF after chunk-data
            nDigit=0;
            chunkSize=0;
        }

        void parse(ByteBuffer bb) throws ParseException
        {
            while(bb.hasRemaining())
            {
                int b = 0xff & bb.get();
                switch(state)
                {
                    case HEX:
                        int d = _HexUtil.hex2int[b];
                        if(d==-1) // not a HEX.
                        {
                            if(nDigit ==0)
                                throw new ParseException("invalid chunked coding",0);

                            // HEX should be followed imm by CRLF. that's the case in practice.
                            // nobody uses chunk-extension(to be deprecated). no optional whitespace either.
                            if(b!=CR)
                                throw new ParseException("invalid chunked coding: expect CR after HEX",0);

                            // we got 1 or more hex digits, and chunkSize is set
                            if(chunkSize>0)
                                state = Parser.State.LF1;
                            else // size=0, last-chunk. to seek CRLF CRLF
                                state = Parser.State.LF3;
                        }
                        else // a HEX
                        {
                            nDigit++;
                            if(nDigit >10) // hard syntactic limit here
                                throw new ParseException("chunk-size bigger than 2^40",0);  // 1 TB
                            chunkSize = chunkSize << 4 | (long)d;
                        }

                        break;

                    case LF1:
                        expectLF(b);

                        state = Parser.State.DATA;
                        return;

                    // when DATA is reached, caller consume all chunk-data, then call afterChunkData() before parse()

                    case DATA:
                        throw new AssertionError();

                    case CR2:
                        if(b!=CR)
                            throw new ParseException("invalid chunked coding: CRLF expected after chunk-data",0);
                        state = Parser.State.LF2;
                        break;

                    case LF2:
                        expectLF(b);
                        state = Parser.State.HEX;
                        break;

                    // after we see a 0 chunk-size, we seek CRLF CRLF for the end. trailer is ignored.
                    // actually as soon as we see the 0 chunk-size, we could notify caller that stream is ended.
                    // we delay that after the final CRLF CRLF is reached, to deplete request body.

                    case TRAILER:
                        if(b==CR)
                            state = Parser.State.LF3;
                        // skip any non CR, don't care. b could be a lone LF, but that's unlikely,
                        // since we have previously learned that client does use CR
                        break;

                    case LF3:
                        expectLF(b);
                        state = Parser.State.POST_LF3;
                        break;

                    case POST_LF3:
                        if(b==CR)  // CRLF CR. this is common
                            state = Parser.State.LF4;
                        else       // not back-to-back CRLF CRLF. trailer stuff. this is rare
                            state = Parser.State.TRAILER;
                        break;

                    case LF4:
                        expectLF(b);
                        state = Parser.State.END;
                        return;

                    case END:
                        throw new AssertionError();

                    default:
                        throw new AssertionError();

                } // switch(state)

            } // while(bb.hasRemaining())

            // not DATA or END. bb is exhausted, need more bytes to parse framing bytes.
        }

        static void expectLF(int b) throws ParseException
        {
            if(b!=LF)
                throw new ParseException("invalid chunked coding: LF expected after CR",0);
        }
    } // Parser
}
