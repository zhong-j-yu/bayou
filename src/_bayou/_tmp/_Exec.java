package _bayou._tmp;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class _Exec
{
    // we don't want threads to stay forever. exit if there's no task.
    public static final long threadKeepAliveMs = Long.getLong(_Exec.class.getName()+
            ".threadKeepAliveMs", 100L).longValue();
    // must be >0. default 100ms is probably long enough.

    static final int maxNbThreads = Integer.getInteger(_Exec.class.getName()+
            ".maxNbThreads", Runtime.getRuntime().availableProcessors()).intValue();

    static final AtomicInteger execId = new AtomicInteger(0);

    // serial executor -------------------------------------------------------------------------------
    // tasks are serialized
    // 1 thread, non daemon, expires after 1sec.
    // unbounded queue.
    public static ThreadPoolExecutor newSerialExecutor(String threadName)
    {
        ThreadPoolExecutor serialExec = new ThreadPoolExecutor (
                1,1,
                threadKeepAliveMs, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                new _NamedThreadFactory(threadName)
        );
        serialExec.allowCoreThreadTimeOut(true);
        return serialExec;
    }


    // exec non-blocking ------------------------------------------------------------------------------

    // a system wide executor for non blocking tasks (from various sub-systems)
    // is it necessary? if a task is non blocking, why not just invoke it directly? why dispatch it to an executor?
    //   reason 1: if there are many tasks to be executed, need to send them to multiple CPUs
    //   reason 2: don't want the current stack for the task; might be confusing. start fresh stack for task.
    // unbounded queue, fixed N threads, default N=N_CPU. (not sure what's the optimal number)

    static class ExecNb
    {
        static final ThreadPoolExecutor execNb = new ThreadPoolExecutor(
                maxNbThreads, maxNbThreads,
                threadKeepAliveMs, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new _NamedThreadFactory(() -> _Exec.class.getName() + " non-blocking #"+ execId.incrementAndGet())
        );
        static{ execNb.allowCoreThreadTimeOut(true); }
    }


    static public void execNb(Runnable nonBlockingTask)
    {
        ExecNb.execNb.execute(nonBlockingTask);
    }


    // exec scheduled ----------------------------------------------------------------------

    static class ExecScheduler
    {
        // a single thread for all scheduled tasks. don't do anything heavy here!
        static ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1,
                new _NamedThreadFactory(() -> _Exec.class.getName() + " scheduled executor"));
        static{ scheduler.setRemoveOnCancelPolicy(true); }
        // removal could be expensive.
        // scheduler is mainly used by promise timeout; most promises complete before timeout,
        // so remove on cancel may keep space usage low. Promise class depends on it.
        static
        {
            // ScheduledThreadPoolExecutor warns against timeout. however it seems fine to enable it.
            // if it turns out to be a problem, we'll disable timeout. maybe write our own impl.
            scheduler.setKeepAliveTime(threadKeepAliveMs, TimeUnit.MILLISECONDS);
            scheduler.allowCoreThreadTimeOut(true);
            // the timeout behavior is weird, if most tasks are cancelled before the timeout is reached.
            // e.g. use ApacheBench to send continuously requests to an http server,
            // we'll see that the scheduler thread exists quickly, immediately followed by a new thread, and so on.
            // that can be a problem.
            // but when dealing with real browser clients, where there are usually keep-alive connections
            // idling for seconds, there's no problem. so we are not worried about it at this time.
        }
    }

    // ok if delay<0, it's same as delay=0
    // CAUTION: there's only one thread for all scheduled actions.
    // action should be really short; action should dispatch heavy work to somewhere else
    static public ScheduledFuture<?> execNbDelayed(Duration delay, final Runnable action)
    {
        long nanos = delay.toNanos(); // delay must be less than 292 years or this line fails

        return ExecScheduler.scheduler.schedule(action, nanos, TimeUnit.NANOSECONDS);
    }

    // exec blocking ------------------------------------------------------------------------------

    // a system wide executor for blocking tasks, behaving similar to Executors.newCachedThreadPool().
    // this executor can be shared by sub systems to reuse threads more efficiently.

    // no limit of number of threads. it would be unwise to shelf some tasks due to lack of worker threads.
    // this is of course very dangerous. an app submitting moderate amount of tasks could see OutOfMemoryError.
    // the app then needs to set limits somewhere else (e.g. max concurrent user requests), or use a diff executor.

    static class ExecB
    {
        static final ThreadPoolExecutor exec = new ThreadPoolExecutor(
                0, Integer.MAX_VALUE,
                threadKeepAliveMs, TimeUnit.MILLISECONDS,
                new SynchronousQueue<Runnable>(),
                new _NamedThreadFactory(() -> _Exec.class.getName() + " blocking #"+ execId.incrementAndGet())
        );
    }

    static public void executeB(Runnable task)
    {
        ExecB.exec.execute(task);
    }
    public static ThreadPoolExecutor executorB()
    {
        return ExecB.exec;
    }

    // append name to the thread name. usually for long running task
    static public void executeB(final String name, final Runnable task)
    {
        executeB(() -> {
            String oldThreadName = Thread.currentThread().getName();
            Thread.currentThread().setName(oldThreadName + " (" + name + ")");

            task.run();

            Thread.currentThread().setName(oldThreadName);
        });

    }


}
