package bayou.tcp;

import _bayou._async._WithPreferredFiberDefaultExec;
import _bayou._async._WithThreadLocalFiber;
import _bayou._log._Logger;
import _bayou._tmp._Exec;
import _bayou._tmp._Util;

import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

// note WindowsSelectorImpl creates one sub-selector/thread for every 1024 channels.

class SelectorThread extends Thread implements Executor, _WithThreadLocalFiber, _WithPreferredFiberDefaultExec
{
    static final _Logger logger = _Logger.of(SelectorThread.class);

    static final HashMap<Object, SelectorThread> allThreadMap = new HashMap<>();

    static SelectorThread acquire(Object id) throws Exception
    {
        SelectorThread selectorThread;
        boolean start = false;
        synchronized (allThreadMap)
        {
            selectorThread = allThreadMap.get(id);
            if(selectorThread==null)
            {
                Selector selector = Selector.open(); // throws
                selectorThread = new SelectorThread(id, selector);
                allThreadMap.put(id, selectorThread);
                start = true;
            }
            ++selectorThread.acquireCount;
        }
        if(start)
            selectorThread.start();
        return selectorThread;
    }

    static void release(SelectorThread selectorThread)
    {
        boolean stop = false;
        synchronized (allThreadMap)
        {
            --selectorThread.acquireCount;
            if(selectorThread.acquireCount==0)
            {
                allThreadMap.remove(selectorThread.id);
                stop = true;
            }
        }
        if(stop)
            selectorThread.execute( ()->selectorThread.stopRequested=true );
    }

    // tasks are serialized
    // 1 flow for all (even multiple servers), since tasks are small, quick, non-blocking.
    static final ThreadPoolExecutor orphanFlow = _Exec.newSerialExecutor("bayou selector thread #orphan");
    // the thread is useless most of time. fortunately it expires quickly


    // =======================================================================================

    interface BeforeSelect
    {
        void beforeSelect();
    }

    interface OnSelected // every SelectionKey has an attachment of this type
    {
        void onSelected(SelectionKey sk);
    }

    // =======================================================================================

    int acquireCount; // accessed only by acquire/release, under global lock.

    final Object id;
    final Selector selector; // can be accessed by other threads (for wakeup)

    // accessed only by this thread
    ArrayList<BeforeSelect> actionsBeforeSelect = new ArrayList<>(); // we expect only a few; usually just 1.
    boolean stopRequested;
    ArrayDeque<Runnable> localEvents = new ArrayDeque<>();

    // remote events from other threads. use `remoteEvents` as the lock
    final ArrayDeque<Runnable> remoteEvents = new ArrayDeque<>();
    volatile boolean remoteEventFlag_volatile;
    boolean blockingOnSelect =true;
    boolean threadKilled;

    SelectorThread(Object id, Selector selector)
    {
        super("bayou selector thread #" + id);

        this.id = id;
        this.selector = selector;
    }


    // for _WithThreadLocalFiber
    Object threadLocalFiber;
    @Override
    public Object getThreadLocalFiber()
    {
        return threadLocalFiber;
    }
    @Override
    public void setThreadLocalFiber(Object obj)
    {
        threadLocalFiber = obj;
    }

    @Override
    public Executor getPreferredFiberDefaultExec()
    {
        return this;
    }

    @Override // Executor
    public void execute(Runnable event)
    {
        Objects.requireNonNull(event);

        // this method can be called on any thread

        if(Thread.currentThread()==this)
        {
            localEvents.addLast(event);
            return;
        }

        // addEvent() called from another thread. hopefully this is not common.
        int x = 0;
        synchronized (remoteEvents)
        {
            if(threadKilled)
            {
                // orphan event, diverted to the single-threaded orphanFlow.
                // not big deal for tasks after kill, they should be light-weighted.
                x = 1; // orphanFlow.execute(event);
            }
            else
            {
                remoteEvents.addLast(event);
                remoteEventFlag_volatile = true;

                if(blockingOnSelect)
                    x=2; // selector.wakeup();
            }
        }
        if(x==1)
            orphanFlow.execute(event);
        else if(x==2)
            selector.wakeup(); // ok if selector is closed
    }

