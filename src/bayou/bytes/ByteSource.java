package bayou.bytes;

import _bayou._str._CharsetDecoder;
import _bayou._str._HoehrmannUtf8Decoder;
import bayou.async.Async;
import bayou.async.AsyncIterator;
import bayou.util.OverLimitException;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;

/**
 * Async source of bytes.
 *
 * <p>
 *     A ByteSource contains a sequence of bytes.
 *     The {@link #read()} action succeeds with a ByteBuffer containing next chunk of bytes,
 *     or fails with {@link bayou.util.End End} to indicate EOF.
 *     The source must be {@link #close() closed} after use.
 * </p>
 * <p>
 *     Example Usage:
 * </p>
 * <pre>
         ByteSource src = new FileByteSource("/tmp/data.bin");

         AsyncIterator.forEach( src::read, System.out::println )
             .finally_( src::close );
 * </pre>
 * <p>
 *     A ByteSource can optionally support {@link #skip(long) skip(n)}.
 * </p>
 * <p>
 *     In general, a ByteSource is <em>not</em> thread-safe. See {@link ThreadSafeByteSource}
 *     for a thread-safe wrapper.
 * </p>
 * <p>
 *     In general, concurrent pending actions are not allowed.
 *     Particularly, close() cannot be invoked while a read() is pending.
 *     See {@link ThreadSafeByteSource} for a solution.
 * </p>
 *
 */

public interface ByteSource
{
    //   read() - source provides a ByteBuffer, instead of caller supplying a buffer
    //       usually source knows better about buffering of its data.
    //          cached data: can return same buffer, instead of copying to caller supplied buffer each time
    //          ssl engine: need a buffer large enough for decryption
    //          req entity: need to return a slice of data read.
    //       in last 2 cases, if caller supplies buffer, source may need its own buffer, and copying may be needed
    //   caller does not need to allocate a buffer when read is pending, which may save memory


    /**
     * Read the next chunk of bytes.
     * <p>
     *     The method returns an `Async&lt;ByteBuffer&gt;` which eventually completes in 3 possible ways:
     * </p>
     * <ul>
     *     <li>
     *         succeeds with a ByteBuffer.
     *     </li>
     *     <li>
     *         fails with an {@link bayou.util.End End}, indicating EOF.
     *     </li>
     *     <li>
     *         fails with some other exception.
     *     </li>
     * </ul>
     * <p>
     *     Example Usage:
     * </p>
     * <pre>
     *     AsyncIterator.forEach( source::read, System.out::println )
     *         .finally_( source::close );
     * </pre>
     * <p>
     *     The resulting ByteBuffer may contain any number of bytes. For example, it may be a view of
     *     a huge ByteBuffer that's cached and shared.
     *     You may use {@link PushbackByteSource} to "unread".
     * </p>
     * <p>
     *     The ownership of the ByteBuffer is transferred to the caller.
     *     The content of the ByteBuffer should be treated as read-only.
     * </p>
     * <p>
     *     CAUTION: since ByteBuffer is stateful (even for methods like {@link ByteBuffer#get()}),
     *     a new ByteBuffer must be created for each read() action.
     *     The implementation may create a view of a shared ByteBuffer through
     *     {@link ByteBuffer#asReadOnlyBuffer()}.
     * </p>
     * <p>
     *     The app should wait for this read() action to complete
     *     before it calls another method on this source.
     * </p>
     */
    Async<ByteBuffer> read();
    // if fails with a non-End exception, the failure may or may not be recoverable.
    // spurious success (bb.remaining()==0) should be avoided; could happen occasionally.
    // it may be wasteful to return a huge chunk, more than what the reader needs at the moment.
    //     for example, http server read() from a file source, gets a chunk more than send buffer
    // while a read() is pending, it's illegal to operate on the source.
    //    however, an impl usually does not guard against such illegal operations; it assumes it doesn't happen.
    // if a pending read is cancelled, the exception may be persistent.
    //    otherwise, next read() should must return data from the position when this read() was issued.






