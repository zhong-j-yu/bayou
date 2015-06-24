package bayou.async;

import _bayou._async._Asyncs;
import _bayou._async._Fiber_Stack_Trace_;
import _bayou._async._WithThreadLocalFiber;
import _bayou._log._Logger;
import _bayou._tmp._Util;
import bayou.util.Result;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An analogue of java.lang.Thread for async tasks.
 *
 * <p>
 *     A fiber executes an async <em>task</em>. A fiber is <em>started</em> upon construction
 *     (i.e. the constructor starts the execution of the task).
 * </p>
 *
 * <pre>
 *     Async&lt;String&gt; fooTask(){ ... }
 *
 *     // start a fiber to execute fooTask
 *     Fiber&lt;String&gt; fiber = new Fiber&lt;&gt;( this::fooTask );
 * </pre>
 * <p>
 *     A fiber is <em>completed</em> when the task is completed.
 *     See {@link #isCompleted()}, {@link #join()}.
 * </p>
 * <pre>
 *     fiber.block(); // block the current thread until the fiber is completed.
 * </pre>
 * <p>
 *     The <em>current fiber</em> can be obtained by {@link #current() Fiber.current()}, for example
 * </p>
 * <pre>
 *     Async&lt;String&gt; fooTask()
 *     {
 *         ...
 *         System.out.println( Fiber.current().getName() );
 * </pre>
 * <p>
 *     The fiber <em>name</em> is for diagnosis purposes; see {@link #getName()} and {@link #setName(String) }.
 * </p>
 *
 *
 * <h4 id=fiber-executor>Fiber Executor</h4>
 * <p>
 *     Each fiber is associated with an {@link Executor}, for executing the fiber task and its sub-tasks.
 *     The executor can be specified when creating a new fiber, see constructor
 *     {@link #Fiber(Executor, String, Callable) Fiber(executor, name, task)}.
 * </p>
 * <p>
 *     There are {@link #defaultExecutor() default executors} that can be used for fibers.
 *     However, applications often have customized executors.
 *     For example, {@link bayou.http.HttpServer HttpServer}
 *     creates a fiber for each http connection, using
 *     {@link bayou.tcp.TcpConnection#getExecutor() TcpConnection's executor}
 *     which dispatches tasks to the selector thread of the connection.
 *     Another example, if you use Async in Swing, you may consider an executor based on
 *     {@link java.awt.EventQueue#invokeLater(Runnable) EventQueue::invokeLater}.
 * </p>
 * <p id=fiber-executor-requirements>
 *     Requirements for any fiber executor:
 * </p>
 * <ul>
 *     <li>
 *         must run tasks sequentially -- no two tasks can be run concurrently.
 *         <!-- we don't want app to worry about concurrency of tasks in a fiber -->
 *     </li>
 *     <li>
 *         must not run tasks synchronously, i.e. `execute(task)` must not run `task`
 *         on the caller thread before the method completes.
 *     </li>
 *     <li>
 *         must be thread safe -- its `execute()` method may be invoked on any thread at any time.
 *     </li>
 *     <li>
 *         should consider all submitted tasks as non-blocking.
 *     </li>
 * </ul>
 *
 *  <h4 id="fiber-stack-trace">Fiber Stack Trace</h4>
 *  <p>
 *      If {@link Fiber#enableTrace Fiber.enableTrace}==true, fibers keep track of execution paths
 *      of async actions and sub-actions.
 *      In the following example, the `foo` action invokes the `bar` sub-action:
 *  </p>
 *  <pre>
    public static void main(String[] args)
    {
        System.setProperty("bayou.async.Fiber.enableTrace", "true");
        new Fiber&lt;&gt;( () -&gt; foo() )
            .block();
    }
    static Async&lt;Void&gt; foo()
    {
        return Async.VOID
            .then( v -&gt; bar() );
    }
    static Async&lt;Void&gt; bar()
    {
        Fiber.dumpStack(); // compare to Thread.dumpStack()
        return Async.VOID;
    }
 *  </pre>
 *  <p>
 *      The fiber stack trace will show {@code main()-foo()-bar()}.
 *  </p>
 * <p>
 *     Here's a utility to dump all live fibers to System.out:
 * </p>
 * <pre>
     public static void dumpAllFibers()
     {
         for(Fiber&lt;?&gt; fiber : Fiber.getAll())
         {
             System.out.println(fiber.getName());
             for(StackTraceElement st : fiber.getStackTrace())
                 System.out.println("    "+st);
         }
     }
 * </pre>
 * <p>
 *     There is a non-trivial cost of enabling fiber stack trace.
 *     Whether to enable it on a production system should be determined by profiling.
 * </p>
 *
 *
 * <h4>See Also</h4>
 * <ul>
 *     <li>
 *         {@link FiberLocal} -- an analogue of ThreadLocal
 *     </li>
 * </ul>
 *
 */

public class Fiber<T>
{
    /*
        async actions do not necessarily need to run in fibers.
        however fiber provides the following benefits:
        - FiberLocal
        - choice of executor
        - stack trace
        - list all fibers
        let's not publicize fiber-less mode for now.
     */

    static final _Logger logger = _Logger.of(Fiber.class);

    /**
     * Whether to enable <a href="#fiber-stack-trace">fiber stack traces</a>.
     * <p>
     *     This flag is true iff system property
     *     "bayou.async.Fiber.enableTrace" is "true" when Fiber class is initialized.
     *     For example, you can place the following code in the beginning of your application:
     * </p>
     * <pre>
     *     System.setProperty("bayou.async.Fiber.enableTrace", "true");
     * </pre>
     */
    public static final boolean enableTrace = Boolean.getBoolean("bayou.async.Fiber.enableTrace");
    // if on, HttpServerTest GET / throughput drops to 70%.

    static
    {
        if(enableTrace)
            System.out.printf("##%n## bayou.async.Fiber.enableTrace=true%n##%n");
    }
    // see HttpServerTest for example fiber trace dump

    // ===============================================================================================================

    static final ThreadLocal<Fiber<?>> threadLocal = new ThreadLocal<>();
    static Fiber<?> getLocalFiber()
    {
        Thread t = Thread.currentThread();
        if(t instanceof _WithThreadLocalFiber)
            return (Fiber<?>)((_WithThreadLocalFiber)t).getThreadLocalFiber();
        else
            return threadLocal.get();
    }
    static void setLocalFiber(Fiber<?> fiber)
    {
        Thread t = Thread.currentThread();
        if(t instanceof _WithThreadLocalFiber)
            ((_WithThreadLocalFiber)t).setThreadLocalFiber(fiber);
        else
            threadLocal.set(fiber);
    }

    /**
     * Return the current fiber, or null if there is none.
     * <p>
     *     If the current code is being executed in a fiber executor, this method returns that very fiber.
     *     See {@link #getExecutor()}.
     * </p>
     */
    public static Fiber<?> current()
    {
        return getLocalFiber();
    }

    // we need fast add/remove, and low memory. don't care too much about iteration performance.
    // we may write a custom collection in future.
    static final ConcurrentHashMap<Fiber<?>, Object> allFibers = new ConcurrentHashMap<>();

    /**
     * Get all fibers that are not completed.
     * <p>
     *     The returned collection is unmodifiable by caller, but it may appear to be changing,
     *     because new fibers are being created, and existing fibers are being completed.
     * </p>
     */
    public static Collection<Fiber<?>> getAll()
    {
        return Collections.unmodifiableSet( allFibers.keySet() );
    }


    // ===============================================================================================================

    // original motivations of async executor
    // 1. to avoid deep stack.
    //    if action1 triggers action2 ... it could cause very deep stack.
    //    simple solution: submit action2 to an executor that runs it async-ly. that's usually costly though.
    //    with async executor, we can schedule action2 to be run later on the same thread, with a shallow stack.
    //    kind of to simulate tail call with trampoline
    // 2. to bring app flow back to the selector thread, reducing number of active threads.
    //    e.g. a request-response flow is originally on a selector thread.
    //    it does something like fileSource.read().onCompletion(callback)
    //    when read completes, we want to invoke the callback on the selector thread.
    //    solution: onComplete() remembers the async executor of the current thread
    //    when read completes (on some IO thread), callback is submitted to that async executor.
    //    the executor schedules the callback to the selector thread event loop.
    // 3. to yield. break down a CPU-intensive task to sub tasks. on completion of each task, submit
    //    the next task to executor. this gap allows other tasks to run.


    /**
     * Get a default executor implementation that can be used for fibers.
     */
    public static Executor defaultExecutor()
    {
        return FiberDefaultExecutors.getOneExec();
    }

    /**
     * Get the executor of the current fiber.
     * <p>
     *     If {@link #current Fiber.current()}==null, return {@link #defaultExecutor()} instead.
     *     Note that this method never returns null.
     * </p>
     * <p>
     *     The current executor is used for executing user codes like
     *     `onSuccess` in {@link Async#then(bayou.util.function.FunctionX) Async.then(onSuccess)},
     *     `action` in {@link AsyncIterator#forEach(bayou.util.function.ConsumerX) AsyncIterator.forEach(action)},
     *     etc. The purpose is to execute fiber related codes in the proper fiber environment.
     * </p>
     * <p>
     *     <em>Clarification on the meaning of "current":</em> in this example
     * </p>
     * <pre>
     *     action1
     *         .then( func )
     *         ...
     * </pre>
     * <p>
     *     The current executor for `then()` is captured at the time `then()` is invoked,
     *     not when/where `action1` is completed. When `action1` is completed,
     *     `func` will be submitted to the previously captured executor.
     * </p>
     */
    public static Executor currentExecutor()
    {
        Fiber<?> fiber = current();
        if(fiber!=null)
            return fiber.getExecutor();
        else
            return defaultExecutor();

        // in the fiber-less case, the current executor should behave such that when a submitted task
        //    is running, currentExecutor() should return the same executor.
        // in the fiber-ful case, that is guaranteed - the task will see Fiber.current()==fiber.
    }

    // ===============================================================================================================

    volatile String name_volatile;
    final static AtomicLong anonFiberCount = new AtomicLong(1); // for automatic naming

    final ExecutorWrap executorWrap;

    Async<T> joiner;

    final IdentityHashMap<FiberLocal<?>, Object> fiberLocalMap = new IdentityHashMap<>();
    // don't create it lazily. it's most often used.

    // can be accessed from different threads. must synchronize on it
    ArrayList<StackTraceElement[]> traces;


    /**
     * Create a new fiber for the task, with the current executor and a generated name.
     * <p>
     *     See {@link #Fiber(Executor, String, Callable) Fiber(executor, name, task)} for details.
     * </p>
     */
    public Fiber(Callable<Async<T>> task)
    {
        this(null, null, task);
    }

    /**
     * Create a new fiber, with the specified executor, name, and task.
     * <p>
     *     The fiber is started upon construction, submitting the task to the executor.
     * </p>
     * <p>
     *     If `executor==null`, the {@link #currentExecutor()} is used.
     *     See also <a href="#fiber-executor-requirements">requirements for the executor</a>.
     * </p>
     * <p>
     *     If `name==null`, an automatically generated name is used.
     * </p>
     * <p>
     *     The `task` must be non-null.
     * </p>
     */
    public Fiber(Executor executor, String name, Callable<Async<T>> task)
    {
        // we allow null executor/name, because app may want to specify one but not the other.

        if(executor==null)
            executor = currentExecutor(); // inherit executor from current fiber
        if(executor instanceof ExecutorWrap) // for example, got from another fiber
            executor = ((ExecutorWrap)executor).executor;
        this.executorWrap = new ExecutorWrap(this, executor);

        if(name==null)
            name = "Fiber-"+anonFiberCount.getAndIncrement();
        this.setName(name);

        _Util.require(task != null, "task!=null");

        if(Fiber.enableTrace)
        {
            traces = new ArrayList<>();

            // push 1 user frame, to show where the fiber is created.
            for(StackTraceElement frame : new Exception().getStackTrace())
            {
                // skip async scaffold on the top of stack
                if(_Fiber_Stack_Trace_.isAsyncScaffold(frame) || _Fiber_Stack_Trace_.isLambdaScaffold(frame))
                    continue;

                traces.add( new StackTraceElement[]{frame} );
                break;
            }
        }

        start(task);

        allFibers.put(this, Boolean.TRUE);
    }

    /**
     * Get the executor of this fiber.
     * <p>
     *     The returned executor is not the same as the executor passed in the constructor;
     *     it's a wrapper of the latter.
     * </p>
     * <p>
     *     Any code submitted to this executor will see `Fiber.current()` equal to this fiber.
     * </p>
     * <pre>
     *     fiber0.getExecutor().execute( ()-&gt;
     *     {
     *         assert Fiber.current()==fiber0;
     *     });
     * </pre>
     */
    public Executor getExecutor()
    {
        return executorWrap;
    }

    /**
     * Get the name of this fiber.
     * <p>
     *     Methods <code>get/setName()</code> are thread-safe, with volatile read/write semantics.
     * </p>
     */
    public String getName()
    {
        return name_volatile;
    }

    /**
     * Set the name of this fiber.
     *
     * <p>
     *     Methods <code>get/setName()</code> are thread-safe, with volatile read/write semantics.
     * </p>
     *
     * @param name must be non-null
     */
    public void setName(String name)
    {
        _Util.require(name != null, "name!=null");
        this.name_volatile = name;
    }

    static class ExecutorWrap implements Executor
    {
        final Fiber fiber;
        final Executor executor;

        ExecutorWrap(Fiber fiber, Executor executor)
        {
            this.fiber = fiber;
            this.executor = executor;
        }

        @Override
        public void execute(Runnable task)
        {
            executor.execute(new TaskWrap(fiber, task));
        }
    }

    static class TaskWrap implements Runnable
    {
        final Fiber<?> fiber;
        final Runnable task;

        TaskWrap(Fiber<?> fiber, Runnable task)
        {
            this.fiber = fiber;
            this.task = task;
        }

        @Override
        public void run()
        {
            Fiber f0 = getLocalFiber();  // should be null
            setLocalFiber(fiber);
            try
            {
                task.run();
            }
            catch(RuntimeException e)
            {
                // do not bother the executor to handle it. log it and ignore it.
                logger.error("Unexpected error from task: %s", e);
            }
            finally
            {
                setLocalFiber(f0);
            }
        }
    }

    // no public start(). fiber is started in constructor.
    void start(Callable<Async<T>> task)
    {
        executorWrap.execute(() ->
        {
            Async<T> async;
            try
            {
                async = task.call();
            }
            catch (Exception e)
            {
                async = Result.failure(e);
            }

            async.onCompletion( result -> allFibers.remove(this) );

            Promise<T> joinerP=null;
            synchronized (this)
            {
                if (joiner == null)
                    joiner = async;
                else
                    joinerP = (Promise<T>) joiner;
            }
            if(joinerP!=null)
            {
                async.onCompletion(joinerP::complete);
                joinerP.onCancel(async::cancel);
            }
        });
    }

    /**
     * Whether this fiber is completed. A fiber is completed when the task is completed.
     */
    public boolean isCompleted()
    {
        // even if the fiber is completed, the fiber is still good to use, as long as executor is functioning.
        // but the fiber will not show up in getAll()

        synchronized (this)
        {
            if(joiner==null)
                return false;
            else
                return joiner.isCompleted();
        }
    }

    /**
     * Return an Async that completes when this fiber completes.
     *
     * <p>
     *     Example Usage:
     * </p>
     * <pre>
     *     anotherFiber.join()
     *         .timeout( Duration.ofSeconds(5) )
     *         .then(...)
     *         ...
     * </pre>
     *
     * <p>
     *     Any cancellation request to the returned Async will be forwarded to the fiber task.
     * </p>
     *
     * <p>
     *     Note: this method is an <em>async</em> analogue of `Thread.join()`;
     *     it <em>does not block</em> the current thread.
     *     You can do <code>join().sync()</code> instead,
     *     see {@link bayou.async.Async#sync()}.
     * </p>
     */
    public Async<T> join()
    {
        return join0();
    }
    Async<T> join0()
    {
        Async<T> async;
        synchronized (this)
        {
            if(joiner==null)
                return joiner = new Promise<>();

            async=joiner;
        }
        if(!Fiber.enableTrace || async.isCompleted())
            return async;

        // fiber trace enabled, and fiber is not completed. do not return `async`
        // create a new Promise for each join() call, so that caller fiber trace appear to be stuck at join()
        Promise<T> joinerP = new Promise<>();
        async.onCompletion(joinerP::complete);
        joinerP.onCancel(async::cancel);
        return joinerP;
        // problem! joinerP is strongly referenced by `async`, cannot be GC-ed till task is completed.
        // so each join() call increase memory footage. usually join() is called only once.
        // but if app does `while(!fiber.join().isCompleted())` we are screwed. TBA.
        // use `fiber.isCompleted()` instead.
    }

    /**
     * Block the current thread util this fiber completes. The return value is the result of the fiber task.
     * <p>
     *     If the current thread is interrupted while it's being blocked by this method,
     *     the fiber task will receive a cancellation request with `InterruptedException` as reason.
     *     Hopefully the fiber task will abort quickly, so that this method can return quickly.
     * </p>
     * <p>
     *     This method does not have a timeout parameter;
     *     try `join().timeout(duration).sync()` instead.
     * </p>
     * <p>
     *     <b>Caution:</b> This is a blocking method, which usually should not be called in an async application.
     *     Because this method blocks the current thread, deadlock is possible if it's called in a fiber
     *     executor, preventing this or other fibers to advance to completion.
     *     Be very careful if you use this method on a production system.
     * </p>
     */
    public Result<T> block()
    {
        return _Asyncs.await(this.join());
    }

    /**
     * Start an async action that idles for the specified duration, then succeeds with `null`.
     * <p>
     *     If the action is canceled with `reason=e` before the duration has passed,
     *     the action fails with `e`.
     * </p>
     * <p>
     *     This method is equivalent to `sleep(duration, (Void)null)`.
     * </p>
     */
    public static Async<Void> sleep(Duration duration)
    {
        // This method can be used in a fiber-less environment
        return Asyncs.sleep(duration, (Void)null);
    }

    /**
     * Start an async action that idles for the specified duration, then succeeds with `value`.
     * <p>
     *     If the action is canceled with `reason=e` before the duration has passed,
     *     the action fails with `e`.
     * </p>
     */
    public static <T> Async<T> sleep(Duration duration, T value)
    {
        // This method can be used in a fiber-less environment
        return Asyncs.sleep(duration, value);
    }

    // yield():
    // there's no yield. our async code always yield anyway (which is quite inefficient)
    // a yield() would be the same as Result.Success.VOID.


    // stack trace
    // ===============================================================================================================

    // a fiber may have concurrent things running; trace will become messed if that happens.

    void pushStackTrace(StackTraceElement[] t)
    {
        // called internally only when Fiber.enableTrace=true
        assert Fiber.enableTrace;

        synchronized (traces)
        {
            traces.add(t);

            if(false)
                printTraces(); // for internal testing, show fiber trace whenever it changes
        }
    }

    void popStackTrace(Object t)
    {
        // called internally only when Fiber.enableTrace=true
        assert Fiber.enableTrace;

        synchronized (traces)
        {
            // search from the rear. usually t is the last one (but not always)
            for(int i= traces.size()-1; i>=0; i--)
            {
                if(traces.get(i)==t)
                {
                    traces.remove(i);  // usually the last
                    break;
                }
            }
        }
    }

    void printTraces()
    {
        System.out.println("********************************************************************");
        for(int i= traces.size()-1; i>=0; i--)
        {
            for(StackTraceElement frame : traces.get(i))
                System.out.println(""+i+": "+frame);
            if(traces.get(i).length==0)
                System.out.println(""+i+": -");
        }
    }

    /**
     * Get the fiber stack trace.
     * <p>
     *     The returned array is a snapshot copy of the current stack trace;
     *     the caller may modify it.
     * </p>
     * <p>
     *     It's safe to invoke this method on any fiber and any thread.
     * </p>
     */
    public StackTraceElement[] getStackTrace()
    {
        if(!Fiber.enableTrace)
            return new StackTraceElement[0];

        ArrayList<StackTraceElement> list = new ArrayList<>();

        StackTraceElement[] last = null;

        if(this==Fiber.current())
        {
            last= _Fiber_Stack_Trace_.captureTrace();
            Collections.addAll(list, last);
        }

        synchronized (traces)
        {
            for(int i= traces.size()-1; i>=0; i--)
            {
                StackTraceElement[] t = traces.get(i);

                // remove some duplicate frames, caused by _AsyncDoWhile
                // but this will also hide some tail-recursive frames; we don't like that. todo
                // don't remove initial frame set by Fiber()
                if(i>0 && last!=null && _Fiber_Stack_Trace_.covers(last, t))
                    continue;

                Collections.addAll(list, t);
                last = t;
                //list.add(new StackTraceElement("---", "---", "---", 0));
            }
        }

        return list.toArray(new StackTraceElement[list.size()]);
    }

    /**
     * Dump the stack trace of the current fiber to System.err.
     * <p>
     *     If {@link Fiber#enableTrace Fiber.enableTrace}==false
     *     or {@link bayou.async.Fiber#current() Fiber.current()}==null,
     *     this method is equivalent to {@link Thread#dumpStack()}.
     * </p>
     */
    public static void dumpStack()
    {
        Fiber<?> fiber = Fiber.current();
        if(fiber==null)
        {
            new Exception("Fiber.dumpStack(): Fiber.current()==null, " +
                "dump Thread stack trace instead").printStackTrace();
            return;
        }

        if(!Fiber.enableTrace)
        {
            new Exception("Fiber.dumpStack(): Fiber.enableTrace==false, " +
                "dump Thread stack trace instead").printStackTrace();
            return;
        }

        _Fiber_Stack_Trace_ traceEx = new _Fiber_Stack_Trace_();
        traceEx.setStackTrace(fiber.getStackTrace());
        traceEx.printStackTrace();
    }


}
