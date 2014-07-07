package bayou.async;

import _bayou._tmp._Array2ReadOnlyList;
import _bayou._tmp._Util;
import bayou.util.OverLimitException;
import bayou.util.Result;
import bayou.util.function.FunctionX;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static _bayou._tmp._Util.cast;


/**
 * Bundles multiple parallel {@code Async<T>} tasks as one {@code Async<R>} action.
 * <p>
 *     An {@code AsyncBundle<T,R>} consists of multiple child <em>tasks</em>
 *     of type {@code Async<T>} ;
 *     the <em>bundle</em> itself is an {@code Async<R>} ,
 *     which completes when all or some of the tasks are completed
 *     (determined by the <a href="#trigger-function" ><em>trigger function</em></a>).
 * </p>
 * <p>
 *     For common usages, see static methods
 *     {@link #anyOf anyOf()}, {@link #allOf allOf()}, {@link #someOf someOf()}.
 *     For example
 * </p>
 * <pre>
 *     Stream&lt;Async&lt;String&gt;&gt; tasks = ...;
 *     Async&lt;List&lt;String&gt;&gt; bundle = AsyncBundle.someOf(tasks, 2);
 *     // the bundle succeeds as soon as 2 of the tasks succeeds
 * </pre>
 *
 * <h4 id="trigger-function">Trigger Function</h4>
 * <p>
 *     The trigger function determines when and how the bundle completes.
 *     The input to the function is the results from currently completed tasks;
 *     the output of the function can be one of:
 * </p>
 * <ul>
 *     <li><b>return null</b> <br> the bundle is not yet completed; awaiting more task completions</li>
 *     <li><b>return `r`</b> (non-null, type R) <br> the bundle completes with <b>success</b> value `r` </li>
 *     <li><b>throw `e`</b> <br> the bundle completes with <b>failure</b> exception `e`</li>
 * </ul>
 * <p>
 *     The bundle first invokes the trigger function with an empty list,
 *     then with a list of one result after the first task completion,
 *     and so on, until the bundle completes.
 * </p>
 *
 * <h4>Cancellation</h4>
 * <p>
 *     When a bundle completes, the remaining uncompleted tasks are cancelled;
 *     they are no longer needed by the bundle.
 * </p>
 *
 * @param <T> value type of child tasks
 * @param <R> value type of the bundle
 */

public class AsyncBundle<T, R> implements Async<R>
{
    // O(n^2) problem
    // since trigger needs to iterate the List<Result> on every task completion,
    // the total cost is O(n^2). probably not a big deal, n is usually small.
    // if it becomes a public issue: one workaround is to use an internal List<Result>
    // that contains successCount, which thresholdTrigger() can check directly.
    // more general solution: declare that trigger is never invoked concurrently;
    // trigger can be stateful; and it can check just the newest/last Result on each invocation.

    final FunctionX<List<Result<T>>, R> trigger;
    final Promise<R> promise;


    final Object lock(){ return this; }

    Object[] taskArray; //Async<T>[]
    // we must keep tasks to forward cancel request to them. remove a task as soon as it's completed.

    Object[] resultArray; //Result<T>[]
    int resultCount;

    /**
     * Create a bundle over the tasks, with the trigger function.
     */
    public AsyncBundle(Stream<Async<T>> tasks, FunctionX<List<Result<T>>, R> trigger)
    {
        this(_Util.toArray(tasks), trigger);
    }

    AsyncBundle(Object[] taskArray, FunctionX<List<Result<T>>, R> trigger)
    {
        this.trigger = trigger;
        this.promise = new Promise<>();

        this.taskArray = taskArray;

        this.resultArray = new Object[taskArray.length];

        synchronized (lock()) // actually, no need to lock here.
        {
            // possible degenerate case: triggered on zero task completion
            checkTrigger(lock());

            for(int i=0; i< taskArray.length; i++)
            {
                final int index = i;
                Async<T> task = cast(taskArray[index]);
                task.onCompletion(result -> onTaskCompletion(index, result));
            }
        }
    }

    /**
     * Return an immutable list of results from currently completed tasks.
     */
    public List<Result<T>> pollTaskResults()
    {
        synchronized (lock())
        {
            return pollTaskResults(lock());
        }
    }
    List<Result<T>> pollTaskResults(Object lock)
    {
        return new _Array2ReadOnlyList<>(resultArray, 0, resultCount);
        // this view is immutable because resultArray only grows at the end
    }


    /**
     * Implements {@link Async#pollResult Async&lt;R&gt;.pollResult()}.
     */
    @Override
    public Result<R> pollResult()
    {
        return promise.pollResult();
    }

    /**
     * Implements {@link Async#onCompletion Async&lt;R&gt;.onCompletion()}.
     */
    @Override
    public void onCompletion(Consumer<Result<R>> callback)
    {
        promise.onCompletion(callback);
    }

    /**
     * Implements {@link Async#cancel Async&lt;R&gt;.cancel()}.
     * <p>
     *     The cancel request will be forwarded to all uncompleted tasks.
     * </p>
     */
    @Override
    public void cancel(Exception reason)
    {
        synchronized (lock())
        {
            cancel(lock(), reason);
        }
    }
    void cancel(Object lock, Exception reason)
    {
        for(Object task : taskArray)
        {
            if(task==null)
                continue;
            Async<T> async = cast(task);
            async.cancel(reason);
        }
        // we send one `reason` to multiple tasks. that can be problematic if `reason` is not immutable.
    }


