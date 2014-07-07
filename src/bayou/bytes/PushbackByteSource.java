package bayou.bytes;

import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.util.Result;

import java.nio.ByteBuffer;

/**
 * A ByteSource wrapper that supports unread().
 *
 * <p>
 *     `unread(bb)` stores `bb` internally to be served in the next `read()`.
 *     read() will remove the stored `bb`. skip() may also remove the stored `bb`.
 * </p>
 * <p>
 *     At most one ByteBuffer can be stored at a time.
 *     Consecutive unread() calls are not supported.
 * </p>
 */
public class PushbackByteSource implements ByteSource
{
    ByteSource origin;

    ByteBuffer hoard;
    // at this impl, only 1 hoard is supported. that's enough for most apps.

    boolean closed;

    /**
     * Create a PushbackByteSource wrapper of the `origin` ByteSource.
     */
    public PushbackByteSource(ByteSource origin)
    {
        this.origin = origin;
    }

    /**
     * Unread `bb`.
     * <p>
     *     `bb` is stored, to be served in then next read().
     * </p>
     * <p>
     *     If there is already a ByteBuffer stored from a previous unread(),
     *     this method throws IllegalStateException.
     * </p>
     *
     * @throws java.lang.IllegalStateException
     *         if there is already a ByteBuffer stored from a previous unread();
     *         or if this source has been closed.
     */
    public void unread(ByteBuffer bb) throws IllegalStateException
    {
        if(closed)
            throw new IllegalStateException("closed");

        if(hoard !=null)
            throw new IllegalStateException("consecutive unread() not supported");

        // bb can be empty, we'll still hoard it and return it in next read()
        hoard = bb;
    }

    /**
     * Read the next chunk of bytes.
     * <p>
     *     If there is a `bb` stored from a previous unread(),
     *     it's removed, and this read() action succeeds immediately with that `bb`.
     *     Otherwise, this call is equivalent to `origin.read()`.
     * </p>
     * @throws IllegalStateException
     *         if this source is closed.
     */
    @Override
    public Async<ByteBuffer> read() throws IllegalStateException
    {
        if(closed)
            throw new IllegalStateException("closed");

        ByteBuffer hoardL = hoard;
        if(hoardL!=null)
        {
            hoard = null;
            return Result.success(hoardL);
        }

        return origin.read();
    }

    /**
     * Try to skip forward `n` bytes.
     * <p>
     *     If there is a `bb` stored from a previous unread(),
     *     its position may be adjusted, or it may be removed entirely.
     * </p>
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

        if(hoard==null)
            return origin.skip(n);

        int s = hoard.remaining();
        if(n<s) // skip part of hoard
        {
            hoard.position(hoard.position()+ (int)n);
            return n;
        }
        // skip entire hoard
        hoard = null;

        if(n==s)
            return n;
        else // n>s
            return s + origin.skip(n-s);
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

        if(hoard !=null)
        {
            hoard = null;
        }

        origin.close();
        origin=null;
        return Async.VOID;
    }

}
