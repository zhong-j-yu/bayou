package bayou.async;

import _bayou._async._Asyncs;
import _bayou._tmp._Exec;
import bayou.util.Result;
import bayou.util.function.*;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static _bayou._tmp._Util.cast;

/**
 * Represents an async action that eventually completes with a {@link Result Result&lt;T&gt;}.
 *
 * <p>
 *     The completion status and result can be polled by `pollResult()` method;
 *     or callbacks can be registered through `onCompletion()` method.
 * </p>
 *
 * <h4 id='sequential-actions'>Sequential Actions</h4>
 * <p>
 *     After an action completes, often we want to start another action, and so on,
 *     forming a sequence of actions. This is modeled through
 *     <i>sequencing methods</i>,
 *     which include <code>transform(), map(), peek(), then(), catch_(), and finally_()</code>.
 * </p>
 * <p>
 *     Every <b id=sequencing-method>sequencing method</b> takes in a <i>subsequent action</i>,
 *     and returns the <i>action sequence</i> (also an <code>Async</code>) of the two actions.
 *     After `this` action completes, the subsequent action is started;
 *     when the subsequent action completes, the action sequence completes with the same result.
 * </p>
 * <p>
 *     The following is an example of a sequence of 5 actions:
 * </p>
 * <pre class=inline>
 *     Async&lt;Integer&gt; action1(){...}
 *     Async&lt;String&gt;  action2(Integer x){...}
 *
 *     Async&lt;Integer&gt; sequence =
 *         action1()
 *         .map ( x-&gt;x*2 )
 *         .then( x-&gt;action2(x) )
 *         .catch_( IOException.class, e-&gt;"default string" )
 *         .finally_( resource::close );
 * </pre>
 * <p>
 *     Cancelling a sequence is equivalent to cancelling every action in the sequence (after it's started).
 * </p>
 *
 * <h4 id=covariance>Covariance</h4>
 * <p>
 *     Conceptually, <code>Async&lt;T&gt;</code> is covariant, for example,
 *     <code>Async&lt;String&gt;</code> "is" an <code>Async&lt;CharSequence&gt;</code>,
 *     which "is" an <code>Async&lt;Object&gt;</code>.
 * </p>
 * <p>
 *     Even if we use wildcards profusely, sometimes we'll run into situations where explicit cast is necessary.
 *     We provide {@link #covary()} to do covariance cast, so that, if necessary,
 *     an <code>Async&lt;String&gt;</code> can be used as an <code>Async&lt;CharSequence&gt;</code>.
 * </p>
 *
 * <h4>See Also</h4>
 * <ul>
 *     <li>
 *         {@link Promise} -- a mutable implementation of Async for result producers
 *     </li>
 *     <li>
 *         {@link AsyncIterator} -- for loops of async actions
 *     </li>
 *     <li>
 *         {@link AsyncBundle} --
 *         to bundle parallel async actions
 *     </li>
 *     <li>
 *         {@link Fiber} --
 *         async analogue of Thread
 *     </li>
 * </ul>
 *
 */
public interface Async<T>
{
    // tail recursion...

    // undocumented yet: cancelling a sequence of actions:
    // the cancellation request is sent to the currently executing action,
    // and all subsequent actions when they start to execute
    // (so that the cancellation request wont' be missed)
    // often, the currently executing action results in failure upon cancellation,
    // and all subsequent actions simply propagate the failure, therefore complete instantly,
    // so that the cancellation requests to the subsequent actions have no effect anyway.

    // in failure, exception stacktrace usually doesn't make sense to app developers.
    // we may need to do something about it in future.

    // generally, func/block etc in Async APIs are allowed to throw, which usually triggers failure.

    // note on wildcards:
    // in theory we should use wildcards in a lot of places in our APIs.
    // but it's ugly and hard to read.
    // for now, we deliberately omit wildcards and see how much we can get away with it.
    //
    // at least for functional interfaces it seems fine, e.g. Function<X,Y> works fine
    // with lambda expression and method reference that are subX => superY, javac converts automatically.
    // for a `Function<subX,superY> ff`, it's easy to manually convert too: Function<X,Y> f =ff;
    //
    // if we are wrong, we'll add wildcards in future. that shouldn't break existing call sites.
    // it may break subtypes, but we don't expect users do a lot of subtyping of our types.


