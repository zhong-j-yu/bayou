package bayou.text;

import _bayou._tmp._ByteBufferUtil;
import _bayou._tmp._StreamIter;
import bayou.async.Async;
import bayou.bytes.ByteSource;
import bayou.util.End;
import bayou.util.Result;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

/**
 * A ByteSource encoding texts to bytes.
 */

public class TextByteSource implements ByteSource
{
    int bufferSize;
    CharsetEncoder encoder;
    _StreamIter<? extends CharSequence> charSrc;

    enum State{encoding, flush2, end, error, closed }
    State state;

    /**
     * Create a TextByteSource that encodes charSource to bytes.
     * @param bufferSize
     *        preferred buffer size for read()
     * @param encoder
     *        for encoding chars to bytes.
     *        CAUTION: CharsetEncoder is stateful and cannot be shared;
     *        create a new CharsetEncoder for each new TextByteSource.
     * @param charSource source of chars
     */
    // charSource must be non-blocking
    //     tho non-blocking, it can be expensive to iterate, so we only iterate on demand.
    // we expect encoder.encode() to return only OVERFLOW or UNDERFLOW. otherwise read() error.
    //   this is good if user wants to be strict. if user wants to be lenient, the encoder should be
    //   configured to ignore or replace on malformed-input and un-mappable-character.
    // must be new encoder. encoder cannot be reused.
    public TextByteSource(int bufferSize, CharsetEncoder encoder, Stream<? extends CharSequence> charSource)
    {
        this.bufferSize = bufferSize;
        this.encoder = encoder;
        this.charSrc = new _StreamIter<>(charSource);

        this.state = State.encoding;
    }

    /**
     * Create a TextByteSource that encodes charSource to UTF-8 bytes.
     */
    public TextByteSource(CharSequence... charSource)
    {
        this(
            TextHttpEntity.BUF_SIZE,
            TextHttpEntity.newEncoder(StandardCharsets.UTF_8),
            Stream.of(charSource)
        );
    }

    @Override
    public Async<ByteBuffer> read() throws IllegalStateException
    {
        try
        {
            return Result.success(read0());
        }
        catch (IllegalStateException e) // closed
        {
            throw e;
        }
        catch (Exception e)
        {
            return Result.failure(e);
        }
   }

    // sync interface. won't block.
    ByteBuffer read0() throws End, Exception
    {
        switch(state)
        {
            case encoding:
                break; // goto encoding

            case flush2:
                return flush2();

            case end:
                throw End.instance();

            case error: // unrecoverable
                throw new Exception("prev error");

            case closed:
                throw new IllegalStateException("closed");

            default:
                throw new AssertionError();
        }

        // state = encoding

        ByteBuffer output = ByteBuffer.allocate(bufferSize);

        try
        {
            read2(output);
        }
        catch (Exception e) // usually unrecoverable. (encoder is corrupt)
        {
            state = State.error;
            throw e;
        }

        if(output.position()==0)  // no byte produced.
        {
            assert state == State.end;
            throw End.instance();
        }
        else
        {
            // state: encoding/flush2/end
            output.flip();
            // the last output buffer may have lots of wasted room. shrink it.
            return _ByteBufferUtil.shrink(output, 0.75);
        }
    }

    CharSequence charSeq;
    int charSeqI;

    CharBuffer   charBuf;

    // return null if no more chars
    CharBuffer nextInput()
    {
        // naively we can just wrap next CharSequence with CharBuffer.wrap(charSeq).
        // but encoder is really slow on that type of CharBuffer.  ( 75MB/s)
        // it's much faster if we copy chars to a heap CharBuffer. (190MB/s)
        // also may reduce overhead if there are lots of tiny char sequences

        int capacity = bufferSize/2;

        if(charBuf==null) // first time
        {
            charBuf=CharBuffer.allocate(capacity);
            charBuf.limit(0);
        }

        if(charBuf.hasRemaining()) // prev leftover
            return charBuf;

        // get char sequences and copy to charBuf
        charBuf.clear();
        char[] charArr = charBuf.array();  // a little faster than operating on charBuf
        int pos = 0;
        while(true)
        {
            if(charSeq==null)
            {
                CharSequence next = charSrc.next();
                if(next!=null)
                {
                    charSeq = next;
                    charSeqI = 0;
                }
                else
                {
                    charBuf.position(pos);
                    charBuf.flip();
                    if(charBuf.hasRemaining())
                        return charBuf;
                    // no more chars
                    charBuf=null;
                    return null;
                }
            }

            int L1 = charSeq.length();
            int L2 = charSeqI + (capacity-pos);
            int Lm = Math.min(L1, L2);
            while(charSeqI<Lm)
                charArr[pos++] = charSeq.charAt(charSeqI++);

            if(L1<=L2)
                charSeq = null;

            if(L1>=L2)
            {
                charBuf.position(pos);
                charBuf.flip();
                return charBuf;
            }
        }
    }

    void read2(ByteBuffer output) throws CharacterCodingException
    {
        while(true)
        {
            CharBuffer input = nextInput();
            if(input==null) // no more chars
            {
                CoderResult result = encoder.encode(emptyCharBuf, output, true); // can not overflow
                assert result.isUnderflow();

                result = encoder.flush(output);
                if(result.isUnderflow()) // cool, all done
                    state = State.end; // output could be empty
                else if(result.isOverflow()) // very rare; need to flush again to a new buffer
                    state = State.flush2;
                else // impossible for flush()
                    result.throwException();

                return;
            }

            CoderResult result = encoder.encode(input, output, false);
            if(result.isOverflow())
            {
                assert input.hasRemaining();  // for next read
                return;
            }
            else if(result.isUnderflow())
            {
                // continue, encode next input
                // it's possible but rare that output is full here, so next round is futile.
            }
            else
            {
                result.throwException();
            }
        }
    }
    static final CharBuffer emptyCharBuf = CharBuffer.wrap(new char[0]);

    ByteBuffer flush2()
    {
        // prev flush() overflowed, need to do it again to a fresh buffer. this is very rare

        ByteBuffer output = ByteBuffer.allocate(32); // should be big enough
        CoderResult cr = encoder.flush(output);

        assert cr.isUnderflow(); // no way it can overflow again - the output buffer is huge
        assert output.position()>0; // flush() must produce some bytes, or it wouldn't overflow last time.

        state = State.end;

        output.flip();
        return output;
    }

    // skip()
    // can't skip here. we don't know how many chars to skip without actual encoding.
    // caller must read-and-discard. that involves overhead of encoding, not too much.

    @Override
    public Async<Void> close()
    {
        if(state==State.closed)
            return Async.VOID;
        state=State.closed;

        encoder = null;

        charSrc.close();
        charSrc = null;

        charSeq = null;
        charBuf = null;
        return Async.VOID;
    }
}
