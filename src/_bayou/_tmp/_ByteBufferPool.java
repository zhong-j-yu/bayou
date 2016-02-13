package _bayou._tmp;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

// expiration based pool for ByteBuffer. reason for pool:
//   to reduce alloc/de-alloc cost; significant for direct buffers(10K cycles for alloc?)
//   to reduce zeroing costs; significant for direct and non-direct buffers. (2 cycles per byte?)

// in a server, the best choice for expiration is never. server should have big enough dedicated memory.
// since the server can survive the peak load, it's ok to keep all the buffers, for next peak.
// however that's not usually what people expect. so we have an expiration for the pool. when load decreases,
// items in pool are evicted after expiration.

// the pool is most useful to buffer short term load jitter.
// define load L(t) as the total checked out items at time t. it's usually very jittery.
// there are rapid check-out and check-in actions microscopically.
// smooth the load curve, with expiration as the time window, to Ls(t). Ls'(t) is rate of alloc/dealloc.
// expiration should be long enough to smooth out the jitter, so that Ls'(t) is acceptably low.

// there is a system wide default expiration, specified by sys prop bayou.util.ByteBufferPool.defaultExpiration, or 60s.
// the default is used by our framework for most pools. 60s should be long enough to absorb load jitter
// in typical web apps. it is short enough to release memory promptly when load decreases.

// items in pool are tagged with the time they are checked in. check-out always pick the newest item.
// this is good for cpu cache. the older an item is, the more likely it'll stay longer. when it stays
// past expiration, it means the item wasn't really needed for all that time.

// since pool is expiration based, generally it doesn't matter to merge or split pools.
// suppose two sub systems share a pool, and their load curves are roughly proportional,
// then each can have its own pool, without significantly increasing total number of buffers.

// which suggests that we COULD shard by thread. for each pool, have a local pool per thread.
// that is justifiable in a server, suppose different threads have similar load curves.
// in the async server, a thread can be serving multiple overlapping requests, so the local
// pool may have multiple items checked out.
// thread local pool is good to reduce synchronization. maybe also good for cpu cache.
// even without thread-cpu affinity, in an extended time window, a thread is run on one cpu,
// using the local pool, buffers of that pool are exclusively w/r by a single cpu.

// HOWEVER, we are afraid that it may cause great waste under some situations, e.g. one thread
// may be hoarding many useless items, and another thread keeps allocating new items.
// to be conservative, have a central pool shared by all threads.
// THEN, for each thread, we have 1 locally cached item. that should be very helpful in most
// apps where back to back checkOut-checkIn and checkIn-checkOut are the majority use case.
// (possible an item is checked out on thread1 and checked in on thread2.
// this local cache is disabled by default, to avoid many lingering buffers on many threads.
// though it's more expensive to use shared cache, it's probably not big deal compared to file/socket IO

// performance @2.5Hz, checkOut+checkIn
//    local: 10ns
//    shared:  uncontended: 150ns, contended(2): 520ns
public class _ByteBufferPool
{
    static final long defaultExpiration = Long.getLong(_ByteBufferPool.class.getName()+
            ".defaultExpiration", 60_000L).longValue();

    static final boolean defaultDoLocalCache = _Util.booleanProp(true,
        _ByteBufferPool.class.getName()+".defaultDoLocalCache");

    final Object lock(){ return this; }
    // when `this` becomes garbage, localCache becomes garbage, and all of
    // its local entries on all threads become garbage too.
    final ThreadLocal<ByteBuffer> localCache;

    final int bufferCapacity;
    final boolean allocateDirect;
    final long expiration;

    Entry oldest, newest; // double linked list
    // todo: why linked list? just use ArrayDeque

    public _ByteBufferPool(int bufferCapacity, boolean allocateDirect, long expiration, boolean doLocalCache)
    {
        this.bufferCapacity = bufferCapacity;
        this.allocateDirect = allocateDirect;
        this.expiration = expiration;

        this.localCache = doLocalCache? new ThreadLocal<ByteBuffer>() : null;
    }
    public _ByteBufferPool(int bufferCapacity)
    {
        this(bufferCapacity, true, defaultExpiration, defaultDoLocalCache);
    }

    // todo: use weak ref for _ByteBufferPool, so that they can be GC-ed.
    // tho the pool itself is not a heavy object, it prevents the ThreadLocal localCache from GC-ed.
    static final ConcurrentHashMap<Integer, _ByteBufferPool> cachedPools = new ConcurrentHashMap<>();
    public static _ByteBufferPool forCapacity(int bufferCapacity)
    {
        _ByteBufferPool pool = cachedPools.get(bufferCapacity);
        if(pool!=null)
            return pool;

        _ByteBufferPool poolA = new _ByteBufferPool(bufferCapacity);
        _ByteBufferPool poolB = cachedPools.putIfAbsent(bufferCapacity, poolA);
        return poolB!=null? poolB : poolA;
    }


    public int getBufferCapacity()
    {
        return bufferCapacity;
    }
    public boolean isAllocateDirect()
    {
        return allocateDirect;
    }
    public long getExpiration()
    {
        return expiration;
    }



