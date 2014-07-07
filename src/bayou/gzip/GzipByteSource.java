package bayou.gzip;

import _bayou._async._Asyncs;
import _bayou._tmp._ByteBufferUtil;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.bytes.ByteSource;
import bayou.util.Result;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Compresses the origin ByteSource with gzip.
 * <p>
 *     The bytes of this source will be in the gzip format.
 * </p>
 */
public class GzipByteSource implements ByteSource
{
    // compressing text files: about 50MB/s (input)

    // (GunzipByteSource: it is more complex, we need to parse gzip headers.
    //    do it later. it's useful for decode gzip-ed request body. )


    // can't use direct ByteBuffer, because currently Deflater API requires byte[].
    // even if the API accepts ByteBuffer, using pooled direct ByteBuffer doesn't save much,
    //   since compression is very slow anyway.

    SourceWrapper origin;
    int compressionLevel;

    enum State{gzipHeader, reading, deflating, gzipTrailer, gzipDone, closed }
    State state;


    /**
     * Create a GzipByteSource, which compresses the origin source with gzip.
     * <p>
     *     `compressionLevel` can be 0-9. For on-the-fly gzip, consider compressionLevel=1,
     *      which is faster, with pretty good compression ratio.
     * </p>
     */
    public GzipByteSource(ByteSource origin, int compressionLevel)  // caller assumes no throw
    {
        this.origin = new SourceWrapper(origin);
        this.compressionLevel = compressionLevel;

        this.state = State.gzipHeader;
    }


    // see graph [9-28-2012] [4]
    //
    // from observation, Deflater has big internal buffers. it'll consume lots of input text
    // without producing any output; then when input>100K, it starts to produce outputs (~30K).
    // once deflater starts to produce, we drain the output asap to the client.
    //
    // we don't force flush; if inputs come in small chunks over extended time,
    // client won't detect progress promptly.


    // created on 1st read
    Deflater deflater;
    CRC32 crc;
    // Deflater uses out-of-vm resources; remember to call close() to promptly free resources.

    byte[] outputBuffer;
    static final int outputBufferCap = 4*1024;
    // note: deflater produces output in 20-40K blocks.

    boolean originEof;


    static final byte[] gzip_header = {31, -117, 8, 0, 0, 0, 0, 0, 0, 0};

    @Override
    public Async<ByteBuffer> read() throws IllegalStateException
    {
        switch(state)
        {
            case gzipHeader:

                this.deflater = new Deflater(compressionLevel, true);
                this.crc = new CRC32();

                state = State.reading;

                return Result.success(ByteBuffer.wrap(gzip_header));

            case reading:
                return read_deflate_loop();

            case deflating:

                ByteBuffer result = deflate();
                if(result!=null)
                    return Result.success(result);
                else // no output, needs new input
                    return read_deflate_loop();

            case gzipTrailer:

                byte[] gzip_trailer = new byte[8];
                put4(gzip_trailer, 0, crc.getValue());
                // rfc: size of the original (uncompressed) input data modulo 2^32
                put4(gzip_trailer, 4, deflater.getBytesRead());

                state = State.gzipDone;

                return Result.success(ByteBuffer.wrap(gzip_trailer));

            case gzipDone:
                return _Util.EOF;

            case closed:
                throw new IllegalStateException("closed");

            default:
                throw new AssertionError();
        }
    }

    Async<ByteBuffer> read_deflate_loop()
    {
        // in case of origin.read() error, it may be recoverable, state==reading
        return _Asyncs.scan(origin::read,
            obb -> {
                // set input
                // need input in byte[]. copy ByteBuffer to byte[]
                // obb may be based on a byte[] internally, we could hack into it to use that array directly.
                // but it won't have much saving, since compression is so slow anyway.

                byte[] array = new byte[obb.remaining()]; // SourceWrapper guarantees that it's not too huge
                obb.get(array);

                crc.update(array);
                deflater.setInput(array);

                state = State.deflating;
                ByteBuffer result = deflate();
                if (result != null)
                    return result;
                // else, no output. needs more inputs.
                return null;
                // note: outputBuffer is kept
            },
            end ->
            {
                originEof = true;
                deflater.finish();

                state = State.deflating;
                ByteBuffer result = deflate();
                assert result != null;
                return result;
            });
    }

