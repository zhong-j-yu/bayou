package bayou.bytes;

import _bayou._async._Asyncs;
import _bayou._log._Logger;
import _bayou._tmp._ByteBufferUtil;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.async.AsyncIterator;
import bayou.async.Promise;
import bayou.util.End;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Caches all bytes from a ByteSource in memory.
 * <p>
 *     The cached bytes can be viewed as a ByteSource by {@link #newView()}.
 * </p>
 * <p>
 *     Example Usage: cache a file content in memory
 * </p>
 * <pre>
     FileByteSource fileSrc = new FileByteSource("/tmp/data.bin");
     ByteSourceCache cache = new ByteSourceCache(fileSrc, null);

     // read bytes from the cache
     ByteSource view = cache.newView();
     AsyncIterator.forEach( view::read, System.out::println ).finally_( view::close );
 * </pre>
 * <p>
 *     If an error occurred while the cache reads the origin source,
 *     the error will be reflected when reading from views.
 * </p>
 */

// as soon as the cache is created, it can be read thru views, even tho copying is still ongoing.
// a copy buffer is available to views as soon as it's full.
// copying is lazy, triggered by first newView(). for eager copying, do cache.newView().close();
//
// CAUTION: after cache is created, and before first view is requested, we are holding the origin,
// which is idle but may be taking system resource. that's bad if the time windows is long.
// either do eager copying so that origin can
// be closed after copying is done, or make origin lazy too, so that it only takes system resource
// upon first read. see FileByteSource



// data must be less than 16TB (Int.MAX * 8K)

// cache is thread safe. views are not thread safe, like most ByteSource

// data are cached in 8K direct ByteBuffers.

// ----
// we could keep ByteBuffer from origin source here, and returns them in read().
// but instead, we copy origin bytes into our own direct buffers, optimizing for repeated reads by http server:
//     origin bytes may not be direct ByteBuffer.
//     origin bytes may be small.
//     origin bytes may be based on wasteful ByteBuffer (remaining<capacity).
//     origin probably prefer to have its buffers GC-ed asap
// anyway, the copying overhead is trivial, considering that the cache will be read many times.
//
// in the prev impl, if data size is known, we try to allocate one buffer as the copy buffer.
// if data size is unknown, we try to allocate buffers of increasing sizes of C*1.5^n.
// that's probably bad in a fragmented heap; and it may worsen heap fragmentation,
// especially if old caches expire and new caches come to existence.
//
// in this impl we use uniformed 8K buffers (except the last buffer).
// even if source is big, the overhead of having numerous 8K buffers should be fine.


public class ByteSourceCache
{
    ByteSource origin;
    volatile Long dataSize_volatile; // may start as null, becoming non-null after copying is done

    final Object lock(){ return this; }

    enum State {pre, copying, done, error }
    volatile State state_volatile;

    String copyErrorMsg;

    ArrayList<ByteBuffer> copyBuffers = new ArrayList<>();  // note: return only duplicates to views

    ArrayList<WaitingView> waitingList = new ArrayList<>();


    /**
     * Create an in-memory cache of the origin source.
     *
     * @param totalBytes
     *        total number of bytes in the origin source.  null if unknown.
     */
    // if dataSize conflicts with actual bytes read from origin, it's an error.
    public ByteSourceCache(ByteSource origin, Long totalBytes)
    {
        _Util.require(totalBytes==null || totalBytes.longValue()>=0, "totalBytes==null || totalBytes>=0");

        this.origin = origin;
        this.dataSize_volatile = totalBytes;

        synchronized (lock())
        {
            this.state_volatile = State.pre;  // lazy, don't start copying yet
        }
    }

    /**
     * Return the total number of bytes in the origin source; null if unknown yet.
     * <p>
     *     This number may be unknown initially (because it was not supplied to the constructor).
     *     However, it eventually becomes known after the cache reads all bytes from
     *     the origin source (barring any error).
     * </p>
     * <p>
     *     This number may be useful, for example, in Content-Length header of http responses.
     * </p>
     */
    public Long getTotalBytes()
    {
        return dataSize_volatile;   // may change from null to non-null
    }





    /*
        there are 2 types of concurrent players
        1. the copier. it reports copy progress to cache:
                publisher buffer
                copy done
                copy error
        2. views. they nag cache to
                open view
                request data
                cancel waiting
     */








    // copier started by first newView()

    void copier_publishBuffer(final ByteBuffer buffer) throws OutOfMemoryError
    {
        ArrayList<WaitingView> readyList;
        synchronized (lock())
        {
            copyBuffers.add(buffer); // throws OutOfMemoryError

            readyList = new ArrayList<>(waitingList.size());
            ArrayList<WaitingView> futureList = new ArrayList<>();
            for(WaitingView w : waitingList)
            {
                if(w.view.iBuf<copyBuffers.size())  // usually all are waiting for this exact buffer
                {
                    assert w.view.iBuf==copyBuffers.size()-1;
                    readyList.add(w);
                }
                else  // view skipping ahead. needs to wait longer. rare case.
                    futureList.add(w);
            }
            waitingList = futureList;
        }

        for(final WaitingView w : readyList)
        {
            w.view.iBuf++;
            w.promise.succeed(buffer.asReadOnlyBuffer()); // return a duplicate for each view
            // completion callback of promise is invoked by _AsyncExec
        }
    }

    void copier_done(long bytesCopied, ByteBuffer lastBuffer)
    {
        origin.close();
        origin=null;

        if(dataSize_volatile == null)
            dataSize_volatile = new Long(bytesCopied);

        synchronized (lock())
        {
            if(lastBuffer!=null)
                copyBuffers.add(lastBuffer);

            state_volatile = State.done;
        }
        terminateWaitingList(State.done);
    }

    void copier_error(Exception e)
    {
        origin.close();
        origin=null;

        // this is a serious problem. must be investigated.
        String msg1 = e.toString();
        String msg2 = "(errorId="+ _Util.msgRef(msg1) +") " + msg1;
        _Logger.of(ByteSourceCache.class).error(msg2, e);

        synchronized (lock())
        {
            copyBuffers=null;

            copyErrorMsg = msg2;

            state_volatile = State.error;
        }
        terminateWaitingList(State.error);
    }

    void terminateWaitingList(final State state)
    {
        for(final WaitingView w : waitingList)
        {
            view_read2(state, w.view, w.promise);
            // completion callback of promise is invoked on _AsyncExec
        }
        waitingList = null;
    }

    void cancelWaiting(WaitingView w, Exception reason)
    {
        // not very efficient. it should be rare that this method is called
        synchronized (lock())
        {
            if(state_volatile !=State.copying)
                return;

            boolean removed = waitingList.remove(w);
            if(!removed)  // view is served
                return;
        }
        w.promise.fail(reason);
        // view not corrupt. iBuf not changed. view.read() can be called again.
    }


    /**
     * Create a ByteSource view of the cached bytes.
     * <p>
     *     This method is thread-safe.
     * </p>
     */
    public ByteSource newView()
    {
        if(state_volatile == State.pre)
        {
            boolean first = false;
            synchronized (lock())
            {
                if(state_volatile == State.pre)
                {
                    first = true;
                    state_volatile = State.copying;
                }
            }
            if(first)
            {
                new Copier(this, dataSize_volatile)
                    .run();
                // the copier could monopolize the current thread for some time.
                // to fix that, change origin.read() so it doesn't always complete immediately.
            }
        }

        return new View(this);
    }

    Async<ByteBuffer> view_read(final View view)
    {
        State _state;  // most commonly state=done
        if((_state= state_volatile)==State.copying)  // need to lock during copying, while things are changing
        {
            synchronized (lock())
            {
                if((_state= state_volatile)==State.copying)
                {
                    if(view.iBuf < copyBuffers.size())
                        return view_read3(view, null);

                    WaitingView w = new WaitingView(view);
                    waitingList.add(w);
                    return w.promise;
                }
            }
        }
        // done/error (impossible that state==pre)
        return view_read2(_state, view, null);
    }

    // read when done/error. no need to lock.
    Async<ByteBuffer> view_read2(State _state, final View view, Promise<ByteBuffer> promise)
    {
        if(_state==State.error)
            return _Asyncs.fail(promise, new Exception("caching failed: " + copyErrorMsg));

        assert _state==State.done;

        if(view.iBuf < copyBuffers.size())
            return view_read3(view, promise);
        else
            return _Asyncs.fail(promise, End.instance());
    }
    // the buffer that the view is requesting is ready
    Async<ByteBuffer> view_read3(View view, Promise<ByteBuffer> promise)
    {
        ByteBuffer buf = copyBuffers.get(view.iBuf++);
        buf = buf.asReadOnlyBuffer(); // return a duplicate
        return _Asyncs.succeed(promise, buf);
    }



    static class WaitingView
    {
        View view;
        Promise<ByteBuffer> promise;

        WaitingView(View view)
        {
            this.view = view;

            promise = new Promise<>();
            promise.onCancel(reason ->
                view.cache.cancelWaiting(this, reason));
        }
    }





    static class View implements ByteSource
    {
        ByteSourceCache cache;

        int iBuf;

        View(ByteSourceCache cache)
        {
            this.cache = cache;
        }

        @Override
        public Async<ByteBuffer> read()
        {
            return cache.view_read(this);
        }

        @Override
        public long skip(long n) throws IllegalArgumentException
        {
            // since everything is in memory, we could simply return 0 here, let caller read-and-discard.
            // but if data is huge, copyBuffers is huge, it's a little faster to skip buffers internally.
            _Util.require(n>=0, "n>=0");

            // note: last buffer may be smaller than BB_SIZE
            long buffersToSkip = n/BB_SIZE;
            long iBufNew = (long)iBuf + buffersToSkip;
            final long INT_MAX = (long)Integer.MAX_VALUE;
            if(iBufNew>INT_MAX)
            {
                buffersToSkip -= (iBufNew-INT_MAX);
                iBufNew=INT_MAX;
            }
            iBuf = (int)iBufNew;  // may be beyond EOF

            return buffersToSkip * BB_SIZE; // usually < n
        }

        @Override
        public Async<Void> close(){ return Async.VOID; }

    } // class View
















    // we don't use a pool. it's not likely that we'll frequently alloc and dealloc.
    static ByteBuffer alloc(int size)
    {
        return ByteBuffer.allocateDirect(size);
    }
    static final int BB_SIZE = 8*1024;


    static class Copier implements Runnable
    {
        final ByteSourceCache cache;
        final long bytesExpected; //  <0 if unknown

        long bytesCopied;
        ByteBuffer buffer;

        Copier(ByteSourceCache cache, Long bytesExpected)
        {
            this.cache = cache;
            this.bytesExpected = (bytesExpected==null)? -1 : bytesExpected.longValue();
        }

        @Override public void run()
        {
            // begin copy loop
            // no timeout, read and cache indefinitely
            AsyncIterator.forEach(cache.origin::read, this::process)
                .then(v ->
                {
                    if (bytesExpected >= 0 && bytesCopied < bytesExpected)
                        throw new IllegalStateException(
                            "premature EOF of origin; expected:" + bytesExpected + ", read:" + bytesCopied);

                    done();
                    return Async.VOID;
                })
                .catch_(Exception.class, e ->
                {
                    err(e);
                    throw e;
                });
        }

        public void process(ByteBuffer obb) throws IllegalStateException
        {
            // got data. could be empty/spurious.
            long bytesRead = bytesCopied + obb.remaining();

            if(bytesExpected >= 0 && bytesRead > bytesExpected)
                throw new IllegalStateException(
                        "excessive data from origin; expected:"+bytesExpected+", read:"+ bytesRead);

            copyData(obb); //  throws OutOfMemoryError
            assert bytesCopied == bytesRead;

            // it's possible that now we have (bytesExpected >= 0 && bytesCopied == bytesExpected)
            // i.e. enough bytes is read, we can call done() right here.
            // we'll read again anyway, it should return End (maybe some spurious results before End)
            // if it returns excessive data, error.
        }

        void copyData(ByteBuffer obb) throws OutOfMemoryError
        {
            while(obb.hasRemaining())
            {
                if(buffer==null)
                {
                    int bufferSize = BB_SIZE;
                    if(bytesExpected>=0)
                    {
                        long bytesLeft = bytesExpected - bytesCopied;
                        if(bytesLeft<BB_SIZE) // this must be the last buffer
                            bufferSize = (int)bytesLeft;
                    }
                    buffer = alloc(bufferSize); // throws OutOfMemoryError
                }

                int n = _ByteBufferUtil.putSome(buffer, obb);
                bytesCopied += n;

                if(!buffer.hasRemaining()) // full. publish it.
                {
                    buffer.flip();
                    cache.copier_publishBuffer(buffer); // throws OutOfMemoryError
                    buffer = null;
                }
            }
        }

        void done()
        {
            ByteBuffer lastBuffer = null;
            if(buffer!=null) // only if bytesExpected unknown; otherwise the last buffer was full and published.
            {
                buffer.flip();  // not empty
                lastBuffer = packBuffer(buffer);  // reduce wasted capacity
                buffer = null;
            }

            cache.copier_done(bytesCopied, lastBuffer);
        }

        void err(Exception e)
        {
            if(buffer!=null)
            {
                buffer = null;
            }

            cache.copier_error(e);
        }

        static ByteBuffer packBuffer(ByteBuffer buffer)
        {
            if(buffer.remaining() == buffer.capacity())
                return buffer;

            // we may save just a few bytes, and copy a lot.
            // copy cost is not significant in long term.

            ByteBuffer b2;
            try
            {
                b2 = alloc(buffer.remaining());
            }
            catch (OutOfMemoryError e) // ok then, no pack
            {
                return buffer;
            }

            b2.put(buffer);
            b2.flip();  // for get
            return b2;
        }

    } // class Copier



}
