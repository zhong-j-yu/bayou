package bayou.async;

import _bayou._async._AsyncDoWhile;
import _bayou._tmp._Util;
import bayou.util.End;
import bayou.util.Result;
import bayou.util.function.BiFunctionX;
import bayou.util.function.ConsumerX;
import bayou.util.function.FunctionX;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import static _bayou._async._Asyncs._next;
import static _bayou._tmp._Util.cast;
import static bayou.async.Asyncs.FindNext;

/**
 * An iterator that yields elements asynchronously.
 * <p>
 *     The single abstract method of {@code AsyncIterator<T>} is {@code `Async<T> next()`},
 *     which is an async action that yields the next element.
 *     If the async action fails with
 *     {@link End}, the iterator reaches its end and there are no more elements.
 * </p>
 * <p>
 *     Some APIs have methods that are semantically compatible with AsyncIterator.next(),
 *     for example, ByteSource.read(), WebSocketChannel.readMessage().
 *     It's easy to create an AsyncIterator for them:
 * </p>
 * <pre>
 *     AsyncIterator&lt;ByteBuffer&gt; bbIter = byteSource::read;
 *     bbIter.forEach( bb-&gt;... );
 *
 *     AsyncIterator.by( webSocketChannel::readMessage ).forEach( msg-&gt;... );
 * </pre>
 * <p>
 *     AsyncIterator is usually used to model loops containing async actions. For example
 * </p>
 * <pre>
 *     ByteSource source = ...;
 *     AsyncIterator.by(source::read)
 *         .map( ByteBuffer::remaining )
 *         .forEach( System.out::println )
 *         .finally_( source::close );
 * </pre>
 * <p>
 *     There are intermediary operations that transform an AsyncIterator to another AsyncIterator,
 *     for example, {@link #map map}, {@link #flatMap flatMap}, {@link #filter filter}.
 * </p>
 * <p>
 *     There are terminal operations that visit all elements and compute a result,
 *     for example, {@link #reduce reduce}, {@link #forEach forEach}, {@link #toList toList}.
 * </p>
 * <p>
 *     After an intermediary/terminal operation on an iterator, the iterator should not be used again,
 *     or there could be multiple parties trying to iterate its elements.
 * </p>
 *
 * <h4 id=breaking>Breaking</h4>
 * <p>
 *     Sometimes we need to break a loop early, ignoring the rest of the elements.
 *     This can be done by throwing `End` from the function that processes elements.
 *     For example
 * </p>
 * <pre>
 *     Stream&lt;Integer&gt; stream = IntStream.range(0, 100).boxed();
 *     AsyncIterator&lt;Integer&gt; iter = AsyncIterator.wrap(stream); // {0, 1, ... 99 }
 *
 *     iter = iter.filter( (integer) -&gt; {
 *         if(integer &gt; 50)
 *             throw End.instance();  // filter out all remaining elements
 *         return integer%2==0;
 *     });

 *     iter.forEach( (integer) -&gt; {
 *         if(integer &gt; 30)
 *             throw End.instance(); // break
 *         print(integer);
 *     });
 * </pre>
 * <p>
 *     Breaking does not cause error condition.
 *     In the example, `filter()` will create a new iterator of {0, 2, ... 50},
 *     `forEach()` will complete successfully  after printing {0, 2, ... 30},
 * </p>
 * <p>
 *     If the function returns an Async, it can also break the loop by
 *     returning an Async that (eventually) fails with an End.
 * </p>
 *
 * <h4>See Also</h4>
 * <ul>
 *     <li>
 *         {@link End} -- the control exception for signaling end-of-iteration
 *     </li>
 * </ul>
 *
 */
public interface AsyncIterator<T>
{
    /**
     * Yield the next element asynchronously.
     * <p>
     *     This method returns an {@code Async<T>},
     * </p>
     * <ul>
     *     <li>if it succeeds with a value `t`, the next element is `t`</li>
     *     <li>if it fails with an `End`, this iterator reaches its end and there are no more elements</li>
     *     <li>
     *         if it fails with `e` that's not an `End`, it's an error condition,
     *         which may or may not be recoverable
     *     </li>
     * </ul>
     * <p>
     *     <b>Caution: </b> concurrent next() actions are not supported;
     *     an application must not call next() until the previous next() action is completed.
     * </p>
     */
    public abstract Async<T> next();




