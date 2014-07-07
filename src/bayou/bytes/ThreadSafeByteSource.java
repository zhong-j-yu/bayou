package bayou.bytes;

import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.util.Result;
import bayou.util.function.RunnableX;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;

/**
 * A thread-safe wrapper of a ByteSource; supporting arbitrary close().
 * <p>
 *     A {@link ByteSource} is usually not thread-safe.
 *     This class provides a thread-safe wrapper - it's safe to call read()/skip()/close()
 *     on any thread at any time.
 * </p>
 * <p>
 *     The main purpose of this class is to allow close() to be called on any thread at any time.
 * </p>
 * <p>
 *     Particularly, close() can be invoked while a read() is pending
 *     (which is usually illegal for a ByteSource).
 *     The pending read will be cancelled by close().
 * </p>
 * <p>
 *     However, it is still considered a programming error to invoke read()/skip() while a read() is pending.
 *     This class checks for such invocations and throws IllegalStateException.
 * </p>
 */
public class ThreadSafeByteSource implements ByteSource
{
    final Object lock(){ return this; }
    final ByteSource origin;

    enum State{ standby, readPending, closePending, closed }
    State state;
    // closePending: read is pending and close is requested

    Async<ByteBuffer> currAsync;  // during read pending

    /**
     * Creates a thread-safe wrapper of the `origin` ByteSource.
     */
    public ThreadSafeByteSource(ByteSource origin)
    {
        this.origin = origin;

        synchronized (lock())
        {
            state = State.standby;
        }
    }


    // read()/skip() on closePending:
    //   it's a programming error on read flow, since prev read hasn't completed yet.
    //   so we throw IllegalStateException
    // read()/skip() on closed
    //   src could be async-ly closed by another flow; not a programming error of this flow.
    //   do not throw IllegalStateException.


    /**
     * Read the next chunk of bytes.
     * <p>
     *     If this source is closed, this action fails with an
     *     {@link AsynchronousCloseException}. Note: that means the returned Async
     *     will be a failure; not that this method will throw the exception.
     * </p>
     * @throws IllegalStateException if there is already a pending read.
     */
    @Override
    public Async<ByteBuffer> read() throws IllegalStateException
    {
        synchronized (lock())
        {
            switch(state)
            {
                case standby:
                    Async<ByteBuffer> async = origin.read();
                    Result<ByteBuffer> result = async.pollResult();
                    if(result!=null)
                        return result;  // state is still standby

                    state = State.readPending;
                    currAsync = async;  // may be needed by close() before read completes
                    Async<ByteBuffer> async2 = async.finally_(cbComplete);
                    return async2;
                    // note: async2 completes after cbComplete is invoked.
                    //   otherwise, after user sees async2() completes, it can call read() again,
                    //   which may be before cbComplete is invoked.

                case readPending:
                case closePending:
                    throw new IllegalStateException("read pending");

                case closed: // if async-ly closed by another thread, this read() call is innocent.
                    return Result.failure(new AsynchronousCloseException());

                default: throw new AssertionError();
            }
        }
    }

    final RunnableX cbComplete = () -> {
        // readPending or closePending.
        synchronized (lock())
        {
            switch(state)
            {
                case standby:
                    throw new AssertionError();

                case readPending:
                    state = State.standby;
                    currAsync = null;
                    return;

                case closePending:
                    state = State.closed;
                    (this).origin.close();
                    return ;

                case closed:
                    throw new AssertionError();

                default: throw new AssertionError();
            }
        }
    };

    /**
     * Try to skip forward `n` bytes.
     * <p>
     *     This method throws IllegalStateException if there is a read pending.
     * </p>
     * @throws IllegalStateException if there is a read pending.
     */
    @Override
    public long skip(long n) throws IllegalArgumentException, IllegalStateException
    {
        _Util.require(n >= 0, "n>=0");

        synchronized (lock())
        {
            switch(state)
            {
                case standby:
                    return origin.skip(n);

                case readPending:
                case closePending:
                    throw new IllegalStateException("read pending");

                case closed:
                    return 0; // if async-ly closed by another thread, this skip() call is innocent.

                default: throw new AssertionError();
            }
        }
    }

    /**
     * Close this source.
     * <p>
     *     It is OK to call this method while a read is pending; the pending read will be cancelled.
     * </p>
     *
     */
    @Override
    public Async<Void> close()
    {
        synchronized (lock())
        {
            switch(state)
            {
                case standby:
                    state = State.closed;
                    return origin.close();

                case readPending:
                    state = State.closePending;

                    // origin.close() to be done after currAsync completes
                    currAsync.cancel(new Exception("cancel read, because close() is called"));
                    currAsync = null;
                    return Async.VOID;

                case closePending:
                case closed:
                    return Async.VOID;

                default: throw new AssertionError();
            }
        }
    }
}