    /**
     * A constant {@code Async<Void>} that immediately succeeds with value <code>null</code>.
     * This object is equivalent to the return value of {@link #success(Object) `Async.success( (Void)null )`}.
     * <p>Example Usage:</p>
     * <pre>
     *     Async&lt;Void&gt; action()
     *     {
     *         if(condition)
     *             return Async.VOID;
     *         ...
     *     }
     * </pre>
     */
    public static final Async<Void> VOID = Asyncs.successVoid;



    /**
     * Return the result of the action if it is completed; otherwise return null.
     */
    public abstract Result<T> pollResult();

    /**
     * Whether the action is completed.
     * <p>
     *     This method is equivalent to {@code `pollResult()!= null`}
     * </p>
     */
    public default boolean isCompleted()
    {
        return pollResult() != null;
    }


    /**
     * Block the current thread util this async action completes; return the result or throw the exception.
     * <p>
     *     If the current thread is interrupted while it's being blocked by this method,
     *     this async action will receive a cancellation request with `InterruptedException` as reason.
     * </p>
     * <p>
     *     This method does not have a timeout parameter;
     *     if timeout is needed, consider `action.timeout(duration).sync()`.
     * </p>
     * <p>
     *     <b>Caution:</b> This is a blocking method, which usually should not be called in an async application.
     *     Because this method blocks the current thread, deadlock is possible
     *     if the current thread is needed for completion of this async action.
     *     Be very careful if you use this method on a production system.
     * </p>
     */
    public default T sync() throws Exception
    {
        return _Asyncs.await(this).getOrThrow();
    }


    /**
     * Register a callback that will be invoked after the action is completed.
     * The result of the action will be fed to the callback.
     * <p>
     *     Multiple callbacks can be registered to an async action;
     *     there is no guaranteed in which order they will be invoked.
     * </p>
     * <p>
     *     The `callback` will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    public abstract void onCompletion(Consumer<Result<T>> callback);
    // this is the most basic callback method; users probably don't use it often.
    // people may want an alternative syntax/semantics;
    // the peek() method is probably what they want
    //     Async<T> peek( T->void, Exception->void )


    /**
     * Send a cancellation request to this async action.
     * <p>
     *     If the action has not been completed,
     *     it should be aborted as soon as possible, and the result should be a failure
     *     with {@code `reason`} as the failure exception.
     *     Multiple cancellation requests can be sent; only the first one is effective.
     * </p>
     * <p>
     *     If the action has been completed, the cancellation request is ignored.
     * </p>
     * <p>
     *     The `reason` should usually be a checked exception.
     *     If there is no particularly reason, consider {@code `new Exception("cancelled")`}.
     * </p>
     */
    public abstract void cancel(Exception reason);
    // 1st cancellation request is effective; others are ignored. very likely the other requests
    //     arrive after the action has been completed due to abortion anyway.
    // a result producer may ignore any cancel request.
    // this method should simply send a request, it should not, for example, invoke completion callback sync-ly.
    // `reason` - if there's no particular reason, use new Exception("cancelled"). why not CancellationException -
    //     usually cancel is a "normal" operation and we should use checked exception.
    // kind of ugly to require an arg for cancel(). usually cancel() is not called by app code.
    //   we want a reason so that timeout() can use TimeoutException, better for diagnosis.
    // stacktrace: cancel(new FooException()), stacktrace is of the callers.
    //   that's good; the consumer sees where the cancel request is from.
    //   that's why reason is eagerly created, instead of doing cancel(Supplier<Exception>)
    // if an async reacts to cancel, but we don't want clients to be able to cancel,
    // use a wrapper that ignores cancel (or do some ref counting etc)


