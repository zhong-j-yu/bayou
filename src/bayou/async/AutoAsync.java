package bayou.async;

import _bayou._async._Asyncs;
import bayou.util.Result;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * To make a type also as an `Async` type.
 * <p>
 *     Often we need to convert a `Foo` object to an `Async&lt;Foo&gt;` object.
 *     This can be done by `Async.success(foo)`; but it can become tedious if it happens a lot.
 * </p>
 * <p>
 *     By simply `extends/implements AutoAsync&lt;Foo&gt;`, we can make an `interface/class Bar`
 *     a subtype of `Async&lt;Foo&gt;`,
 *     then a `Bar` object can be used anywhere an `Async&lt;Foo&gt;` is needed.
 *     Note that `AutoAsync` is an interface with no abstract method.
 * </p>
 * <p>
 *     Requirement: if `interface/class Bar extends/implements AutoAsync&lt;Foo&gt;`,
 *     it must be true that `Bar` is `Foo` or a subtype of `Foo`.
 * </p>
 * <p>
 *     <b>Caution:</b> Bar inherits all instance methods from Async&lt;Foo&gt;,
 *     which can create confusions along with Bar's own methods.
 * </p>
 * <p>
 *     One usage example is `HttpResponseImpl`:
 * </p>
 * <pre class=inline>
 *     public class HttpResponseImpl implements HttpResponse, AutoAsync&lt;HttpResponse&gt;
 * </pre>
 * <p>
 *     this means that an HttpResponseImpl object can be used any where an Async&lt;HttpResponse&gt; is needed.
 * </p>
 */
public interface AutoAsync<T> extends Async<T>
{
    // It may seem that it's better to have AutoResult instead,
    // because each Foo is conceptually a Result.Success<Foo>. (and pollResult() will be cheaper)
    // however methods of Result probably create more confusions than methods of Async
    // if they blend in Foo 's methods.
    // and we rarely need Foo as Result<Foo>; we usually need Foo as Async<Foo>



    /**
     * Implements {@link Async#pollResult()}.
     * Equivalent to `Result.success( (T)this) )`
     */
    @Override
    public default Result<T> pollResult()
    {
        @SuppressWarnings("unchecked")
        T thisT  = (T)this;
        return Result.success(thisT);
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
     * by default, do nothing.
     */
    @Override
    public default void cancel(Exception reason)
    {
        // no effect
    }


    /**
     * Implements {@link Async#timeout(Duration)};
     * by default, do nothing.
     * Returns `this`.
     *
     * @return <code>`this`</code>
     */
    @Override
    public default Async<T> timeout(Duration duration)
    {
        // no effect
        return this;
    }

}
