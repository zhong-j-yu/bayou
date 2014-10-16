package bayou.gzip;

import _bayou._async._Asyncs;
import bayou.async.Async;
import bayou.bytes.ByteSource;
import bayou.util.End;
import bayou.util.Result;
import bayou.util.function.FunctionX;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

/**
 * Decompress the origin ByteSource that's compressed in gzip format.
 */
public class GunzipByteSource implements ByteSource
{
    static final int outputBufferCap = 4*1024;

    ByteSource origin;
    ZipException error; // persistent, unrecoverable.
    boolean closed;

    /**
     * Create a GunzipByteSource that decompresses the `origin` source in gzip format.
     */
    public GunzipByteSource(ByteSource origin)
    {
        this.origin = origin;
    }

    /**
     * Read the next chunk of decompressed bytes.
     * <p>
     *     If the origin source is not in valid gzip format, the read() action fails with
     *     {@link java.util.zip.ZipException}.
     * </p>
     */
    @Override
    public Async<ByteBuffer> read() throws IllegalStateException
    {
        if(closed)
            throw new IllegalStateException("closed");

        if(error!=null)
            return Async.failure(error);

        return read0().transform(persistError);
    }

    Async<ByteBuffer> read0()
    {
        ByteBuffer bb;
        try
        {
            bb = unzip(); // throws
        }
        catch (Exception e)
        {
            return Async.failure(e);
        }

        if(bb!=null)
            return Async.success(bb);

        return _Asyncs.scan(origin::read, this::unzip, endScanner);
    }

    static final FunctionX<End, ByteBuffer> endScanner = end->
    {
        throw new ZipException("premature EOF from origin");
    };

    FunctionX<Result<ByteBuffer>, Async<ByteBuffer>> persistError = result->
    {
        Exception ex = result.getException();
        if(ex instanceof ZipException)
        {
            clear();
            error = (ZipException)ex;
        }
        return result;
    };










    GzipHeaderParser headerParser = new GzipHeaderParser();

    Inflater inflater;
    CRC32 crc;
    byte[] array;
    int off;
    int len;

    ByteBuffer trailer;

    // return null if needs more input
    ByteBuffer unzip() throws ZipException, End
    {
        if(inflater!=null)
            return inflateBody();

        if(headerParser!=null)
            return null; // need more bytes for header

        if(trailer!=null)
            return null;

        throw End.instance();
    }
    ByteBuffer unzip(ByteBuffer obb) throws ZipException, End
    {
        if(inflater!=null)
            return inflateBody(obb);

        if(headerParser!=null)
            return parseHeader(obb);

        if(trailer!=null)
            return parseTrailer(obb);

        // shouldn't reach here
        throw new AssertionError();
    }
    ByteBuffer parseHeader(ByteBuffer obb) throws ZipException, End
    {
        headerParser.parse(obb); // throws
        if(!headerParser.done()) // need more bytes for head
            return null;

        headerParser =null;

        inflater = new Inflater(true);
        crc = new CRC32();
        return inflateBody(obb);
    }
    ByteBuffer inflateBody(ByteBuffer obb) throws ZipException, End
    {
        len = obb.remaining();
        if(len==0)
            return null;

        if(obb.hasArray())
        {
            array = obb.array();
            off = obb.arrayOffset()+ obb.position();
        }
        else // copy obb to array.
        {
            array = new byte[len];
            obb.get(array);
            off = 0;
        }

        inflater.setInput(array, off, len);
        return inflateBody();
    }
    ByteBuffer inflateBody() throws ZipException, End
    {
        byte[] outputBuffer = new byte[outputBufferCap];
        int outputLength;
        try
        {
            outputLength = inflater.inflate(outputBuffer); // throws
        }
        catch (DataFormatException e)
        {
            throw new ZipException(e.toString());
        }

        if(outputLength>0)
        {
            crc.update(outputBuffer, 0, outputLength);
            return ByteBuffer.wrap(outputBuffer, 0, outputLength);
        }

        byte[] array = this.array;
        this.array = null;

        if(inflater.finished())
        {
            int r = inflater.getRemaining();
            ByteBuffer obb = ByteBuffer.wrap(array, off + len - r, r);

            trailer = ByteBuffer.wrap(GzipByteSource.trailer(crc.getValue(), inflater.getBytesWritten()));
            inflater.end();
            inflater=null;
            crc=null;

            return parseTrailer(obb);
        }

        if(inflater.needsDictionary()) // not handled now
            throw new ZipException("inflater.needsDictionary()");

        if(inflater.needsInput())
            return null;

        // uh? shouldn't reach here.
        throw new IllegalStateException();
    }
    ByteBuffer parseTrailer(ByteBuffer obb) throws ZipException, End
    {
        while(obb.hasRemaining() && trailer.hasRemaining())
        {
            if(obb.get()!=trailer.get())
                throw new ZipException("Invalid gzip trailer");
        }

        if(trailer.hasRemaining()) // needs more obb
            return null;

        trailer = null;
        throw End.instance();
    }




    @Override
    public Async<Void> close()
    {
        if(closed)
            return Async.VOID;
        closed=true;
        clear();
        return Async.VOID;
    }

    void clear() // could be called multiple times
    {
        if(origin!=null)
            origin.close();
        if(inflater!=null)
            inflater.end();

        origin=null;
        error=null;
        headerParser=null;
        inflater=null;
        crc=null;
        array=null;
        trailer=null;
    }


}
