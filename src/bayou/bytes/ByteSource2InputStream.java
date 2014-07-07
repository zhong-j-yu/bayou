package bayou.bytes;

import _bayou._async._Asyncs;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.util.End;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.time.Duration;

/**
 * Converts a ByteSource to an InputStream.
 * <p>
 *     This is useful to pass a ByteSource to a legacy API that accepts InputStream.
 * </p>
 * <p>
 *     CAUTION: InputStream.read() usually blocks;
 *     a method that accepts an InputStream is usually a blocking method.
 *     Keep that in mind when building a non-blocking app.
 * </p>
 */
public class ByteSource2InputStream extends InputStream
{
    // thread safe - as most InputStream impls. see ByteArrayInputStream

    ByteSource origin;
    Duration readTimeout;
    boolean closed;

    /**
     * Create an InputStream based on the ByteSource.
     * @param readTimeout timeout for reading the origin ByteSource.
     */
    public ByteSource2InputStream(ByteSource origin, Duration readTimeout)
    {
        this.origin = origin;
        this.readTimeout = readTimeout;
    }

    ByteBuffer hoard;

    // CAUTION: see Fiber.block()
    @Override
    synchronized // SYNCHRONIZED!!
    public int read(byte[] dst, int off, int len) throws IOException
    {
        if(closed)
            throw new IllegalStateException("closed");

        while(hoard==null)
        {
            ByteBuffer bb;
            try
            {
                Async<ByteBuffer> asyncRead = origin.read().timeout(readTimeout);
                bb = _Asyncs.await(asyncRead).getOrThrow();  // blocked till completion.
            }
            catch (End end)
            {
                return -1;
            }
            catch (IOException e)
            {
                throw e;
            }
            catch (Exception e)
            {
                throw new IOException(e);
            }

            if(bb.remaining()>0)
                hoard = bb;
            // else, spurious result, read again.
            // InputStream.read() must block till at least one byte is read
        }

        int myLen = hoard.remaining();  // >0
        if(myLen>len)
        {
            hoard.get(dst, off, len);
            return len;
        }
        else
        {
            hoard.get(dst, off, myLen);
            hoard = null;
            return myLen;
        }
    }

    byte[] byte1;

    @Override
    synchronized // SYNCHRONIZED!!
    public int read() throws IOException
    {
//        if(closed)
//            throw new IllegalStateException("closed");

        if(byte1==null)
            byte1=new byte[1]; // reuse a byte[1], try to reduce overhead of this single-byte read method.

        int r = read(byte1, 0, 1);
        if(r==-1)
            return -1;
        else // r==1
            return byte1[0];
    }

    @Override
    synchronized // SYNCHRONIZED!!
    public long skip(long n) throws IOException
    {
        _Util.require(n >= 0, "n>=0");

        if(closed)
            throw new IllegalStateException("closed");

        long s=0;
        if(hoard!=null)
        {
            int myLen = hoard.remaining();
            if(myLen>n)
            {
                hoard.position(hoard.position()+(int)n);
                return n;
            }
            else
            {
                hoard = null;
                s += myLen;
                n -= myLen;
                // continue to skip origin
            }
        }

        s += origin.skip(n);

        return s;
    }

    @Override
    synchronized // SYNCHRONIZED!!
    public int available()
    {
        if(closed)
            throw new IllegalStateException("closed");

        if(hoard==null)
            return 0;
        else
            return hoard.remaining();
    }

    @Override
    synchronized // SYNCHRONIZED!!
    public void close()
    {
        if(closed)
            return;
        closed=true;

        if(hoard!=null)
            hoard = null;

        origin.close();   // we don't worry about async close due to synchronization
        origin=null;
    }


}