    public ByteBuffer checkOut()throws OutOfMemoryError
    {
        stat(1, 0);

        if(localCache!=null)
        {
            ByteBuffer local = localCache.get();
            if(local!=null)
            {
                localCache.set(null);
                // set(null) instead of remove(). we expect that a check-in follows soon.
                return local;
            }
        }
        return checkOut2();
    }
    // pool now owns `bb`; it can be checkout; it can be de-allocated.
    // it is critical that nobody else is still using `bb`.
    public void checkIn(ByteBuffer bb)
    {
        boolean compatible =
                bb!=null
                && bb.capacity() == bufferCapacity
                && bb.isDirect() == allocateDirect
                && !bb.isReadOnly();

        if(!compatible)
            throw new AssertionError("the buffer is not from this pool: "+bb);
        // if bb wasn't from this pool and we accept it, caller's bug.

        stat(0, 1);

        bb.clear();

        if(localCache!=null)
        {
            ByteBuffer local = localCache.get();
            if(local==null)
            {
                localCache.set(bb);
                return;
            }
        }
        checkIn2(bb);
    }

    static class Entry
    {
        Entry older;
        Entry newer;

        ByteBuffer bb;
        long time;
        Entry(ByteBuffer bb, long time)
        {
            this.bb = bb;
            this.time = time;
        }
    }

    ByteBuffer checkOut2()throws OutOfMemoryError
    {
        synchronized (lock())
        {
            // remove and return newest.
            ByteBuffer bb = checkOut3();
            if(bb!=null)
                return bb;
        }

        return alloc(bufferCapacity, allocateDirect);  // outside lock{}
    }
    void checkIn2(ByteBuffer bb)
    {
        Entry entry = new Entry(bb, System.currentTimeMillis());

        Entry e2;
        synchronized (lock())
        {
            // install entry as the new newest.
            // return the youngest end of expired chain
            e2 = checkIn3(entry);
        }

        while(e2!=null)
        {
            // dealloc() is very dangerous and must be used correctly.
            // this pool is only used internally by trusted code, checkIn-checkOut should be perfectly paired.
            _ByteBufferUtil.dealloc(e2.bb);
            e2=e2.older;
        }
    }

    ByteBuffer checkOut3()throws OutOfMemoryError
    {
        Entry n = newest;
        if(n==null)
            return null;
        Entry n2 = n.older;
        if(n2==null)
        {
            newest=null;
            oldest=null;
        }
        else
        {
            n.older = null; // help GC?
            n2.newer = null;
            newest = n2;
        }
        return n.bb;
    }
    Entry checkIn3(Entry entry)
    {
        Entry n = newest;
        if(n==null) // could be quite common
        {
            newest = entry;
            oldest = entry;
            // only one entry, just checked in, it shouldn't be expired
            return null;
        }
        else
        {
            n.newer = entry;
            entry.older = n;
            newest = entry;
            return severExpired(entry.time - expiration);
        }
    }
    // note: we could be removing expired items while the load is increasing. likelihood should be low.
    Entry severExpired(long minTime)
    {
        // caller ensured at least two entries in pool
        Entry e1 = oldest; // not null.
        if(!(e1.time<minTime)) // fast path. non expired. very common?
            return null;

        // starting from the oldest end, find the first entry that's not expired.
        while(e1!=null && e1.time<minTime)
            e1 = e1.newer;

        if(e1==null) // all expired, incl the newest one, due to clock precision. practically impossible.
        {
            Entry e2 = newest;
            oldest=null;
            newest=null;
            return e2;
        }

        // e1 != oldest (see fast path)
        Entry e2 = e1.older; // not null. the youngest expired
        oldest=e1;
        e1.older=null;
        e2.newer=null;
        return e2;
    }

    static ByteBuffer alloc(int capacity, boolean allocateDirect) throws OutOfMemoryError
    {
        if(allocateDirect)
            return ByteBuffer.allocateDirect(capacity);
        else
            return ByteBuffer.allocate(capacity);
    }

    static final long dumpStatInterval = Long.getLong(_ByteBufferPool.class.getName()+
            ".dumpStatInterval", 0L).longValue();

    static final Object statLock = new Object();
    static long nextStatDumpTime = System.currentTimeMillis() + dumpStatInterval;
    static int checkOutCount = 0, checkInCount = 0;

    static void stat(int checkOut, int checkIn)
    {
        // this method should cost nothing on runtime if dump is not enabled
        if(dumpStatInterval<=0)
            return;

        synchronized (statLock)
        {
            checkOutCount += checkOut;
            checkInCount += checkIn;

            long now = System.currentTimeMillis();
            if(now >= nextStatDumpTime)
            {
                System.out.printf("ByteBufferPool stat: out=%d in=%d diff=%d %n",
                        checkOutCount, checkInCount, checkOutCount - checkInCount);
                nextStatDumpTime = now + dumpStatInterval;
            }
        }
    }

}
