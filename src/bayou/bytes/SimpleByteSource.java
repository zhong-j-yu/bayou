package bayou.bytes;

import _bayou._tmp._StreamIter;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.util.Result;

import java.nio.ByteBuffer;
import java.util.stream.Stream;

/**
 * A simple ByteSource based on some ByteBuffers.
 * <p>
 *     Each SimpleByteSource is associated with a `Stream&lt;ByteBuffer&gt;`.
 *     The read() method simply serves the ByteBuffers from the stream.
 * </p>
 * <p>
 *     Actually, read() serves a readonly view of the original ByteBuffer from the stream.
 *     The original ByteBuffer is not modified, therefore can be shared by multiple SimpleByteSources.
 * </p>
 */
public class SimpleByteSource implements ByteSource
{
    _StreamIter<ByteBuffer> bbIter; // null if closed

    /**
     * Create a SimpleByteSource based on the stream of ByteBuffers.
     * <p>
     *     CAUTION: the stream must be non-blocking in yielding its elements,
     *     i.e. the tryAdvance() method of
     *     {@linkplain java.util.stream.Stream#spliterator() its spliterator}
     *      must be non-blocking.
     * </p>
     */
    public SimpleByteSource(Stream<ByteBuffer> bbStream)
    {
        this.bbIter = new _StreamIter<>(bbStream);
    }

    /**
     * Create a SimpleByteSource based on a single ByteBuffer.
     * <p>
     *     Calling this constructor is equivalent to
     *     `new SimpleByteSource( Stream.of(bb) )`.
     * </p>
     */
    public SimpleByteSource(ByteBuffer bb)
    {
        this(Stream.of(bb));
    }

    /**
     * Create a SimpleByteSource of the bytes.
     * <p>
     *     Calling this constructor is equivalent to
     *     `new SimpleByteSource( ByteBuffer.wrap(bytes) )`.
     * </p>
     */
    public SimpleByteSource(byte[] bytes)
    {
        this(ByteBuffer.wrap(bytes));
    }


    /**
     * Read the next chunk of bytes.
     * <p>
     *     This method will serve the next ByteBuffer from the stream.
     *     Actually, a {@linkplain java.nio.ByteBuffer#asReadOnlyBuffer() readonly view}
     *     is served, so that the original ByteBuffer will not be touched.
     * </p>
     * @throws IllegalStateException
     *         if this source is closed.
     */
    @Override
    public Async<ByteBuffer> read() throws IllegalStateException
    {
        if(bbIter ==null)
            throw new IllegalStateException("closed");

        ByteBuffer next = bbIter.next();
        if(next!=null)
            return Result.success(next.asReadOnlyBuffer());  // return a duplicate; don't touch origin bb
        else
            return _Util.EOF;
    }

    // skip()
    // since everything is in memory, we simply return 0 here, let caller read-and-discard.
    // we can't skip a lot faster by doing it internally

    /**
     * Close this source.
     */
    @Override
    public Async<Void> close()
    {
        if(bbIter ==null)
            return Async.VOID;

        bbIter.close();
        bbIter = null;
        return Async.VOID;
    }
}