    /**
     * Create a new iterator by mapping each elements of this iterator to a new element.
     * <p>
     *     If the elements of this iterator are { e<sub>0</sub> , e<sub>1</sub> , ... e<sub>n-1</sub> },
     *     the elements of the new iterators are
     *     { func(e<sub>0</sub>) , func(e<sub>1</sub>) , ... func(e<sub>n-1</sub>) }.
     * </p>
     *
     * <p>
     *     <a href="#breaking">Breaking </a> can be done if `func` throws `End`.
     * </p>
     * <p>
     *     The `func`
     *     will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    public default <R> AsyncIterator<R> map(FunctionX<T, R> func)
    {
        return () -> next().map(func);
    }

    /**
     * Similar to {@link #map map()}, except that `func` returns {@code Async<R>} instead of R.
     * <p>
     *     <a href="#breaking">Breaking </a> can be done if `func` throws `End`
     *     or returns an Async that eventually fails with `End`.
     * </p>
     * <p>
     *     The `func`
     *     will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    // note: this is not "flat map"
    public default <R> AsyncIterator<R> map_(FunctionX<T, Async<R>> func)
    {
        return () -> next().then(func);
    }

    /**
     * Create a new iterator by mapping each elements of this iterator to multiple new elements.
     *
     * <p>
     *     <a href="#breaking">Breaking </a> can be done if `func` throws `End`.
     * </p>
     * <p>
     *     The `func`
     *     will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    public default <R> AsyncIterator<R> flatMap(FunctionX<T, AsyncIterator<R>> func)
    {
        FunctionX<End, AsyncIterator<R>> funcEndToEnd = end -> ()->Result.failure(end);
        return flatMap(func, funcEndToEnd);
    }

    /**
     * Similar to flatMap(func), except that the end-of-iteration event is also used
     * to generate new elements.
     * <p>
     *     When this iterator next() fails with End, or when func throws End,
     *     the End exception is fed to endFunc to generate more new elements.
     * </p>
     * <p>
     *     <a href="#breaking">Breaking </a> can be done if `func` throws `End`.
     * </p>
     * <p>
     *     The `func` and 'endFunc'
     *     will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    public default <R> AsyncIterator<R> flatMap(FunctionX<T, AsyncIterator<R>> func,
                                                FunctionX<End, AsyncIterator<R>> endFunc)
    {
        return new FlatMapIterator<>(this, func, endFunc);
    }



    /**
     * Create a new iterator that retrains only elements approved by predicate.
     *
     * <p>
     *     <a href="#breaking">Breaking </a> can be done if `predicate` throws `End`.
     * </p>
     * <p>
     *     The `predicate`
     *     will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    public default AsyncIterator<T> filter(FunctionX<T,Boolean> predicate)
    {
        FunctionX<T, Async<Boolean>> predicateAsync = t -> Result.success(predicate.apply(t));
        return filter_(predicateAsync);
    }

    /**
     * Similar to {@link #filter filter()}, except that predicate returns {@code Async<Boolean>} instead of Boolean.
     *
     * <p>
     *     <a href="#breaking">Breaking </a> can be done if `predicate` throws `End`
     *     or returns an Async that eventually fails with `End`.
     * </p>
     * <p>
     *     The `predicate`
     *     will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    public default AsyncIterator<T> filter_(FunctionX<T, Async<Boolean>> predicate)
    {
        return () -> {
            FindNext<T> findNext = new FindNext<>(this, predicate); // need a new one every time
            return findNext.run();
        };
    }

    /**
     * Perform `action` on each element as they are being iterated.
     * <p>
     *     This is an intermediary operation equivalent to
     * </p>
     * <pre>
     *     map( t-&gt;{ action.accept(t); return t; } );
     * </pre>
     * <p>
     *     usual for performing actions without altering the elements.
     * </p>
     * <p>
     *     Example usage:
     * </p>
     * <pre>
     *     AsyncIterator
     *         .ofInts(0, 10)
     *         .peek( System.out::println )
     *         .filter( x-&gt;x%3==0 )
     *         .peek( System.err::println )
     *         ...
     * </pre>
     * <p>
     *     CAUTION: this is not a terminal operation; this method alone will not trigger iteration of elements.
     * </p>
     *
     */
    public default AsyncIterator<T> peek(ConsumerX<T> action)
    {
        return map( t->{ action.accept(t); return t; } );
    }


    // terminal operations
    // can be implemented by recursion, e.g.
    //     forEach(action)
    //         next().then(action).then(forEach(action))


