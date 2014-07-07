package bayou.async;

import _bayou._async._Asyncs;
import _bayou._async._Fiber_Stack_Trace_;
import bayou.util.Result;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * An implementation of `Async` for result producers.
 * <p>
 *     A result producer creates a Promise&lt;T&gt; and presents it to result consumers as Async&lt;T&gt;.
 *     The result producer later completes the Async when the result is available,
 *     through `succeed(), fail(), or complete()` methods.
 * </p>
 * <h4 id=the-cancellation-request><em>The</em> cancellation request</h4>
 * <p>
 *     Each `cancel(reason)` sends a cancellation request to this Promise.
 *     However, only the 1st request, before completion of this promise, is effective.
 *     We call it <i><b>the</b> cancellation request</i> for this Promise.
 *     Other requests are ignored.
 * </p>
 * <h4 id=cancellation-listener>Cancellation Listener</h4>
 * <p>
 *     The result producer can register cancellation listeners, through `onCancel(listener)`,
 *     to react to <em>the</em> cancellation request.
 * </p>
 * <p>
 *     Multiple cancellation listeners can be registered. A result producing process may involve
 *     a series of steps, and each step requires a different cancellation listener.
 *     We consider that once a step is completed, its cancellation listener becomes useless.
 *     For the sake of garbage collection, a registered cancellation listener is discarded
 *     when the next one is registered, or when the Promise is completed.
 *     The Promise keeps at most one cancellation listener at a time.
 * </p>
 * <p>
 *     When <em>the</em> cancellation request arrives, the current registered cancellation listener (if any) is triggered.
 *     Any future cancellation listener is triggered as soon as it's registered (if before completion).
 * </p>
 * <p>
 *     If you do need multiple concurrent cancellation listeners,
 *     you can register one master listener, and manage sub-listeners by yourself.
 * </p>
 *
 * <h4 id=associated-fiber>Associated Fiber</h4>
 * <p>
 *     At the time of construction `new Promise()`, the {@link bayou.async.Fiber#current() current fiber}
 *     is associated with this Promise. Note that the fiber can be null, which is not a fatal problem.
 * </p>
 *
 */
@SuppressWarnings({"SynchronizationOnLocalVariableOrMethodParameter", "ConstantConditions"})
public class Promise<T> implements Async<T>
{
    volatile Pending<T> pending_volatile;
    volatile Result<T> result_volatile;
    // during pending phase, must synchronize on the pending object.
    //    if((pending=pending_volatile)!=null)
    //        synchronized (pending)
    //            if((pending=pending_volatile)!=null)
    // when complete, result!=null, pending=null.
    //     pending_volatile==null => result_volatile!=null

    static class Pending<T>
    {
        Consumer<Result<T>> callbacks;  // null, single callback, or a CallbackList

        Consumer<Exception> cancelListener;
        volatile Exception cancelReason_volatile;
    }
    static class PendingDebug<T> extends Pending<T>
    {
        Fiber<?> fiber; // may be null
        ArrayDeque<Object> traces = new ArrayDeque<>();
    }

    /**
     * Create a Promise, to be completed later.
     * <p>
     *     Every Promise should eventually be completed.
     * </p>
     * <p>
     *  The {@link bayou.async.Fiber#current() current fiber}
     *     is <a href="#associated-fiber">associated</a> with this Promise.
     * </p>
     * <p>
     *     Note: this constructor automatically does a {@link #fiberTracePush()};
     *     if that's not desired, do {@link #fiberTracePop()} immediately after construction.
     *     If a Promise is created but never completed, the fiber stack trace it pushed in the constructor
     *     may never be popped.
     * </p>
     */
    public Promise()
    {
        ForEachNoAsync.warn();

        if(!Fiber.enableTrace)
        {
            pending_volatile = new Pending<>();
        }
        else
        {
            PendingDebug<T> pendingDebug = new PendingDebug<>();
            pending_volatile = pendingDebug;

            pendingDebug.fiber = Fiber.current(); // null is ok, not fatal.
            fiberTracePush(); // if user don't like this push, do a pop after constructor.
        }
    }

    /**
     * Complete this Promise with success of value `v`.
     * <p>
     *     This method is equivalent to `complete(Result.success(v))`.
     * </p>
     * @param v
     *        the success value; can be null.
     * @throws IllegalStateException
     *         if this Promise has already been completed.
     */
    public void succeed(T v) throws IllegalStateException
    {
        complete(Result.success(v));
    }

    /**
     * Complete this Promise with failure of exception `e`.
     * <p>
     *     This method is equivalent to `complete(Result.failure(e))`.
     * </p>
     * @param e
     *        the failure exception; must be non-null.
     * @throws IllegalStateException
     *         if this Promise has already been completed.
     */
    public void fail(Exception e) throws IllegalStateException
    {
        complete(Result.failure(e));  // throws if failure==null
        // Result.failure(e) adds current fiber trace to e. may be different from my fiber.
    }

    /**
     * Complete this Promise with `result`.
     * <p>
     *     Multiple <code>complete()</code> calls are not tolerated.
     *     This method can only be invoked if this promise has not been completed.
     * </p>
     * @throws IllegalStateException
     *         if this Promise has already been completed.
     */
    public void complete(Result<T> result) throws IllegalStateException
    {
        Objects.requireNonNull(result);

        Pending<T> pending;
        if((pending=pending_volatile)!=null)
        {
            synchronized (pending)
            {
                if((pending=pending_volatile)!=null)
                {
                    result_volatile = result;
                    pending_volatile = null;
                }
            }
        }

        if(pending==null)
            throw new IllegalStateException("Promise already completed");

        if(pending.callbacks!=null)
            pending.callbacks.accept(result);
        // note: each callback is already wrapped by AsyncExec

        // cancel info in pending is no longer relevant after completion.

        if(Fiber.enableTrace)
        {
            PendingDebug<T> debug = (PendingDebug<T>)pending;
            if(debug.fiber!=null)
            {
                Exception ex = result.getException();
                if(ex!=null) // add fiber trace to ex. do this before popStackTrace
                    _Fiber_Stack_Trace_.addFiberStackTrace(ex, debug.fiber); // may be done already

                while(!debug.traces.isEmpty())
                    debug.fiber.popStackTrace(debug.traces.removeLast());
            }
        }

        // pending becomes garbage
    }




    /**
     * Register a <a href="#cancellation-listener">cancellation listener</a>.
     * A cancellation listener may be triggered if/when <em>the</em> cancellation request arrives.
     * <p>
     *     If this Promise has been completed, this method has no effect.
     * </p>
     * <p>
     *     This listener may cause the previous registered listener to be discarded.
     * </p>
     * <p>
     *     The `listener`
     *     will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    public void onCancel(Consumer<Exception> listener)
    {
        Objects.requireNonNull(listener);

        // bind callback to current async exec
        listener = _Asyncs.bindToCurrExec(listener);

        Exception _cancelReason=null;
        Pending<T> pending;
        if((pending=pending_volatile)!=null)
        {
            synchronized (pending)
            {
                if((pending=pending_volatile)!=null)
                {
                    _cancelReason = pending.cancelReason_volatile;
                    if(_cancelReason==null)
                    {
                        pending.cancelListener = listener;  // prev listener is discarded
                        return;
                    }
                    // else, cancel called, invoke listener immediately async-ly
                }
            }
        }

        if(_cancelReason!=null)
            listener.accept(_cancelReason);  // async-ly
    }

    /**
     * Poll <em>the</em> cancellation request.
     * <p>
     *     If this Promise has not been completed, and cancel(reason) was invoked,
     *     this method returns `reason` of <a href="#the-cancellation-request"><em>the</em> cancellation request</a>;
     *     otherwise returns null.
     * </p>
     * <p>
     *     This method is needed by some result producers to poll and react to cancellation requests.
     *     Note that once this Promise is completed, this method returns null --
     *     the producer should have no need for this method after completion.
     * </p>
     */
    public Exception pollCancel()
    {
        Pending<T> pending;
        if((pending=pending_volatile)!=null)
            return pending.cancelReason_volatile;

        return null;
    }




    /**
     * Implements {@link Async#pollResult()}.
     */
    @Override
    public Result<T> pollResult()
    {
        return result_volatile;
    }


    /**
     * Implements {@link Async#onCompletion(Consumer)}.
     */
    @Override
    public void onCompletion(Consumer<Result<T>> callback)
    {
        Objects.requireNonNull(callback);

        // bind callback to current async exec
        callback = _Asyncs.bindToCurrExec(callback);  // often, callback was already wrapped.

        // most likely onComplete(callback) is called before completion
        Pending<T> pending;
        if((pending=pending_volatile)!=null)
        {
            synchronized (pending)
            {
                if((pending=pending_volatile)!=null)
                {
                    pending.callbacks = CallbackList.concat(pending.callbacks, callback);
                    return;
                }
            }
        }

        // completed. invoke callback immediately (but async-ly)
        Result<T> result = result_volatile;
        callback.accept(result);  // callback was wrapped by AsyncExec
    }


    /**
     * Implements {@link Async#cancel(Exception)}.
     * <p>
     *     Send a cancellation request to this Promise,
     *     which may trigger current/future cancellation listeners.
     * </p>
     */
    @Override
    public void cancel(Exception reason)
    {
        Objects.requireNonNull(reason);

        Consumer<Exception> _cancelListener=null;

        Pending<T> pending;
        if((pending=pending_volatile)!=null)
        {
            synchronized (pending)
            {
                if((pending=pending_volatile)!=null)
                {
                    if(pending.cancelReason_volatile!=null)
                        return;

                    pending.cancelReason_volatile = reason;

                    _cancelListener = pending.cancelListener;
                    pending.cancelListener = null;
                    // invoke cancelListener now, async-ly
                }
            }
        }

        if(_cancelListener!=null)
            _cancelListener.accept(reason);   // async-ly, cancelListener was wrapped by AsyncExec
    }





    /**
     * Implements {@link Object#toString()}.
     */
    @Override
    public String toString()
    {
        Result<T> result = pollResult();
        if(result==null)
            return "Promise:Pending";
        else if(result.isSuccess())
            return "Promise:Success: "+result.getValue();
        else
            return "Promise:Failure: "+result.getException();
    }


    // fiber trace push/pop
    // should be done in sequence, paired, before completion.
    // don't worry about concurrency regarding push/pop/completion

    /**
     * Add the current thread stack trace to the fiber stack trace.
     *
     * <p>
     *     If {@link Fiber#enableTrace}==false, or the <a href="#associated-fiber">associated fiber</a> is null,
     *     this method has no effect.
     * </p>
     *
     * <p>
     *     This method is used by the result producer at strategic code locations
     *     to establish informative fiber stack trace. For example
     * </p>
     * <pre>
     *     promise.fiberTracePush();              // line[1]
     *     addEventListener( (event) -&gt;
     *     {
     *         promise.fiberTracePop();
     *         handle event...
     *     });
     * </pre>
     * <p>
     *     while the result producer is awaiting the event,
     *     the fiber stack trace will show line[1] at the top.
     * </p>
     * <p>
     *     Note: the constructor {@link #Promise()} automatically does a `fiberTracePush()`;
     *     if that's not desired, do a `fiberTracePop()` immediately after construction.
     * </p>
     */
    public void fiberTracePush()
    {
        if(!Fiber.enableTrace)
            return;

        Pending<T> pending = pending_volatile;
        if(pending==null) // push after completion, programming error
            throw new IllegalStateException("Promise already completed");
        PendingDebug<T> debug = (PendingDebug<T>)pending;
        if(debug.fiber==null)
            return;

        StackTraceElement[] trace = _Fiber_Stack_Trace_.captureTrace();
        debug.traces.addLast(trace);
        debug.fiber.pushStackTrace(trace);
    }

    /**
     * Remove the last pushed fiber stack trace. See {@link #fiberTracePush()}.
     *
     * <p>
     *     If {@link Fiber#enableTrace}==false, or the <a href="#associated-fiber">associated fiber</a> is null,
     *     this method has no effect.
     * </p>
     *
     * <p>
     *     On completion of this Promise, all previously pushed fiber stack traces by this Promise
     *     will be popped automatically.
     * </p>
     */
    public void fiberTracePop()
    {
        if(!Fiber.enableTrace)
            return;

        Pending<T> pending = pending_volatile;
        if(pending==null) // pop after completion. not serious problem. ignore silently.
            return;
        PendingDebug<T> debug = (PendingDebug<T>)pending;
        if(debug.fiber==null)
            return;

        Object trace = debug.traces.removeLast();  // throws
        debug.fiber.popStackTrace(trace);
    }


}