    void onTaskCompletion(int index, Result<T> result)
    {
        synchronized (lock())
        {
            taskArray[index]=null; // GC

            resultArray[resultCount++] = result;  // grows at the end

            checkTrigger(lock());
        }
    }

    void checkTrigger(Object lock)
    {
        if(promise.isCompleted()) // triggered before
            return;

        List<Result<T>> results = pollTaskResults(lock);
        R r;
        Exception ex;
        try
        {
            r = trigger.apply(results);
            ex = null;
        }
        catch (Exception e)
        {
            ex = e;
            r = null;
        }

        if(r!=null)
            promise.succeed(r);
        else if(ex!=null)
            promise.fail(ex);
        else
            return; // not completed

        // after bundle is completed, cancel uncompleted tasks
        cancel(lock, new Exception("cancel remaining tasks after async is completed"));
    }


    /**
     * Create a bundle that succeeds as soon as any one of the tasks succeeds.
     * <p>
     *     The bundle fails if none of the tasks succeeds.
     * </p>
     * <p>
     *     This method is similar to {@link #someOf someOf()} with successThreshold=1.
     * </p>
     */
    public static <T> AsyncBundle<T, T> anyOf(Stream<Async<T>> tasks)
    {
        Object[] taskArray = _Util.toArray(tasks);
        FunctionX<List<Result<T>>, List<T>> trigger1 = triggerOf(1, taskArray.length);
        FunctionX<List<Result<T>>, T> trigger2 = (results)->
        {
            List<T> values = trigger1.apply(results); // throws
            return values==null? null : values.get(0);
        };
        return new AsyncBundle<>(taskArray, trigger2);
    }

    /**
     * Create a bundle that succeeds if all of the tasks succeeds.
     * <p>
     *     The bundle fails as soon as one of the task fails.
     * </p>
     * <p>
     *     This method is similar to {@link #someOf someOf()} with successThreshold=number_of_tasks.
     * </p>
     */
    public static <T> AsyncBundle<T, List<T>> allOf(Stream<Async<T>> tasks)
    {
        Object[] taskArray = _Util.toArray(tasks);
        return new AsyncBundle<>(taskArray, triggerOf(taskArray.length, taskArray.length));
    }

    /**
     * Create a bundle that succeeds as soon as a number of the tasks succeeds.
     * <p>
     *     When the number of tasks that have succeeded reaches {@code successThreshold} ,
     *     the bundle succeeds, with the list of values from these tasks.
     * </p>
     * <p>
     *     The bundle fails if {@code successThreshold}  becomes unreachable (because too many tasks have failed).
     *     See {@link #triggerOf triggerOf(successThreshold, taskCount)} for details.
     * </p>
     */
    public static <T> AsyncBundle<T, List<T>> someOf(Stream<Async<T>> tasks, int successThreshold)
    {
        Object[] taskArray = _Util.toArray(tasks);
        return new AsyncBundle<>(taskArray, triggerOf(successThreshold, taskArray.length));
    }

    /**
     * Return a simple <a href="#trigger-function" >trigger function</a>
     * that's based the number of success results.
     * <p>
     *     Define {@code failureMax=taskCount-successThreshold} .
     *     Define {@code successCount/failureCount}  as number of success/failure results.
     *     The trigger function behaves as the following
     * </p>
     * <ul>
     *     <li>{@code if(successCount >= successThreshold)}  <br>
     *         Return the list of values from success results. The bundle succeeds.</li>
     *     <li>{@code else if(failureCount > failureMax)}  <br>
     *         Throw an {@link OverLimitException} (limitName="failureMax"). The bundle fails.</li>
     *     <li>{@code else} <br>
     *         Return null. The bundle is not yet completed.</li>
     * </ul>
     * <p>
     *     Note that
     *     {@code successThreshold}  is impossible to reach
     *     if {@code successThreshold>taskCount} .
     *     In that case the trigger function fails on any input because
     *     {@code failureCount>failureMax}  is always true.
     *     The bundle therefore fails immediately on construction.
     * </p>
     *
     * @param taskCount
     *        total number of tasks in the bundle
     */
    public static <T> FunctionX<List<Result<T>>, List<T>> triggerOf(int successThreshold, int taskCount)
    {
        _Util.require(successThreshold >= 0, "successThreshold>=0");
        _Util.require(taskCount >= 0, "taskCount>=0");
        // allowed here: successThreshold>taskCount

        return (List<Result<T>> results)->
        {
            int successCount = 0;
            for(Result<T> result : results)
                if(result.isSuccess())
                    successCount++;

            if(successCount >= successThreshold)
            {
                ArrayList<T> list = new ArrayList<>(successCount);
                for(Result<T> result : results)
                    if(result.isSuccess())
                        list.add(result.getValue());
                return list;
            }

            int failureMax = taskCount-successThreshold;
            int failureCount = results.size() - successCount;
            if(failureCount > failureMax)
            {
                String msg = String.format("successThreshold (%d out of %d) cannot be reached; failureCount=%d",
                    successThreshold, taskCount, failureCount);
                OverLimitException ex = new OverLimitException("failureMax", failureMax, msg);
                for(Result<T> result : results)
                    if(result.isFailure())
                        ex.addSuppressed(result.getException());
                throw ex;
            }

            return null; // bundle not completed
        };
    }

}