    void moveRemoteEventsToLocal()
    {
        assert Thread.holdsLock(remoteEvents);

        localEvents.addAll(remoteEvents);
        remoteEvents.clear();
        remoteEventFlag_volatile = false;
    }


    @Override
    public void run()
    {
        try
        {
            while( run1() ) continue;
        }
        catch(RuntimeException|Error t) // unrecoverable; `this` is corrupt.
        {
            _Util.logUnexpected(logger, t); // extra log
            throw t;
            // residue events and future events won't be run
        }
        finally
        {
            _Util.closeNoThrow(selector, logger);
        }
    }

    boolean run1()
    {
        for(BeforeSelect action : actionsBeforeSelect)
            action.beforeSelect();

        int selectR;
        try
        {
            if(blockingOnSelect) // can read it without sync{}, cause only this thread writes to it.
            {
                selectR = selector.select();     // await channel events

                synchronized (remoteEvents)
                {   blockingOnSelect = false;   }
            }
            else
            {
                selectR = selector.selectNow(); //  peek channel events
            }
        }
        catch (Exception t) // fatal, can't handle
        {
            throw new RuntimeException(t);
        }

        // channel events ==============================================================================
        // process acceptable/readable/writable/connectable events. may generate local events
        if(selectR>0)
        {
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            for(SelectionKey sk : selectedKeys)
            {
                assert sk.isValid(); // no other threads mess with the key.

                OnSelected handler = (OnSelected)sk.attachment();
                handler.onSelected(sk); // may add local events
            }
            selectedKeys.clear();
        }

        // local event loop =========================================================================

        // local event loop may be dominated by some channels (by successively adding new events)
        // so that we don't have a chance to check channel events, or update channel interests.
        // so we'll run loop only for a finite time, then exit to take care of the two concerns.
        long loopEndTime = System.currentTimeMillis() + 100;  // need to be sufficiently long
        // apparently the precision of currentTimeMillis() is 1 on modern computers
        // we don't use nanoTime(), which could be slow (700ns on Win7?)

        int iLoop=0;

        while(true)
        {
            if(remoteEventFlag_volatile)  /*0*/
            {
                synchronized (remoteEvents)
                {
                    moveRemoteEventsToLocal();
                }
            }

            Runnable event = localEvents.pollFirst();
            if(event==null)
            {
                synchronized (remoteEvents)
                {
                    if(!remoteEventFlag_volatile) // local & remove events are depleted. end this event loop
                    {
                        if(stopRequested)
                        {
                            // kill this selector thread. no more local events,
                            // no more channel events (all channels should have been closed)
                            // divert future remote events to orphanFlow
                            threadKilled = true;
                            return false;  // exit run()
                        }
                        else // awaiting channel events, block on select()
                        {
                            blockingOnSelect = true;
                            break;
                        }
                    }
                    else // remote events. should be rare, coz we just checked the flag at /*0*/
                    {
                        moveRemoteEventsToLocal();
                        // goto /*1*/, then goto /*2*/
                    }
                }
                /*1*/
                event = localEvents.pollFirst(); // non null
            }

            /*2*/
            try
            {
                event.run(); // may add more local events
            }
            catch (RuntimeException e) // in case user code throws unexpectedly
            {
                _Util.logUnexpected(logger, e);
                // one chann flow may be corrupted; but keep the selector going
            }

            if( ( ++iLoop & ((1<<10)-1) ) !=0 )  // loop at least 2^10 times before checking time
                continue;

            if(System.currentTimeMillis()>loopEndTime)
            {
                // blockingOnSelect==false, we'll do a selectNow() to peek channel events
                break;
                // there is a very tiny chance that local&remote events are depleted here in which case
                // we should do a blocking select(); or stopRequested=true in which case we should kill.
                // leave those cases to the next event loop.
            }

        } // while(true)

        return true; // repeat, goto select()/selectNow()
    }



}
