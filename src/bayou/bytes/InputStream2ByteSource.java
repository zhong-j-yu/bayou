package bayou.bytes;

import _bayou._log._Logger;
import _bayou._tmp._Exec;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.util.End;

import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Converts an InputStream to a ByteSource.
 * <p>
 *     This is useful to use a legacy InputStream in an async, non-blocking app.
 * </p>
 * <p>
 *     CAUTION: creating an InputStream in the first place might be a blocking action;
 *     keep that in mind when building a non-blocking app.
 * </p>
 */
public class InputStream2ByteSource implements ByteSource
{
    // not thread safe
    // todo: we are using a default executor for blocking actions. allow user to supply the executor?

    InputStream inputStream;
    int bufferSize;
    long toSkip;
    boolean closed;

    // note: opening of an input stream is usually a blocking action.
    //       that should be reminded in an non-blocking app

    /**
     * Creating a ByteSource based on the InputStream.
     * @param bufferSize
     *        preferred buffer size for read
     */
    public InputStream2ByteSource(InputStream inputStream, int bufferSize)
    {
        this.inputStream = inputStream;
        this.bufferSize = bufferSize;
    }

    /**
     * Read the next chunk of bytes.
     *
     * @throws IllegalStateException
     *         if this source is closed.
     */
    @Override
    public Async<ByteBuffer> read() throws IllegalStateException
    {
        if(closed)
            throw new IllegalStateException("closed");

        // cancel -> interrupt. requires `inputStream.read` be responsive to interrupts
        return Async.execute(this::readInputStream);
    }



    /**
     * Try to skip forward `n` bytes.
     *
     * @throws IllegalStateException
     *         if this source is closed.
     */
    @Override
    public long skip(long n) throws IllegalArgumentException, IllegalStateException
    {
        _Util.require(n >= 0, "n>=0");

        if(closed)
            throw new IllegalStateException("closed");

        // we cannot call inputStream.skip() here - it might block.
        // so we only promise to skip; actual skipping is done at read() time

        toSkip += n;  // may skip beyond EOF.
        return n;
    }


    /**
     * Close this source.
     */
    @Override
    public Async<Void> close()
    {
        if(closed)
            return Async.VOID;
        closed = true;

        closeAsync(inputStream);
        inputStream = null;
        return Async.VOID;
    }

    static void closeAsync(final InputStream inputStream)
    {
        _Exec.executeB(() -> {
            try
            {
                inputStream.close(); // assume blocking
            }
            catch (Exception e) // not supposed to happen
            {
                _Logger.of(InputStream2ByteSource.class).error("%s", e);
            }
        });
    }




    ByteBuffer readInputStream() throws Exception
    {
        if(toSkip>0)
            toSkip -= inputStream.skip(toSkip);  // throws
        // skip() may not work. we try it once. if toSkip is still >0, we need to read-and-discard.

        byte[] array = new byte[bufferSize];
        while(true)
        {
            int r = inputStream.read(array);  // throws. may not respond to interrupt(cancel)
            if(r==-1)
            {
                throw End.instance();
            }
            // r>0, at least one byte is read. (code ok if r==0)
            if(toSkip<r)
            {
                int off = (int)toSkip;
                toSkip=0;
                int len = r - off;
                return ByteBuffer.wrap(array, off, len);
            }
            // else skip the whole thing. read again
            toSkip -= r;

            if(Thread.interrupted())  // cancelled
                throw new InterruptedException();
        }
    }

}