    /**
     * Fail this action with {@link java.util.concurrent.TimeoutException TimeoutException}
     * if it's not completed within the specific duration.
     * <p>
     *     The default implementation sends a cancellation request with {@code TimeoutException}
     *     to this action after the specified duration.
     *     It only works if this action responds to the cancellation request.
     * </p>
     * <p>
     *     Example usage:
     * </p>
     * <pre>
     *     action().timeout(Duration.ofSeconds(2)).then(...);
     * </pre>
     * <p>
     *     Multiple timeouts on the same action is ok, the effect is that of the shortest timeout.
     *     Some actions have default timeout, caller can then use {@code timeout()} to set
     *     a shorter timeout.
     * </p>
     * <p>
     *     This method returns `this`, or an equivalent Async (that behaves the same as this one).
     * </p>
     *
     * @return `this` or an equivalent {@code Async<T>}, for method chaining.
     */
    public default Async<T> timeout(Duration duration)
    {
        if(isCompleted())
            return this;

        return new AsyncTimeout<>(this, duration);
    }
    // the timeout is relative to the time timeout() is called, not the time the action is started


    /**
     * Do a <a href="#covariance">covariance</a> cast.
     * <p>
     *     For example
     * </p>
     * <pre>
     *     Async&lt;String&gt; asyncString = ...;
     *
     *     Async&lt;CharSequence&gt; asyncChars = asyncString.covary();
     *
     *     asyncString
     *         .&lt;CharSequence&gt;covary()
     *         .catch_(Exception.class, e-&gt;someCharSequence)
     *
     * </pre>
     * <p>
     *     <code>R</code> must be a supertype of <code>T</code>.
     *     Unfortunately, Java doe not support <code>&lt;R super T&gt;</code>,
     *     so users of this method must take care to uphold that constraint.
     * </p>
     */
    // even if we use wildcards everywhere (yikes), we still need this method, because of another java flaw -
    // lack of lower-bounded type parameter. e.g. Async<? extends HttpResponse>.catch_(clazz, ex->HttpResponse)
    // won't work; we need to insert vary() before catch_()
    public default <R> Async<R> covary()
    {
        return cast(this);
    }


    // sequencing methods
    // after this async is completed, apply a function to the result, get another Async<?>
    // the basic one is transform(). others include map/then/catch/finally
    //
    //       name               func             return      on success/failure
    // ----------------------------------------------------------------------------
    //     transform    Result<T> -> Async<R>    Async<R>      sf
    //           map          T/E -> R           Async<R>      s(f)
    //          then          T/E -> Async<R>    Async<R>      s(f)
    //         catch            E -> T           Async<T>      f
    //       finally           () -> void        Async<T>      sf
    //
    // for T->void, consider using  then(v->{ ...; return Result.Success.VOID; })
    //
    // we may need to add more in future. ask users to suggest.
    // for example:
    //    Async<R> then( ()->Async<R> )  // don't care (T value). e.g. T=Void.
    //    Async<Void> thenVoid( T->void )  // no return in lambda body.
    //    Async<T> finally_( Result<T> -> void );


