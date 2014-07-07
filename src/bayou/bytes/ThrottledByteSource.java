package bayou.bytes;

import _bayou._bytes._ByteSourceSkipper;
import _bayou._tmp._ByteBufferUtil;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.async.Fiber;
import bayou.util.Result;

import java.nio.ByteBuffer;
import java.time.Duration;

/**
 * Throttles a ByteSource to limit how fast bytes can be served.
 * <p>For example:</p>
 * <pre>
     ByteSource fastSrc = new FileByteSource("/tmp/data.bin");
     ByteSource slowSrc = new ThrottledByteSource(fastSrc, 1024, 0, 1024); // 1Kb/s
     AsyncIterator.forEach( slowSrc::read, System.out::println ).finally_( slowSrc::close );
 * </pre>
 * <h4 id=curve>Curve</h4>
 * <p>
 *     The throttling is based on a curve <em>b(t)</em>.
 *     At time <em>t</em>, the total number of bytes served should not exceed <em>b(t)</em>.
 * </p>
 * <p>
 *     The unit of <em>t</em> is milli-second. <em>t</em> is relative to the 1st read,
 *     i.e. <em>t=0</em> on the first read() call.
 * </p>
 * <p>
 *     See {@link bayou.bytes.ThrottledByteSource.Curve}.
 * </p>
 * <h4>bufferSize</h4>
 * <p>
 *     The `bufferSize` influences the size and frequency of reads.
 *     For example, suppose we throttle a very fast origin source at 10K bytes per second,
 *     if bufferSize=10K, read() will stall for 1   second then yield a 10K buffer;
 *     if bufferSize=1K,  read() will stall for 0.1 second then yield a  1K buffer.
 * </p>
 * <h4 id=skipping>Skipping</h4>
 * <p>
 *     Skipped bytes are not counted as served bytes. The `skip(n)` method of this class
 *     is guaranteed to return `n`, so that the skipped bytes will not interfere
 *     with the throughput of read().
 * </p>
 */

// usually for throttling responses. but can be used for throttling requests too.

// skip:
// skipped bytes are not counted as served bytes. skip(n) is guaranteed, it always return n.
// if origin doesn't skip() properly, we'll read-and-discard internally.
// the total bytes served is the total bytes returned by our read(). our read() result never contain skipped bytes.
// if we don't impl skip() properly, caller will have to read-and-discard,
// but read() is throttled, so that can be very slow. but if that is actually what the user wants,
// he can always intercept skip() to make it not effective (by subclass-ing or wrapping)

public class ThrottledByteSource implements ByteSource
{


    /**
     * The curve used in {@link ThrottledByteSource}.
     * <p>
     *     The throttling is based on a curve <em>b(t)</em>.
     *     At time <em>t</em>, the total number of bytes served should not exceed <em>b(t)</em>.
     * </p>
     * <p>
     *     The unit of <em>t</em> is milli-second. <em>t</em> is relative to the 1st read,
     *     i.e. <em>t=0</em> on the first read() call.
     * </p>
     * <p>
     *     This interface uses <code>long</code> type for <em>b</em> and <em>t</em>.
     *     Both the caller and the implementer should  be careful with arithmetic precisions.
     * </p>
     */
    public interface Curve
    {
        /**
         * The max total number of bytes that can be served at time <em>t</em> (ms).
         * <p>
         *     Required: if <em>t1&lt;=t2</em>, then <em>b(t1)&lt;=b(t2)</em>
         * </p>
         */
        long b(long t);

        /**
         * The inverse function of <em>b(t)</em>.
         * <p>
         *     Required: <em>t(b(x)) = x</em> (approximately)
         * </p>
         */
        long t(long b);
        // we don't want to inverse b(t) ourselves (numerically)

        /**
         * Create a linear curve.
         * <p>
         *     The curve will be <em> b(t) = b0 + t * bytesPerSecond / 1000 </em>
         * </p>
         * <p>
         *     <b>CAUTION:</b> the unit of `bytesPerSecond` is <em>byte/second</em>, not <em>milli-second</em>.
         * </p>
         */
        public static Curve linear(long b0, long bytesPerSecond)
        {
            return new LinearCurve(b0, bytesPerSecond);
        }
    }

    _ByteSourceSkipper origin; // skip() guaranteed
    int s;  // suggested buffer size for read(). origin may return huge buffers; we need smaller ones for throttling
    Curve curve;