    /**
     * Try to skip forward `n` bytes; return number of bytes actually skipped.
     * <p>
     *     This is an optional operation. An implementation can simply do nothing and return 0.
     *     The default implementation does exactly that.
     * </p>
     * <p>
     *     The returned value must no exceed `n`.
     * </p>
     * <p>
     *     It's possible to skip beyond EOF. For example, if there are 10 bytes left in this source,
     *     skip(20) may succeed and return 15. The next read() will see EOF.
     * </p>
     * <p>
     *     The implementation must be non-blocking.
     * </p>
     * <p>
     *     This method should not be invoked while a read() is pending, or after close() has been called.
     *     IllegalStateException may be thrown if that happens.
     * </p>
     *
     * @param n number of bytes to skip;  <code>n &gt;= 0</code>.
     * @return number of bytes (m) actually skipped. <code>0 &lt;= m &lt;= n</code>.
     * @throws IllegalArgumentException if <code>n &lt; 0</code>.
     */
    default long skip(long n) throws IllegalArgumentException
    {
        // we don't even check n here. this is an optional operation anyway.
        return 0;
    }
    // we cannot skip while a read is pending -
    //    what does it even mean? skip for the pending read or the next read?
    // caller can always skip by read-and-discard. see also ByteSourceSkipper.
    // if an impl has more efficient way of skipping than read-and-discard,
    //    e.g. file source, it should properly impl skip().
    // see FileInputStream.skip() for skip-beyond-EOF semantics.
    // an impl may not care to check and throw IllegalStateException,
    //    if it doesn't matter to the integrity of the impl.
    // if there are some internal errors triggered by skip,
    //    don't bother caller. error can be reported in next read()


    /**
     * Close this source.
     * <p>
     *     This method can be called multiple times; only the first call is effective.
     * </p>
     * <p>
     *     This method should not be invoked while a read() is pending;
     *     if that's needed, see {@link ThreadSafeByteSource} for a solution.
     * </p>
     * <p>
     *     Since ByteSource is a read-only concept, close() should not have any
     *     side effects that the caller cares about.
     *     The caller is allowed to ignore the returned {@code Async<Void>}
     *     (as if the method returns {@code void}).
     * </p>
     * <p>
     *     The close() action should not fail; if some internal exception arises, it can be logged.
     *     Most implementations return an immediate {@link Async#VOID},
     *     even if there are still background cleanup tasks running.
     * </p>
     *
     */
    Async<Void> close();
    // previously this method returns void. changed to Async<Void> to be consistent with ByteSink,
    // in case someone wants to impl both ByteSource and ByteSink on a class.





    /**
     * Read all bytes from this source into one ByteBuffer.
     * <p>
     *     If this source has more bytes than `maxBytes`, this action fails with
     *     an {@link OverLimitException}.
     *     The `maxBytes` parameter is usually a defense against denial-of-service requests.
     * </p>
     * <p>
     *     Example Usage:
     * </p>
     * <pre>
     *     ByteSource requestBody = httpRequest.entity().body();
     *     requestBody.readAll(1000).then(...);
     * </pre>
     * <p>
     *     This source is automatically closed after `allBytes()` is completed (in success or failure).
     * </p>
     *
     * @param maxBytes max number of bytes expected from this source.
     */
    public default Async<ByteBuffer> readAll(int maxBytes)
    {
        class SaveBbs
        {
            final ArrayList<ByteBuffer> bbs = new ArrayList<>();
            int totalBytes;

            public Void save(ByteBuffer bb) throws Exception
            {
                totalBytes += bb.remaining();
                if (totalBytes > maxBytes)
                    throw new OverLimitException("maxBytes", maxBytes);
                bbs.add(bb);
                return null;
            }

            ByteBuffer merge()
            {
                if(bbs.size()==1)  // not uncommon
                    return bbs.get(0);
                else
                    return ByteBuffer.wrap(toArray());
            }

            byte[] toArray()
            {
                byte[] bytes = new byte[totalBytes];
                int ib = 0;
                for (ByteBuffer bb : bbs)
                {
                    int N = bb.remaining();
                    bb.get(bytes, ib, N);
                    ib += N;
                }
                return bytes;
            }
        }

        final SaveBbs saveBbs = new SaveBbs();

        return AsyncIterator.forEach(this::read, saveBbs::save)
            .finally_(this::close)
            .map(vd -> saveBbs.merge());
    }