    /**
     * After `this` action completes, invoke a function to transform the result.
     * <p>
     *     This is a <a href="#sequencing-method">sequencing method</a>.
     *     After `this` action completes with result `r` (success or failure),
     *     invoke `func.apply(r)`
     * </p>
     * <ul>
     *     <li>
     *         if it returns `Async&lt;R&gt; a2`, `a2` is the subsequence action.
     *     </li>
     *     <li>
     *         if it throws `Exception e`, the sequence fails with `e`.
     *     </li>
     * </ul>
     * <p>
     *     The `func` will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    // not usually used by app
    // other sequencing methods are based on this one
    public default <R> Async<R> transform(FunctionX<Result<T>, Async<R>> func)
    {
        return new AsyncThen<>(this, func);

        // we could pool async1 result; if it's complete, return func(result) directly.
        // however, this may be trouble for tail recursion case like
        //     Async<Void> echo(){ return read().then(::write).then(::echo); }
        // it's not rare that read()/write() all completes immediately due to IO buffering.
        // we want to guarantee that tail recursion always work without deep stack.
        //
        // so even if async1 is completed, we'll still create a new AsyncThen,
        // func is not invoked yet, but very soon (in next event loop, on a shallower stack)
        //
        // that means we can impl traditional tail recursion problems in our async model, e.g.
        //     Async<Integer> factorial(n, v){ return n==0? success(v) : nada.then($->factorial(n-1, n*v)); }
    }


    /**
     * After `this` action succeeds, map the value to another value.
     * <p>
     *     This is a <a href="#sequencing-method">sequencing method</a>.
     * </p>
     * <ul>
     *     <li>
     *         If `this` action succeeds with value `t`,
     *         invoke `onSuccess.apply(t)` --
     *         <ul>
     *             <li>if it returns `r`, the sequence succeeds with `r`</li>
     *             <li>if it throws `e`, the sequence fails with `e`</li>
     *         </ul>
     *     </li>
     *     <li>
     *         If `this` action fails with `e`, the sequence fails with `e`
     *     </li>
     * </ul>
     * <p>
     *     Invoking `map(onSuccess)` is equivalent to
     *     {@link #map(FunctionX, FunctionX) `map(onSuccess, onFailure)`}
     *     with `onFailure = e-&gt;{throw e;}`.
     * </p>
     * <p>
     *     (If you need "flat-map", see <code>then()</code> methods.)
     * </p>
     * <p>
     *     The `onSuccess` will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    public default <R> Async<R> map(FunctionX<T, R> onSuccess)
    {
        return transform( (Result<T> result)->
            result.isSuccess()
                ? Result.success(onSuccess.apply(result.getValue()))
                : cast(result)  // cast Failure<T> to Failure<R>, ok due to erasure.
        );
    }

    /**
     * After `this` action completes, map the success value or the failure exception to another value.
     * <p>
     *     This is a <a href="#sequencing-method">sequencing method</a>.
     *     After `this` action succeeds with `v` or fails with `e`,
     *     invoke `onSuccess.apply(v)` or `onFailure.apply(e)` respectively,
     * </p>
     *         <ul>
     *             <li>if it returns `r`, the sequence succeeds with `r`</li>
     *             <li>if it throws `e`, the sequence fails with `e`</li>
     *         </ul>
     *
     * <p>
     *     If you only care about the failure case,
     *     see {@link #catch_(Class, FunctionX) catch_()} method.
     * </p>
     * <p>
     *     (If you need "flat-map", see <code>then()</code> methods.)
     * </p>
     * <p>
     *     The `onSuccess` or 'onFailure'
     *     will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    // if only cares about failure case, use catch_(Exception.class, ex->...)
    public default <R> Async<R> map(FunctionX<T, R> onSuccess, FunctionX<Exception, R> onFailure)
    {
        return transform( (Result<T> result) ->
            result.isSuccess()
                ? Result.success(onSuccess.apply(result.getValue()))
                : Result.success(onFailure.apply(result.getException()))
        );
    }


    /**
     * After `this` action succeeds, perform an action on the value.
     * <p>
     *     This is a sequencing method equivalent to
     * </p>
     * <pre>
     *     map( t-&gt;{ onSuccess.accept(t); return t; } );
     * </pre>
     * <p>
     *     useful for performing an action without changing the result.
     * </p>
     * <p>
     *     Example usage:
     * </p>
     * <pre>
     *     Async
     *         .success("Hello")
     *         .map( String::toUpperCase )
     *         .peek( System.out::println )
     *         ...
     * </pre>
     */
    public default Async<T> peek(ConsumerX<T> onSuccess)
    {
        return map( t->{ onSuccess.accept(t); return t; } );
    }

    /**
     * After `this` action completes, perform an action on the result.
     * <p>
     *     This is a sequencing method equivalent to
     * </p>
     * <pre>
     *     map(
     *         t-&gt;{ onSuccess.accept(t); return t; },
     *         e-&gt;{ onFailure.accept(e); throw  e; }
     *     );
     * </pre>
     * <p>
     *     useful for performing an action without changing the result.
     * </p>
     * <p>
     *     Example usage:
     * </p>
     * <pre>
     *         ...
     *         .peek( System.out::println, Exception::printStackTrace )
     *         ...
     * </pre>
     */
    public default Async<T> peek(ConsumerX<T> onSuccess, ConsumerX<Exception> onFailure)
    {
        return map(
            t->{ onSuccess.accept(t); return t; },
            e->{ onFailure.accept(e); throw  e; }
        );
    }