    /**
     * Create a throttled wrapper of the origin source.
     */
    public ThrottledByteSource(ByteSource origin, int bufferSize, Curve curve)
    {
        this.origin = new _ByteSourceSkipper(origin);
        this.s = bufferSize;
        this.curve = curve;
    }

    /**
     * Create a throttled wrapper of the origin source, with a linear curve.
     * <p>
     *     The curve used here is
     *     {@link Curve#linear(long, long) Curve.linear(b0, bytesPerSecond)}.
     * </p>
     */
    public ThrottledByteSource(ByteSource origin, int bufferSize, long b0, long bytesPerSecond)
    {
        this(origin, bufferSize, Curve.linear(b0, bytesPerSecond));
    }

    static class LinearCurve implements Curve
    {
        final long b0;
        final long v; // bytes per second
        public LinearCurve(long b0, long bytesPerSecond)
        {
            _Util.require(bytesPerSecond>0, "bytesPerSecond>0");

            this.b0 = b0;
            this.v = bytesPerSecond;
        }
        @Override public long b(long t)
        {
            return b0 + v*t/1000;
        }
        @Override public long t(long b)
        {
            return (b- b0)*1000/v;
        }
    }


    boolean closed;
    long t0;
    long served;
    ByteBuffer hoard;

    /**
     * Skip forward `n` bytes.
     * <p>
     *     This implementation guarantees that `n` bytes will be skipped, and `n` will be returned
     *     (barring any exceptions).
     * </p>
     *
     * @return number of bytes actually skipped; guaranteed to be `n`.
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
            return origin.skip(n); // guaranteed skipping

        // skip hoard first
        int r = hoard.remaining();
        if(n<r) // skip part of hoard
        {
            hoard.position(hoard.position() + (int) n);
            return n;
        }
        else // n>=r, skip entire hoard
        {
            hoard = null;
            if(n>r)
                origin.skip(n-r); // guaranteed skipping
            return n;
        }
    }

    /**
     * Read the next chunk of bytes.
     * <p>
     *     Typically an artificial delay will be introduced for the purpose of throttling.
     * </p>
     *
     * @throws IllegalStateException
     *         if this source is closed.
     */
    @Override
    public Async<ByteBuffer> read() throws IllegalStateException
    {
        if(closed)
            throw new IllegalStateException("closed");

        long now = System.currentTimeMillis();
        if(t0==0) // first time read()
            t0 = now;

        if(hoard!=null)
            return toServeHoard(now);

        return origin.read().then(this::hoardAndServe);
    }
    Async<ByteBuffer> hoardAndServe(ByteBuffer result)
    {
        hoard = result;  // could be empty
        return toServeHoard(System.currentTimeMillis());
    }
    Async<ByteBuffer> toServeHoard(long now)
    {
        // so we have some hoarded data immediately available. we may want to delay the serving
        // for throttling and to avoid returning small buffers.
        // we want to serve at a later time t, when b(t)-served >= s
        long ts = t0 + curve.t(served+s);
        long delay = ts-now;
        if(delay<=0)
            return Result.success(doServeHoard(now));

        // serve after delay
        return Fiber.sleep(Duration.ofMillis(delay))
            .map( (v) -> doServeHoard(System.currentTimeMillis()));
    }
    ByteBuffer doServeHoard(long now)
    {
        long expected = curve.b(now-t0);
        long deficit = expected - served;
        // ideally, deficit>=s.
        // deficit<s is possible, due to low precision in scheduler, b(t), t(b).
        // e.g. b(t) is too steep, t(served+s)=0,  b(0)=0 therefore expected=0!
        if(deficit<s)
            deficit=s;
        // force deficit to be at least s. we may over-serve this time, that's ok, no long term impact.

        int available = hoard.remaining();  // could be 0
        ByteBuffer bb;

        if(available<=deficit)
        {
            bb = hoard;
            hoard=null;
            served += available;
        }
        else // available>deficit
        {
            // usually, origin is much faster than b(t) so we actually accurately serve b(t) bytes at time t.
            bb = _ByteBufferUtil.slice(hoard, (int) deficit);
            served += deficit;
        }

        return bb;
    }

    /**
     * Close this source.
     */
    @Override
    public Async<Void> close()
    {
        if(closed)
            return Async.VOID;
        closed=true;

        if(hoard!=null)
            hoard=null;

        origin.close();
        origin=null;

        curve = null;
        return Async.VOID;
    }


}
