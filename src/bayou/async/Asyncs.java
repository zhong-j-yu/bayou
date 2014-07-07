package bayou.async;

import _bayou._async._AsyncDoWhile;
import _bayou._async._Asyncs;
import _bayou._tmp._Exec;
import _bayou._log._Logger;
import bayou.util.End;
import bayou.util.OverLimitException;
import bayou.util.Result;
import bayou.util.function.BiFunctionX;
import bayou.util.function.FunctionX;

import java.time.Duration;
import java.util.Spliterator;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static _bayou._tmp._Util.cast;

// package private implementation details
class Asyncs
{
    static <T> Async<T> sleep(Duration duration, T value)
    {
        final Promise<T> promise = new Promise<>();
        final AtomicBoolean lock = new AtomicBoolean();  // only lock owner can complete the promise

        // CAUTION: delayed executor is single threaded.
        final Future<?> future = _Exec.execNbDelayed(duration, ()->
        {
            if(lock.compareAndSet(false, true))
                promise.succeed(value);
        });

        promise.onCancel( (reason)->
        {
            if (lock.compareAndSet(false, true))
                promise.fail(reason);

            future.cancel(/*interrupt*/true); // interrupt has no effect here
        });
        return promise;
    }

    // for converting legacy blocking action into async action.
    // action is probably blocking, executor must execute it async-ly.
    // another usage: current AsyncExec is the UI thread; in a then(computation), computation is heavy. we want
    //    xxx.then( x->execute(commonPool, computation) ).
    static <T> Async<T> execute(ExecutorService executor, Callable<T> action)
    {
        Fiber<?> fiber = Fiber.current();
        Promise<T> promise = new Promise<>();
        AtomicBoolean lock = new AtomicBoolean();  // only lock owner can complete the async
        final Future<?> future = executor.submit(() ->
        {
            Fiber.setLocalFiber(fiber);
            try
            {
                Result<T> result;
                try
                {
                    result = Result.call(action); // does not catch Error
                }
                catch (Error e) // we should complete the promise even for Error; or app hangs.
                {
                    result = Result.failure(new RuntimeException(e));
                }
                if(lock.compareAndSet(false, true))
                    promise.complete(result);
            }
            finally
            {
                Fiber.setLocalFiber(null);
            }
        });
        promise.onCancel(reason -> {
            if (lock.compareAndSet(false, true))
                promise.fail(reason);

            future.cancel(/*interrupt*/true);
            // interrupt blockingSupplier. it may or may not abort on interruption.
        });
        return promise;
    }


    static <T1,T2,R> Async<R> invoke_(BiFunctionX<T1,T2,Async<R>> func, Async<T1> async1, Async<T2> async2)
    {
        Async<Object> a1 = cast(async1);
        Async<Object> a2 = cast(async2);
        return AsyncBundle
            .allOf(Stream.of(a1, a2))
            .catch_(OverLimitException.class, ex->
            {
                Throwable[] causes = ex.getSuppressed();
                if(causes.length>0)
                    throw (Exception)causes[0];
                throw ex;
            })
            .then(list ->
            {
                T1 r1 = cast(list.get(0));
                T2 r2 = cast(list.get(1));
                return func.apply(r1, r2);
            });
        // we should really have hetero AsyncTuple<T1,T2,...>
    }


    static final Result<Void> successVoid = Result.success( (Void)null );



    static <T1,T2> Async<T2> applyRA(Result<T1> result1, FunctionX<? super Result<T1>, ? extends Async<T2>> func)
    {
        try
        {
            Async<T2> async2 = func.apply(result1);
            if(async2==null)
                throw new NullPointerException("null returned from function: "+func);
            return async2;
        }
        catch (Exception e)
        {
            return Result.failure(e);
        }
    }


    static class FindNext<T> extends _AsyncDoWhile<Boolean,T>
        implements FunctionX<T, Async<Boolean>>
    {
        final AsyncIterator<T> stream;
        final FunctionX<T, Async<Boolean>> predicate;

        FindNext(AsyncIterator<T> stream, FunctionX<T, Async<Boolean>> predicate)
        {
            this.stream = stream;
            this.predicate =  predicate;
        }

        @Override
        protected Async<Boolean> action()
        {
            return _Asyncs._next(stream).then(/*T->Async<Boolean>*/this);
        }

        T tmp; // save elem temporarily

        @Override // FunctionX<T, Async<Boolean>>
        public Async<Boolean> apply(T t) throws Exception
        {
            tmp = t;
            return predicate.apply(t);
        }

        @Override
        protected Result<T> condition(Result<Boolean> result)
        {
            try
            {
                if(result.isFailure())
                    return cast(result); // loop fails with e

                Boolean bool = result.getValue();
                if(bool==null)
                    return Result.failure(new NullPointerException("predicate gives a null Boolean"));

                if(!bool.booleanValue())
                    return null; // continue loop
                else // the element is approved by the predicate
                    return Result.success(tmp);
            }
            finally
            {
                tmp = null;
            }
        }
    }


    static class Stream2AsyncIterator<T> implements AsyncIterator<T>, Consumer<T>
    {
        final Stream<T> stream;
        final Spliterator<T> spliterator;

        Stream2AsyncIterator(Stream<T> stream)
        {
            this.stream = stream;
            this.spliterator = stream.sequential().spliterator();
        }

        T element;

        @Override
        public void accept(T t)
        {
            element = t;
        }

        @Override
        public Async<T> next()
        {
            boolean b = spliterator.tryAdvance(this);
            if(!b)
            {
                try
                {
                    stream.close();
                }
                catch (Exception e)
                {
                    _Logger.of(Asyncs.class)
                        .error("Exception from Stream.close(), stream=%s, exception=%s", stream, e);
                }

                return End.async();
            }

            Async<T> result = Result.success(element);
            element = null;
            return result;
        }
    }

    static final AsyncIterator<Object> EMPTY_STREAM = End::async;

}