    /**
     * Perform action on each element.
     * <p>
     *     The overall action succeeds after all elements are visited and
     *     action is applied on each of them.
     * </p>
     * <p>
     *     <a href="#breaking">Breaking </a> can be done if `action` throws `End`.
     * </p>
     * <p>
     *     The `action`
     *     will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     * <p>
     *     <b style="color:red;">WARNING:</b>
     *     if the action is async, use {@link #forEach_(FunctionX) forEach_(asyncAction)} instead.
     * </p>
     * <p>
     *     Unfortunately, it's easy to accidentally pass an async action to this method, for example
     * </p>
     * <pre>
     *     iter.forEach( elem-&gt;Fiber.sleep(duration) ); // the action is async!
     * </pre>
     * <p>
     *     This is because the type of parameter `action` is {@code T->void},
     *     which is compatible with any lambda expression {@code T->expr},
     *     regardless of the type of `expr`. It also matches with a method reference
     *     with no regard to the return type.
     * </p>
     * <p>
     *     At this point, we don't have a good solution to prevent the problem at compile time.
     *     We do some runtime detection and issue a warning if the action is suspected to be async.
     * </p>
     */
    public default Async<Void> forEach(ConsumerX<T> action)
    {
        return new ForEachNoAsync<>(this, action).run();
    }

    // maybe indexed for-each? forEach( (Integer, T)->void )

    /**
     * Similar to {@link #forEach(ConsumerX) forEach()}, except that the action is async.
     * The `asyncAction` returns {@code Async<?>} instead of void.
     *
     * <p>
     *     <a href="#breaking">Breaking </a> can be done if `action` throws `End`
     *     or returns an Async that eventually fails with `End`.
     * </p>
     * <p>
     *     The `asyncAction`
     *     will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    public default Async<Void> forEach_(FunctionX<T, Async<?>> asyncAction)
    {
        FunctionX<T, Async<Void>> asyncAction2 = cast(asyncAction);
        // the cast is OK if we don't care about the success value of the action

        _AsyncDoWhile<Void,Void> doWhile = new _AsyncDoWhile<Void,Void>()
        {
            @Override
            protected Async<Void> action()
            {
                return _next(AsyncIterator.this)
                    .then(asyncAction2);
            }

            @Override
            protected Result<Void> condition(Result<Void> result)
            {
                Exception e = result.getException();
                if(e==null)
                    return null; // continue loop
                if(e instanceof End)
                    return Asyncs.successVoid;  // loop ends with success
                else
                    return result; // loop ends with failure
            }
        };

        return doWhile.run();
    }


    /**
     * Compute a value from the elements.
     * <p>
     *     If the elements are { e<sub>0</sub> , e<sub>1</sub> , ... e<sub>n-1</sub> },
     *     define r<sub>i+1</sub> = func(r<sub>i</sub> , e<sub>i</sub>),
     *     reduce() will yield value r<sub>n</sub>.
     * </p>
     *
     * <p>
     *     <a href="#breaking">Breaking </a> can be done if `func` throws `End`.
     * </p>
     * <p>
     *     The `func`
     *     will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    public default <R> Async<R> reduce(R r0, BiFunctionX<R, T, R> func)
    {
        BiFunctionX<R,T, Async<R>> reducerAsync = (r,t)->Result.success(func.apply(r, t));
        return reduce_(r0, reducerAsync);
    }

    /**
     * Similar to {@link #reduce reduce()}, except func returns {@code Async<R>} instead of R.
     * <p>
     *     <a href="#breaking">Breaking </a> can be done if `func` throws `End`
     *     or returns an Async that eventually fails with `End`.
     * </p>
     * <p>
     *     The `func`
     *     will be invoked in the {@link Fiber#currentExecutor() current executor}.
     * </p>
     */
    public default <R> Async<R> reduce_(R r0, BiFunctionX<R, T, Async<R>> func)
    {
        _AsyncDoWhile<R,R> doWhile = new _AsyncDoWhile<R,R>()
        {
            R var = r0;

            @Override
            protected Async<R> action()
            {
                return _next(AsyncIterator.this)
                    .then(t -> func.apply(var, t));
            }

            @Override
            protected Result<R> condition(Result<R> result)
            {
                Exception e = result.getException();
                if(e==null)
                {
                    var = result.getValue();
                    return null; // continue loop
                }

                if(e instanceof End)
                    return Result.success(var);  // loop ends with success
                else
                    return result; // loop ends with failure
            }
        };

        return doWhile.run();
    }

