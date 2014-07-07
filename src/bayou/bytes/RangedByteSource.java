package bayou.bytes;

import _bayou._async._Asyncs;
import _bayou._tmp._Util;
import bayou.async.Async;

import java.nio.ByteBuffer;

/**
 * A sub-range [min, max) of an origin source.
 * <p>
 *     The `min` limit is inclusive. The `max` limit is exclusive.
 * </p>
 * <p>
 *     For example, an origin source contains 10 bytes {b<sub>0</sub>, ..., b<sub>9</sub>}.
 *     The sub-range [min=3, max=8) will contain 5 bytes {b<sub>3</sub>, ..., b<sub>7</sub>}.
 * </p>
 * <p>
 *     Both `min` and `max` can go beyond the range of the origin source.
 *     If the origin source contains 10 bytes,
 *     sub-range [3, Long.MAX_VALUE) is valid, containing 7 bytes;
 *     sub-range [10, 20) is also valid, containing 0 bytes.
 * </p>
 * <p>
 *     This class uses the {@link ByteSource#skip(long) skip(n)} method on the origin source.
 *     Note that `origin.skip(n)` does not need to actually skip n bytes;
 *     this class can skip the origin by read and discard bytes.
 * </p>
 */
public class RangedByteSource implements ByteSource
{
    ByteSource origin;
    long min, max;

    long position; // of origin source
    boolean closed;

    /**
     * Create a sub-range [min, max) of the origin source.
     * <p>
     *     Required: <code>0&lt;=min&lt;=max</code>.
     * </p>
     * <p>
     *     Note that it is legal if <code>max&gt;=L or min&gt;=L</code> where L is the length of the origin source.
     * </p>
     */
    // 0<=min<=max
    public RangedByteSource(ByteSource origin, long min, long max)
    {
        _Util.require(min>=0, "min>=0");
        _Util.require(min<=max, "min<=max");

        this.origin = origin;
        this.min = min;
        this.max = max;

        // be lazy, don't try to skip() to min here.
    }

    /**
     * Get the min limit of the range.
     */
    public long getMin(){ return min; }
    /**
     * Get the max limit of the range.
     */
    public long getMax(){ return max; }

    // returned value can be <min, can be >max
    // public long getPositionOfOrigin(){ return position; }
    //   if read() returns EOF, and getPositionOfOrigin()<getMax(), our range is not within the origin source.
    //   but the reader is unlikely to need this info, so don't publish it for now.

    /**
     * Try to skip forward `n` bytes.
     *
     * @throws IllegalStateException
     *         if this source is closed.
     */
    @Override
    public long skip(long n) throws IllegalArgumentException, IllegalStateException
    {
        _Util.require(n>=0, "n>=0");

        if(closed)
            throw new IllegalStateException("closed");

        if(position >=min)
        {
            long s = origin.skip(n);
            position += s;
            return s;
        }
        else
        {
            // pos0<min, we need to skip (min-pos0) and n.
            position += origin.skip(min- position +n);
            return position <min ? 0 : position -min;
        }
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

        if(position < min) // try to bring position to min. may not succeed.
            position += origin.skip(min- position); // should not throw. may throw.

        if(position >= max)
        {
            return _Util.EOF;
        }

        if(position >= min) // common case
            return origin.read().map(bb -> {
                position += bb.remaining();
                if (position > max)
                    bb.limit(bb.limit() - (int) (position - max));
                return bb;
            });  // if origin EOF, it's premature eof, since position<max. but we don't consider it error


        // position<min, we need to read and discard. should be uncommon.
        return _Asyncs.scan(origin::read,
            bb ->
            {
                long p1 = position;  // position<=min
                long p2 = p1 + bb.remaining();
                position = p2;

                if (p2 <= min) // discard, read again
                    return null;

                if (p1 < min)   // p1<=min
                    bb.position(bb.position() + (int) (min - p1));
                if (p2 > max)
                    bb.limit(bb.limit() - (int) (p2 - max));
                return bb;
                // note: bb is empty if min==max! we served spurious result. next read() will see EOF.
                // we could instead serve EOF right here.
                // consider this a pathological case, unworthy of checking.
                // won't happen in http range request, where at least 1 byte is requested.
            },
            end ->
            {
                // if origin EOF, it's premature EOF. we don't consider it an error. we EOF too
                throw end;
            }
        );
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

        return origin.close();
    }
}