    /**
     * After `this` action succeeds, invoke a subsequent action.
     * <p>
     *     This is a <a href="#sequencing-method">sequencing method</a>.
     * </p>
     * <ul>
     *     <li>
     *         If `this` action succeeds with value `t`,
     *         invoke `onSuccess.apply(t)` --
     *         <ul>
     *             <li>if it returns `a2`, `a2` is the subsequence action.</li>
     *             <li>if it throws `e`, the sequence fails with `e`</li>
     *         </ul>
     *     </li>
     *     <li>
     *         If `this` action fails with `e`, the sequence fails with `e`
     *     </li>
     * </ul>
     * <p>
     *     Invoking `then(onSuccess)` is equivalent to
     *     {@link #then(FunctionX, FunctionX) `then(onSuccess, onFailure)`}
     *     with `onFailure = e-&gt;{throw e;}`.
     * </p>
     * <p>
     *     (This method is also known as "flat-map".)
     * </p>
     * <p>
     *     The `onSuccess`
     *     will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    public default <R> Async<R> then(FunctionX<T, Async<R>> onSuccess)
    {
        return transform( (Result<T> result) ->
            result.isSuccess()
                ? onSuccess.apply(result.getValue())
                : cast(result)  // cast Failure<T> to Failure<R>, ok due to erasure.
        );
    }

    /**
     * After `this` action completes, invoke a subsequence action.
     * <p>
     *     This is a <a href="#sequencing-method">sequencing method</a>.
     *     After `this` action succeeds with `v` or fails with `e`,
     *     invoke `onSuccess.apply(v)` or `onFailure.apply(e)` respectively,
     * </p>
     * <ul>
     *     <li>if it returns `a2`, `a2` is the subsequence action.</li>
     *     <li>if it throws `e`, the sequence fails with `e`</li>
     * </ul>
     * <p>
     *     (This method is also known as "flat-map".)
     * </p>
     * <p>
     *     The `onSuccess` or 'onFailure'
     *     will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    public default <R> Async<R> then(FunctionX<T, Async<R>> onSuccess, FunctionX<Exception, Async<R>> onFailure)
    {
        return transform( (Result<T> result) ->
            result.isSuccess()
                ? onSuccess.apply(result.getValue())
                : onFailure.apply(result.getException())
        );
    }

    /**
     * If `this` action fails with a specific exception type, invoke an exception handler.
     * <p>
     *     This is a <a href="#sequencing-method">sequencing method</a>.
     * </p>
     * <ul>
     *     <li>
     *         if `this` action fails with `x` of type `X`,
     *         invoke `exceptionHandler.apply(x)`
     *         <ul>
     *             <li>if it returns `t`, the sequence succeeds with `t`</li>
     *             <li>if it throws `e`, the sequence fails with `e`</li>
     *         </ul>
     *     </li>
     *     <li>
     *         otherwise, the sequence completes with the result of `this` action.
     *     </li>
     * </ul>
     * <p>Example Usage:</p>
     * <pre>
     *     action.catch_(IOException.class, e-&gt;"surrogate");
     *
     *     action
     *         .then( ... )
     *         .catch_(IOException.class, e-&gt;{
     *             log(e);
     *             throw new MyException(e);
     *         })
     *         .finally_( ... );
     * </pre>
     * <p>
     *     If you need an exceptionHandler that returns a supertype of <code>T</code>,
     *     consider {@link #covary()} before <code>catch_()</code>.
     * </p>
     * <p>
     *     See also {@link #catch__(Class, bayou.util.function.FunctionX)
     *     catch__(Class, FunctionX&lt;X, Async&lt;T&gt;&gt;)} for async exception handlers.
     *     Note that that method has 2 underscores, while this method has 1 underscore.
     * </p>
     * <p>
     *     The `exceptionHandler`
     *     will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    // if func throws e2, sequence fails with e2, e1 is lost.
    // if that's not desired, func can add e1 to e2 as suppressed.
    // ideally the signature should be
    //    <R super T> Async<R> map(E, E->R)
    public default <X extends Exception> Async<T> catch_(Class<X> exceptionType, FunctionX<X, T> exceptionHandler)
    {
        return transform( (Result<T> result) ->
        {
            Exception ex = result.getException();
            if (!exceptionType.isInstance(ex))  // including the case ex==null
                return result;

            X x = cast(ex);
            return Result.success(exceptionHandler.apply(x));
        });
    }

    // catch_()/catch__() cannot be overloaded, it won't work with implicit lambda expressions.

    /**
     * If `this` action fails with a specific exception type, invoke an exception handler.
     * <p>
     *     This is a <a href="#sequencing-method">sequencing method</a>.
     * </p>
     * <ul>
     *     <li>
     *         if `this` action fails with `x` of type `X`,
     *         run the async `exceptionHandler`
     *         <ul>
     *             <li>if it succeeds with `t`, the sequence succeeds with `t`</li>
     *             <li>if it fails with `e`, the sequence fails with `e`</li>
     *         </ul>
     *     </li>
     *     <li>
     *         otherwise, the sequence completes with the result of `this` action.
     *     </li>
     * </ul>
     * <p>Example Usage:</p>
     * <pre>
     *     action.catch__(IOException.class, e-&gt;Fiber.sleep(duration, "surrogate") );
     *
     *     action
     *         .then( ... )
     *         .catch__(IOException.class, e-&gt;
     *             someAsyncAction()
     *                 .then( v-&gt;Async.failure(e) ) // rethrow
     *         )
     *         .finally_( ... );
     * </pre>
     * <p>
     *     If you need an exceptionHandler that returns <code>Async&lt;X&gt;</code>
     *     where <code>X</code> is a supertype of <code>T</code>,
     *     consider {@link #covary() &lt;X&gt;covary()} before <code>catch__()</code>.
     * </p>
     * <p>
     *     See also {@link #catch_(Class, bayou.util.function.FunctionX)
     *     catch_(Class, FunctionX&lt;X, T&gt;)}.
     *     Note that that method has 1 underscore, while this method has 2 underscores.
     * </p>
     * <p>
     *     The `exceptionHandler`
     *     will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    public default <X extends Exception> Async<T> catch__(Class<X> exceptionType,
                                                          FunctionX<X, Async<T>> exceptionHandler)
    {
        return transform( (Result<T> result) ->
        {
            Exception ex = result.getException();
            if (!exceptionType.isInstance(ex))  // including the case ex==null
                return result;

            X x = cast(ex);
            return exceptionHandler.apply(x);
        });
    }

    /**
     * After `this` action completes, regardless of success or failure, execute a subsequent action.
     * <p>
     *     This is a <a href="#sequencing-method">sequencing method</a>.
     *     After `this` action completes with result `r` (success or failure),
     *     invoke `action.run()`
     * </p>
     * <ul>
     *     <li>
     *         if it returns normally, the sequence completes with result `r`
     *     </li>
     *     <li>
     *         if it throws `e`, the sequence fails with `e`.
     *     </li>
     * </ul>
     * <p>
     *     Example Usage:
     * </p>
     * <pre>
     *     action
     *         .then(...)
     *         .finally_( resource::close );
     * </pre>
     * <p>
     *     The `action`
     *     will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    public default Async<T> finally_(RunnableX action)
    {
        // if action.run() throws e, result.exception is lost, not added as e's suppressed.
        return transform( (Result<T> result) ->
        {
            action.run();  // throws
            return result;
        });
    }

    // finally_() is overloaded; seems working fine. action has 0 arg, so lambda expr is never "implicit".
    // do not use different names, like "finally__" for async version (to be symmetric with catch__)
    // because it's easy to mistakenly call finally_(()->void) while the action is ()->Async<?>
    // do overload, and let compiler pick. finally(()->Async) is more specific than finally(()->void)

    /**
     * After `this` action completes, regardless of success or failure, execute a subsequent action.
     * <p>
     *     This is a <a href="#sequencing-method">sequencing method</a>.
     *     After `this` action completes with result `r` (success or failure),
     *     start the async `action`,
     * </p>
     * <ul>
     *     <li>
     *         if it completes successfully, the sequence completes with result `r`
     *     </li>
     *     <li>
     *         if it fails with `e`, the sequence fails with `e`.
     *     </li>
     * </ul>
     * <p>
     *     Example Usage:
     * </p>
     * <pre>
     *     action
     *         .then(...)
     *         .finally_( wsChannel::close );
     * </pre>
     * <p>
     *     The `action`
     *     will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    public default Async<T> finally_(Callable<Async<?>> action)
    {
        return transform( (Result<T> result) ->
        {
            Async<?> actionAsync = action.call(); // throws

            Result<?> actionResult = actionAsync.pollResult();
            if(actionResult!=null) // common
            {
                if(actionResult.isFailure())
                    return cast(actionResult);
                return result;
            }

            return actionAsync.<T>transform( (actionResult2) ->
            {
                if(actionResult2.isFailure())
                    return cast(actionResult2);
                return result;
            });
        });
    }



    // static methods =========================================================================================


    /**
     * Create an `Async&lt;T&gt;` that immediately succeeds with `value`.
     * <p>
     *     This method is equivalent to {@link Result#success(Object) Result.success(value)}.
     * </p>
     */
    public static <T> Async<T> success(T value)
    {
        return Result.success(value);
    }

