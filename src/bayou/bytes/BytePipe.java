package bayou.bytes;

import bayou.async.Async;
import bayou.async.Fiber;
import bayou.async.Promise;
import bayou.util.End;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * A pipe that connects a {@link ByteSink} and a {@link ByteSource}.
 * <p>
 *     A {@link BytePipe} has a {@link #sink()} on one end, and a {@link #source()} on the other end.
 *     Bytes written to the sink will come out of the source in the same order.
 * </p>
 * <p>
 *     Writing to the sink and reading from the source can be executed on the same {@link Fiber}.
 *     <!-- but the fiber trace may be messed up -->
 * </p>
 */
public class BytePipe
{
    // todo buffering:
    // currently there is no buffer. each write() must wait for the read(). this may become a problem.
    // if we add buffering, sink.close() needs to wait till all buffered bytes are read.
    // on write error: should we clear the buffer immediately? or serve the buffered data anyway to reader?
    // maybe we can have two buffers: send/receive buffers.
    // on write error, send buffer is cleared, but receive buffer is not.
    // tune the sizes of the two buffers for desired behavior.

    final Sink sink = new Sink();
    final Source source = new Source();

    /**
     * Create a new BytePipe.
     */
    public BytePipe()
    {
    }

    /**
     * Get the sink of this pipe.
     */
    public ByteSink sink()
    {
        return sink;
    }

    /**
     * Get the source of this pipe.
     */
    public ByteSource source()
    {
        return source;
    }



    // graph 3-13-2013

    // sink&source are thread safe. support async close. but don't advertise it.
    final Object lock(){ return this; }
    // we don't really worry about lock contention

    enum State
    {
        init,
        writePending, writeClosed,
        readPending, readClosed,
        rwClosed
    }
    State state = State.init;

    // if a write fails, the data sequence is corrupt. close sink with wError.
    // if a read fails, source is ok, can be read again.

    Exception wError; // may exist in wClosed states
    ByteBuffer wBB;
    Promise<Void> wPromise;

    Promise<ByteBuffer> rPromise;


    class Sink implements ByteSink
    {
        @Override
        public Async<Void> write(ByteBuffer bb)
        {
            synchronized (lock())
            {
                switch (state)
                {
                    case init:
                        state = State.writePending;
                        wBB = bb;
                        wPromise = new Promise<>();
                        wPromise.onCancel(new CancelWrite(wPromise));
                        return wPromise;

                    case writePending:
                        // serious programming error
                        throw new IllegalStateException("there's already a pending write");

                    case writeClosed:
                        return Async.failure(new Exception("sink is closed", wError));
                        // "normal" exception - async close is allowed

                    case readPending:
                        rPromise.succeed(bb);
                        rPromise=null;
                        state = State.init;
                        return Async.VOID;

                    case readClosed:
                        // reader quits before writer finishes. signal error to writer so it'll quit too.
                        state = State.rwClosed;
                        wError = new Exception("source is closed");
                        return Async.failure(wError);

                    case rwClosed: // same as writeClosed
                        return Async.failure(new Exception("sink is closed", wError));

                    default: throw new AssertionError();
                }
            }
        }

        @Override
        public Async<Void> error(Exception error)  // close sink with error
        {
            synchronized (lock())
            {
                switch (state)
                {
                    case init:
                        state = State.writeClosed;
                        wError = error;
                        return Async.VOID;

                    case writePending:
                        // "async error", allowed.
                        cancelWrite(error);
                        return Async.VOID;

                    case writeClosed:
                        if(wError==null)
                            wError=error;
                        return Async.VOID;

                    case readPending:
                        rPromise.fail(new Exception("sink error: "+error, error));
                        rPromise=null;
                        state = State.writeClosed;
                        wError = error;
                        return Async.VOID;

                    case readClosed:
                        state = State.rwClosed;
                        wError = error;
                        return Async.VOID;

                    case rwClosed: // same as writeClosed
                        if(wError==null)
                            wError=error;
                        return Async.VOID;

                    default: throw new AssertionError();
                }
            }
        }

        @Override
        public Async<Void> close()
        {
            synchronized (lock())
            {
                switch (state)
                {
                    case init:
                        state = State.writeClosed;
                        return Async.VOID;

                    case writePending:
                        // "async close", allowed. data sequence is corrupt.
                        cancelWrite(new Exception("sink is closed"));
                        return Async.VOID;

                    case writeClosed:
                        return Async.VOID;

                    case readPending:
                        rPromise.fail(End.instance()); // source sees EOF
                        rPromise=null;
                        state = State.writeClosed;
                        return Async.VOID;

                    case readClosed:
                        state = State.rwClosed;
                        return Async.VOID;

                    case rwClosed: // same as writeClosed
                        return Async.VOID;

                    default: throw new AssertionError();
                }
            }
        }
    }

    void cancelWrite(Exception e)
    {
        wPromise.fail(e);
        wPromise=null;
        wBB=null;
        state = State.writeClosed;
        wError = e;
    }

    class CancelWrite implements Consumer<Exception>
    {
        Promise<Void> _wPromise;

        CancelWrite(Promise<Void> _wPromise)
        {
            this._wPromise = _wPromise;
        }

        @Override
        public void accept(Exception e)
        {
            synchronized (lock())
            {
                if(wPromise == _wPromise)
                {
                    assert state==State.writePending;
                    cancelWrite(e);
                }
            }
        }
    }

    class Source implements ByteSource
    {
        @Override
        public Async<ByteBuffer> read()
        {
            synchronized (lock())
            {
                switch (state)
                {
                    case init:
                        state = State.readPending;
                        rPromise = new Promise<>();
                        rPromise.onCancel(new CancelRead(rPromise));
                        return rPromise;

                    case writePending:
                        ByteBuffer _wBB = wBB;
                        wPromise.succeed(null);
                        wPromise = null;
                        wBB = null;
                        state = State.init;
                        return Async.success(_wBB);

                    case writeClosed:
                        if(wError!=null)
                            return Async.failure(new Exception("sink error: "+wError, wError));
                        else
                            return Async.failure(End.instance()); // EOF

                    case readPending:
                        // serious programming error
                        throw new IllegalStateException("there's already a pending read");

                    case readClosed:
                        return Async.failure(new Exception("source is closed"));
                        // "normal" exception - async close is allowed

                    case rwClosed: // same as readClosed
                        return Async.failure(new Exception("source is closed"));
                        // wError may exist; not reported here

                    default: throw new AssertionError();
                }
            }
        }

        @Override
        public Async<Void> close()
        {
            synchronized (lock())
            {
                switch (state)
                {
                    case init:
                        state = State.readClosed;
                        return Async.VOID;

                    case writePending:
                        cancelWrite(new Exception("source is closed"));
                        state = State.rwClosed;
                        return Async.VOID;

                    case writeClosed:
                        state = State.rwClosed;
                        return Async.VOID;

                    case readPending:
                        // async close, allowed
                        rPromise.fail(new Exception("source is closed"));
                        rPromise=null;
                        state = State.readClosed;
                        return Async.VOID;

                    case readClosed:
                        return Async.VOID;

                    case rwClosed: // same as readClosed
                        return Async.VOID;

                    default: throw new AssertionError();
                }
            }
        }
    }

    class CancelRead implements Consumer<Exception>
    {
        Promise<ByteBuffer> _rPromise;

        CancelRead(Promise<ByteBuffer> _rPromise)
        {
            this._rPromise = _rPromise;
        }

        @Override
        public void accept(Exception e)
        {
            synchronized (lock())
            {
                if(rPromise == _rPromise)
                {
                    assert state==State.readPending;
                    rPromise.fail(e);
                    rPromise =null;
                    state = State.init;
                }
            }
        }
    }

}
