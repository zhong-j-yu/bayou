package bayou.async;

import _bayou._async._WithPreferredFiberDefaultExec;
import _bayou._async._WithThreadLocalFiber;
import _bayou._log._Logger;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

class FiberDefaultExecutors
{
    // we don't want threads to stay forever. exit if there's no task.
    public static final long threadKeepAliveMs = Long.getLong(FiberDefaultExecutors.class.getName()+
        ".threadKeepAliveMs", 100L).longValue();
    // must be >0. default 100ms is probably long enough.

    static final int N = Runtime.getRuntime().availableProcessors();
    static final Exec[] executors = new Exec[N];
    static
    {
        for(int i=0; i<N; i++)
            executors[i] = new Exec(i);
    }

    static final AtomicInteger select = new AtomicInteger(0);

    static Executor getOneExec()
    {
        Thread thread = Thread.currentThread();
        if(thread instanceof _WithPreferredFiberDefaultExec) // see [inside-local-loop]
            return ((_WithPreferredFiberDefaultExec)thread).getPreferredFiberDefaultExec();

        int random = select.getAndIncrement() % N;
        return executors[random];
    }

    static class Exec implements Executor
    {
        final Object lock = new Object();
        final int id;
        ExecThread execThread;

        Exec(int id)
        {
            this.id = id;
        }

        @Override
        public void execute(Runnable event)
        {
            Objects.requireNonNull(event);

            if(Thread.currentThread()==execThread) // see [inside-local-loop]
            {
                execThread.addLocal(event);
                return;
            }
            // it's possible that current thread is a different ExecThread

            ExecThread newThread=null;
            synchronized (lock)
            {
                if(execThread==null)
                    execThread = newThread = new ExecThread(this);
                execThread.addRemote(event);
            }
            if(newThread!=null)
                newThread.start();
        }
    }

    static class ExecThread extends Thread implements _WithPreferredFiberDefaultExec, _WithThreadLocalFiber
    {
        final Exec exec;
        final Object lock;

        final ArrayDeque<Runnable> remoteEvents = new ArrayDeque<>();
        volatile boolean remoteEventFlag_volatile;

        final ArrayDeque<Runnable> localEvents = new ArrayDeque<>();

        ExecThread(Exec exec)
        {
            super("Fiber Default Executor #"+exec.id);

            this.exec = exec;
            this.lock = exec.lock;
        }

        @Override
        public Executor getPreferredFiberDefaultExec()
        {
            return exec;
        }

        void addLocal(Runnable event)
        {
            localEvents.addLast(event);
        }

        void addRemote(Runnable event) // under lock
        {
            remoteEvents.addLast(event);
            remoteEventFlag_volatile = true;
            lock.notify();
        }

        void moveRemoteToLocal() // under lock
        {
            localEvents.addAll(remoteEvents);
            remoteEvents.clear();
            remoteEventFlag_volatile = false;
        }

        @Override
        public void run()
        {
            while(true)
            {
                if(remoteEventFlag_volatile)
                {
                    synchronized (lock)
                    {
                        moveRemoteToLocal();
                    }
                }

                Runnable event = localEvents.pollFirst();
                if(event==null) // await remote event
                {
                    synchronized (lock)
                    {
                        if(remoteEvents.isEmpty())
                        {
                            try
                            {
                                lock.wait(threadKeepAliveMs);
                                // note: not the usual loop pattern of:   while() lock.wait()
                            }
                            catch (InterruptedException e){ /**/ }

                            if(remoteEvents.isEmpty())
                            {
                                // timeout, interrupt, or spurious wakeup. this thread exits.
                                exec.execThread=null;
                                return;
                            }
                        }
                        moveRemoteToLocal();
                    }
                    event = localEvents.pollFirst(); // non null
                }

                try
                {
                    event.run(); // [inside-local-loop]
                    // user code, may invoke:
                    //   getOneExec()    -  return same exec
                    //   Exec.execute()  -  append to local queue
                }
                catch (RuntimeException e)
                {
                    _Logger.of(FiberDefaultExecutors.class).error("Unexpected error from task: %s", e);
                }

            } // while(true)

        } // run()


        // _WithThreadLocalFiber
        Object threadLocalFiber;
        @Override
        public Object getThreadLocalFiber()
        {
            return threadLocalFiber;
        }
        @Override
        public void setThreadLocalFiber(Object obj)
        {
            this.threadLocalFiber = obj;
        }

    } // class ExecThread

}
