package bayou.util;

import _bayou._async._Asyncs;
import bayou.async.Async;
import bayou.util.function.Callable_Void;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * The result of an action,
 * which is either a <em>success</em> or a <em>failure</em>.
 *
 * <p>
 *     A success Result contains a value of type <code>T</code>.
 *     A failure Result contains an exception of type <code>Exception</code>
 * </p>
 *
 * <p>
 *     See static methods
 *     {@link Result#success Result.success(value)} and {@link Result#failure Result.failure(exception)}
 *     to create Result objects.
 * </p>
 *
 * <p>
 *     Every <code>Result</code> is also an {@link Async} that's immediately completed.
 *     A <code>Result</code> object can be used wherever an <code>Async</code> is expected.
 * </p>
 *
 * <p>
 *     Note: A success value could be <code>null</code>;
 *     however, a failure exception must be non-null.
 * </p>
 *
 * <p>
 *     Note: In failure, the type of the exception is <code>Exception</code>,
 *     not the more general <code>Throwable</code>.
 *     We consider that <code>Error</code> indicates something catastrophic,
 *     which is not a usual result of any action.
 *     Therefore we do not handle <code>Error</code> in <code>Result</code>.
 * </p>
 *
 *
 */

public interface Result<T> extends Async<T>
{
    /**
     * Whether this result is a success.
     */
    public abstract boolean isSuccess();

    /**
     * Whether this result is a failure.
     */
    public abstract boolean isFailure();

    /**
     * Return the value if this result is a success; otherwise return null.
     * <p>
     *     Note that <code>null</code> could be legitimate success value.
     * </p>
     */
    public abstract T getValue();

    /**
     * Return the exception if this result is a failure; otherwise return null.
     * <p>
     *     Note that if this method returns <code>null</code>,
     *     this result must be a success.
     * </p>
     */
    public abstract Exception getException();

    /**
     * Return the value if this result is a success; otherwise throw the exception.
     */
    public default T getOrThrow() throws Exception
    {
        if(isSuccess())
            return getValue();
        else
            throw getException();
    }

    // Async methods -------------------------------------------------------------------------------------

    /**
     * Implements {@link Async#pollResult()}; by default, return <code>`this`</code>.
     */
    @Override
    public default Result<T> pollResult()
    {
        return this;
    }

    /** return `true` */
    @Override
    public default boolean isCompleted()
    {
        return true;
    }

    /**
     * Implements {@link Async#onCompletion(Consumer)}.
     * Note that `this` is already completed.
     */
    @Override
    public default void onCompletion(Consumer<Result<T>> callback)
    {
        _Asyncs.bindToCurrExec(callback).accept(pollResult());
        // async-ly. usually invoked later on the current thread.
    }

    /**
     * Implements {@link Async#cancel(Exception)};
     * by default, do nothing, because a <code>Result</code> is a completed <code>Async</code>.
     */
    @Override
    public default void cancel(Exception reason)
    {
        // no effect
    }

    /**
     * Implements {@link Async#timeout(Duration)};
     * by default, do nothing, because a <code>Result</code> is a completed <code>Async</code>.
     * Returns `this`.
     *
     * @return <code>`this`</code>
     */
    @Override
    public default Result<T> timeout(Duration duration)
    {
        // no effect
        return this;
    }


// ------------------------------------------------------------------------------------------------------------

    /**
     * Create a success Result with the <code>value</code>.
     * <p>Example Usage:</p>
     * <pre>
     *     Result&lt;Integer&gt; result = Result.success(42);
     * </pre>
     *
     * @param value
     *        the success value. Can be null.
     */
    public static <T> Result<T> success(T value)
    {
        return new Result_Success<>(value);
    }

    /**
     * Create a failure Result with the <code>exception</code>.
     * <p>Example Usage:</p>
     * <pre>
     *     Result&lt;Integer&gt; result = Result.failure(new IOException("cannot read file"));
     * </pre>
     *
     * @param exception
     *        the failure exception. Must be non-null.
     *
     */
    public static <T> Result<T> failure(Exception exception)
    {
        return new Result_Failure<>(exception);
    }

    /**
     * Invoke <code>action.call()</code>, convert the outcome to a <code>Result</code>.
     *
     * <p>
     *     If <code>action.call()</code> returns a value, this method returns a success Result with the same value.
     * </p>
     * <p>
     *     If <code>action.call()</code> throws an Exception,
     *     this method <em>returns</em> a failure Result with the same exception.
     * </p>
     *
     * <p>Example Usage:</p>
     * <pre>
     *     Result&lt;Integer&gt; result = Result.call( ()-&gt;Integer.parseInt("0.1") );
     *     // result is a failure with a NumberFormatException
     * </pre>
     *
     * <p>
     *     See {@link Callable_Void} in case the <code>`action`</code> argument is a void-returning lambda expression or
     *     method reference.
     * </p>
     *
     * <p>
     *     Note: Result does not handle <code>Error</code>.
     *     If <code>action.call()</code> throws an Error, this method <b>throws</b> the same error.
     * </p>
     *
     */
    public static <T> Result<T> call(Callable<T> action)
    {
        T t;
        try
        {
            t = action.call();
        }
        catch (Exception e)
        {
            return failure(e);
        }
        return success(t);
    }


}