    /**
     * Read all bytes from this source and convert them to a String.
     * <p>
     *     The encoding is required to be UTF-8.
     *     If the bytes are not a valid UTF-8 sequence, this action fails with
     *     {@link java.nio.charset.CharacterCodingException}.
     * </p>
     * <p>
     *     If the number of <code>chars</code> exceeds `maxChars`, this action fails with
     *     an {@link OverLimitException}.
     *     The `maxChars` parameter is usually a defense against denial-of-service requests.
     * </p>
     * <p>
     *     CAUTION: `maxChars` refers to the number of <em>Java chars</em> here, not <em>unicode characters</em>.
     * </p>
     * <p>
     *     Example Usage:
     * </p>
     * <pre>
     *     ByteSource requestBody = httpRequest.entity().body();
     *     requestBody.asString(1000).then(...);
     * </pre>
     * <p>
     *     This source is automatically closed after `asString()` action is completed (in success or failure).
     * </p>
     *
     * @param maxChars max number of chars expected from this source.
     */
    public default Async<String> asString(int maxChars)
    {
        final _HoehrmannUtf8Decoder decoder = new _HoehrmannUtf8Decoder(maxChars);
        final ByteSource bs = this;

        return AsyncIterator
            .forEach(bs::read, decoder::decode)
            .then( v-> { decoder.finish(); return Async.VOID; } )
            .finally_(bs::close)
            .map( v -> decoder.getString() );
    }


    /**
     * Read all bytes from this source and convert them to a String.
     * <p>
     *     If the bytes are not a valid encoding of the `charset`, this action fails with
     *     {@link java.nio.charset.CharacterCodingException}.
     * </p>
     * <p>
     *     If the number of <code>chars</code> exceeds `maxChars`, this action fails with
     *     an {@link OverLimitException}.
     *     The `maxChars` parameter is usually a defense against denial-of-service requests.
     * </p>
     * <p>
     *     CAUTION: `maxChars` refers to the number of <em>Java chars</em> here, not <em>unicode characters</em>.
     * </p>
     * <p>
     *     Example Usage:
     * </p>
     * <pre>
     *     ByteSource requestBody = httpRequest.entity().body();
     *     requestBody.asString(1000, StandardCharsets.UTF_16).then(...);
     * </pre>
     * <p>
     *     This source is automatically closed after `asString()` action is completed (in success or failure).
     * </p>
     *
     * @param maxChars max number of chars expected from this source.
     */
    public default Async<String> asString(int maxChars, Charset charset)
    {
        if(charset.name().equalsIgnoreCase("UTF-8"))
            return asString(maxChars); // that impl is better

        final _CharsetDecoder decoder = new _CharsetDecoder(maxChars, charset);
        final ByteSource bs = this;

        return AsyncIterator
            .forEach(bs::read, decoder::decode)
            .then( v-> { decoder.finish(); return Async.VOID; } )
            .finally_(bs::close)
            .map( v -> decoder.getString() );
    }





    /**
     * Write the bytes from this source to the sink.
     * <p>
     *     If this action is successful, the result is the number of bytes copied.
     * </p>
     * <p>
     *     After this action completes, regardless of success or failure,
     *     both this source and the sink will be closed.
     * </p>
     */
    public default Async<Long> toSink(ByteSink sink)
    {
        AsyncIterator<ByteBuffer> iter = this::read;
        Async<Long> action = iter.reduce_(Long.valueOf(0), (sum, bb)->
        {
            long sum2 = sum.longValue()+bb.remaining();
            //if(sum2>100_000_000) throw new Exception("test fail");
            return sink.write(bb).map(v->sum2);
        });

        // if fail, it's important to notify sink by sink.error()
        action = action.catch__(Exception.class, e-> sink.error(e).<Long>then(v->{throw e;}));

        action = action.finally_(this::close).finally_(sink::close);
        return action;
    }
}