    /**
     * Create an `Async&lt;T&gt;` that immediately fails with `e`.
     * <p>
     *     This method is equivalent to {@link Result#failure(Exception) Result.failure(e)}.
     * </p>
     */
    public static <T> Async<T> failure(Exception e)
    {
        return Result.failure(e);
    }


    /**
     * Execute the action asynchronously, return an Async that completes when the action completes.
     * <p>
     *     This method is usually to convert a legacy blocking action to an async action. For example
     * </p>
     * <pre>
     *     Async.execute( executor, ()-&gt;Files.readAllBytes(path) );
     * </pre>
     * <p>
     *     If the returned Async is cancelled with `reason=e` before the action is completed,
     *     the Async fails with `e`, and the thread executing the action is interrupted.
     * </p>
     * <p>
     *     The action will inherit the current fiber, i.e. when it's executed,
     *     {@link Fiber#current()} will return the same value as the caller of this method would see.
     * </p>
     * <p>
     *     See {@link Callable_Void} in case the <code>`action`</code> argument is a void-returning lambda expression or
     *     method reference.
     * </p>
     *
     */
    public static <T> Async<T> execute(ExecutorService executor, final Callable<T> action)
    {
        return Asyncs.execute(executor, action);
    }

    /**
     * Execute the action asynchronously in a default executor.
     * <p>
     *     This method is usually used to convert a legacy blocking action to an async action. For example
     * </p>
     * <pre>
     *     Async.execute( ()-&gt;Files.readAllBytes(path) );
     * </pre>
     * <p>
     *     This method is equivalent to {@link #execute(ExecutorService, Callable)}
     *     with a default executor.
     * </p>
     * <p>
     *     <b>Caution:</b>
     *     the action is submitted to a system default executor with unlimited threads.
     *     That is probably inappropriate in a production system.
     * </p>
     *
     */
    public static <T> Async<T> execute(Callable<T> action)
    {
        return Asyncs.execute(_Exec.executorB(), action);
    }