    /**
     * Gather all elements into a List.
     */
    public default Async<List<T>> toList()
    {
        List<T> list0 = new ArrayList<>();
        return reduce(list0, (list, elem) ->
        {
            list.add(elem);
            return list;
        });
    }










    /**
     * Create an empty {@code AsyncIterator<T>}, with no elements.
     *
     */
    public static <T> AsyncIterator<T> empty()
    {
        return _Util.cast(Asyncs.EMPTY_STREAM);
    }

    /**
     * Create an {@code AsyncIterator<T>} of the elements.
     */
    @SafeVarargs
    public static <T> AsyncIterator<T> of(T... elements)
    {
        Iterator<T> iterator = Arrays.asList(elements).iterator();
        return wrap(iterator);
    }

    /**
     * Create an AsyncIterator of integers from `start`(inclusive) to `end`(exclusive).
     */
    public static AsyncIterator<Integer> ofInts(int start, int end)
    {
        if(start>=end)
            return empty();

        return new AsyncIterator<Integer>()
        {
            int i = start;
            @Override
            public Async<Integer> next()
            {
                if(i==end)
                    return End.async();
                return Async.success(i++);
            }
        };
    }

    /**
     * Wrap an {@code Iterator<T>} as an {@code AsyncIterator<T>}.
     */
    public static <T> AsyncIterator<T> wrap(Iterator<T> iterator)
    {
        return () ->
            iterator.hasNext()
                ? Async.success(iterator.next())
                : End.async()
            ;
    }

    /**
     * Wrap a {@code Stream<T>} as an {@code AsyncIterator<T>}.
     */
    public static <T> AsyncIterator<T> wrap(Stream<T> stream)
    {
        return new Asyncs.Stream2AsyncIterator<>(stream);
    }

    /**
     * Syntax sugar to create an AsyncIterator
     * from a lambda expression or a method reference.
     * <p>
     *     This method simply returns the argument `asyncIterator`, which seems a little odd.
     *     Explanation:
     * </p>
     * <p>
     *     Since AsyncIterator is a functional interface, an instance can be created by
     *     a lambda expression or a method reference, in 3 contexts:
     * </p>
     * <pre>
     *     // Assignment Context
     *     AsyncIterator&lt;ByteBuffer&gt; asyncIter = source::read;
     *     asyncIter.forEach(...);
     *
     *     // Casting Context
     *     ((AsyncIterator&lt;ByteBuffer&gt;)source::read)
     *         .forEach(...);
     *
     *     // Invocation Context
     *     AsyncIterator.by(source::read)
     *         .forEach(...);
     * </pre>
     * <p>
     *     The 3rd option looks better than the other two, and that's the purpose of this method.
     * </p>
     * <p>
     *     If by() is followed by forEach(), it's simpler to use static AsyncIterator.forEach():
     * </p>
     * <pre>
     *     AsyncIterator.forEach(source::read, action)
     * </pre>
     *
     * @return the argument `asyncIterator`
     */
    public static <T> AsyncIterator<T> by(AsyncIterator<T> asyncIterator)
    {
        return asyncIterator;
    }


    /**
     * Perform action on each element.
     * <p>
     *     This method is a syntax sugar for `elements.forEach(action)`.
     *     Example Usage:
     * </p>
     * <pre>
     *     ByteSource source = ...
     *     AsyncIterator.forEach( source::read, System.out::println )
     *         .finally_( source::close );
     * </pre>
     * <p>
     *     <b style="color:red;">WARNING:</b>
     *     if the action is async, use
     *     {@link #forEach_(AsyncIterator, FunctionX) forEach_(elements, asyncAction)} instead.
     *     <b>Read the explanation</b> in {@link #forEach(ConsumerX) forEach(action)}.
     * </p>
     */
    static <T> Async<Void> forEach(AsyncIterator<T> elements, ConsumerX<T> action)
    {
        return elements.forEach(action);
    }

    /**
     * Perform asyncAction on each element.
     * <p>
     *     This method is a syntax sugar for `elements.forEach_(asyncAction)`.
     *     Example Usage:
     * </p>
     * <pre>
     *     // echo websocket messages
     *     WebSocketChannel chann = ...;
     *     AsyncIterator.forEach_( chann::readMessage, chann::writeMessage )
     *         .finally_( chann::close );
     * </pre>
     */
    static <T> Async<Void> forEach_(AsyncIterator<T> elements, FunctionX<T, Async<?>> asyncAction)
    {
        return elements.forEach_(asyncAction);
    }

}