    // next state is set before returning
    // return null if need more inputs. (state==reading)
    ByteBuffer deflate()
    {
        if(outputBuffer==null)
            outputBuffer = new byte[outputBufferCap];

        int outputLength = deflater.deflate(outputBuffer, 0, outputBufferCap);  // no flush
        // outputBuffer to be served if outputLength>0

        // decide next step based on various flags

        // deflater.needsInput() actually means last input is emptied; it can return true
        // even if finish() was called. we need to check `!originEof` for *really needsInput*.

        // we have 3 blocks here: [A] [B] [C]
        // [A]/[C1] is before originEof, [B]/[C2] is after originEof.
        // [A] and [B] cannot both be true, so order between them isn't important.
        //     finished()->originEof  =>  not( finished() and !originEof )

        if(!originEof && deflater.needsInput()) // [A]
        {
            if(outputLength==0)  // [1]
            {
                state = State.reading;
                return null;
                // the only "null" return. outputBuffer is kept for next deflate()
                // in all other cases, outputBuffer is served
            }
            else if(outputLength== outputBufferCap) // [2], outputBuffer full
            {
                // state==deflating
            }
            else  // [3], outputBuffer not full, not empty.
            {
                state = State.reading;
            }

            // in [1],we must fetch more input before trying deflate() again.
            // otherwise, 2 valid choices for next step: read()-setInput(), or deflate().
            // to choose better, we speculate that (not backed by javadoc)
            // [2] if deflate() filled outputBuffer full, next deflate() will very likely produce more output.
            //     so we'll try deflate() again without read()-setInput() first, to drain output asap.
            //     (occasionally, this deflate() has drained output, next deflate() produces 0 bytes)
            // [3] if deflate() didn't fill outputBuffer full, next deflate() will very likely produce 0 bytes.
            //     so we'll read()-setInput() first, before deflate() again.

            // a typical flow:
            //   [1] ... [1]  [2] ... [2]  [3]
            //   [1] ... [1]  [2] ... [2]  [3]

        }
        else if(deflater.finished()) // [B]
        {
            // last bytes of deflater output. outputLength>0 should be true. outputBuffer can be full occasionally.
            // a typical flow
            //   [1] ... [1] (EOF) [C2] ... [C2] [B]

            state = State.gzipTrailer;
        }
        else // [C]
        {
            // not finished, and not needing input.
            // [C1] before originEof.
            //      probably curr input is huge, deflater hasn't consumed it up. not common in our usage.
            // [C2] after originEof.
            //      not finished because outputBuffer isn't big enough for draining. common.
            // in either case, outputBuffer should be full here. at least outputLength>0 must be true (or dead loop)
            // in the next step we'll deflate() again.

            // state==deflating
        }

        // we made some observations/assumptions that are not blessed by javadoc.
        // even if they are broken, our code should still work fine, per formal contracts.

        // serve whatever output we have now to client. outputLength>0 should be true.
        ByteBuffer bb = ByteBuffer.wrap(outputBuffer, 0, outputLength);
        outputBuffer = null;
        return bb;
    }

    // put 4 lower bytes into bb
    static void put4(byte[] bb, int off, long v)
    {
        bb[off  ] = (byte)(v    );
        bb[off+1] = (byte)(v>> 8);
        bb[off+2] = (byte)(v>>16);
        bb[off+3] = (byte)(v>>24);
    }

    // skip()
    // don't know how to skip compressed stream

    @Override
    public Async<Void> close()
    {
        if(state==State.closed)
            return Async.VOID;
        state = State.closed;

        origin.close();
        origin=null;

        if(outputBuffer!=null)
            outputBuffer=null;

        if(deflater!=null)
        {
            deflater.end(); // free resources
            deflater=null;
            crc=null;
        }
        return Async.VOID;
    }






    // used internally. make sure read() doesn't return huge data
    static class SourceWrapper implements ByteSource
    {
        ByteSource origin;
        SourceWrapper(ByteSource origin)
        {
            this.origin = origin;
        }

        ByteBuffer hoard;

        @Override
        public Async<ByteBuffer> read()
        {
            if(hoard!=null)
                return Result.success(serveHoard());

            return origin.read().map(result -> {
                hoard = result;
                return serveHoard();
            });

        }

        // if hoard is huge, serve smaller sub-sequence
        static final int MAX = 32*1024; // Deflater can buffer ~100K inputs
        ByteBuffer serveHoard()
        {
            ByteBuffer result;
            if(hoard.remaining()<=MAX)
            {
                result = hoard;
                hoard = null;
            }
            else
            {
                result = _ByteBufferUtil.slice(hoard, MAX);
            }

            return result;
        }


        @Override
        public Async<Void> close()
        {
            if(hoard!=null)
            {
                hoard = null;
            }

            return origin.close();
        }
    }


}