    /**
     * Invoke `func` with async args.
     * <p>
     *     If both `async1` and `async2` are successful, their values will be fed to `func`,
     *     and this `invoke` action completes with the result of `func`.
     * </p>
     * <p>
     *     If either `async1` or `async2` fails, this `invoke` action fails too with the same exception;
     *     the uncompleted `async1` or `async2` will be canceled.
     * </p>
     * <p>
     *     Cancelling this `invoke` action is equivalent to canceling both `async1` and `async2`.
     * </p>
     *
     * @see #invoke_(bayou.util.function.BiFunctionX, Async, Async)
     */
    public static <T1,T2,R> Async<R> invoke(BiFunctionX<T1,T2,R> func, Async<T1> async1, Async<T2> async2)
    {
        BiFunctionX<T1,T2,Async<R>> funcAsync = (t1,t2)->Async.success( func.apply(t1,t2) );
        return invoke_(funcAsync, async1, async2);
    }
    // note that async1 and async2 are concurrent.
    // if it's necessary that async2 starts after async1 completes, user has to do then().then()...
    //
    // other arity:
    // arity=1: not needed? invoke(func,async1)==async1.map(func). tho we may add it just for uniformity
    // arity=3: invoke(TriFunc, async1, async2, async3)
    // higher arity: probably not needed. or we'll need a better way to model them. tuple?


    /**
     * Similar to {@link #invoke(bayou.util.function.BiFunctionX, Async, Async)},
     * except that `func` returns `Async&lt;R&gt;` instead.
     */
    public static <T1,T2,R> Async<R> invoke_(BiFunctionX<T1,T2,Async<R>> func, Async<T1> async1, Async<T2> async2)
    {
        return Asyncs.invoke_(func, async1, async2);
    }
}
