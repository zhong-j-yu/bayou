package _bayou._async;

import _bayou._tmp._Util;
import bayou.async.*;
import bayou.util.End;
import bayou.util.Result;
import bayou.util.function.FunctionX;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

public class _Asyncs
{
    public static <T> Async<T> succeed(Promise<T> promise, T result)
    {
        if(promise==null)
            return Result.success(result);

        promise.succeed(result);
        return promise;
    }

    public static <T> Async<T> fail(Promise<T> promise, Exception exception)
    {
        if(promise==null)
            return Result.failure(exception);

        promise.fail(exception);
        return promise;
    }


    public static <T> Async<T> _next(AsyncIterator<T> stream)
    {
        try
        {
            return stream.next();
        }
        catch (Exception e)
        {
            return Result.failure(e);
        }
    }

    // repeatedly feed elements to scanner, until scanner yields a non-null value.
    //   if scanner returns non-null R r, loop ends, yield r.
    //   if scanner returns null, go on to next element.
    //   if scanner throws exception e, loop fails with e.
    // if stream ends before loop ends, use endScanner (it must not return null)
    //   if endScanner returns non-null R r, loop ends, yield r.
    //   if endScanner throws exception e, loop fails with e.
    // used for parsing
    //
    // sync analogue:
    //     R scan(Stream<T> stream) throws Exception // returns non-null
    //          while(true)
    //              T t; End end;
    //              try
    //                 t = stream.next(); // throws Exception, End
    //              catch(End e)
    //                 end = e;
    //
    //              R r = scanner.apply(t) OR endScanner.apply(end) // throws Exception (may be End)
    //              if(R!=null)
    //                  return r;
    //
    // we don't expose this API. programmer usually can use AsyncIterator.flatMap() to accomplish his task.
    //
    public static <T, R> Async<R> scan(AsyncIterator<T> stream, FunctionX<T, R> scanner, FunctionX<End, R> endScanner)
    {
        class Scan extends _AsyncDoWhile<R,R> implements FunctionX<Result<T>, Async<R>>
        {
            @Override
            protected Async<R> action()
            {
                return _next(stream).transform(/*FunctionX*/this);
            }

            @Override // FunctionX<Result<T>, Async<R>>
            public Async<R> apply(Result<T> result) throws Exception
            {
                Exception ex = result.getException();
                if(ex==null)
                    return Result.success(scanner.apply(result.getValue()));  // throws
                if(ex instanceof End)
                    return Result.success(endScanner.apply((End) ex)); // throws
                return _Util.cast(result);
            }

            @Override
            protected Result<R> condition(Result<R> result)
            {
                Exception e = result.getException();
                if(e!=null)
                    return result; // loop fail with e
                R r = result.getValue();
                if(r!=null)
                    return result; // loop ends, successfully, with r
                return null; // continue loop
            }
        }

        return new Scan().run();
    }


    public static <T> Consumer<T> bindToCurrExec(Consumer<T> consumer)
    {
        if(consumer instanceof ConsumerInExecutor<?>)
            return consumer;
        else
            return new ConsumerInExecutor<>(Fiber.currentExecutor(), consumer);
    }

    // todo: expose this to user, as Async.onCompletion(executor, callback). note its danger.
    public static <T> void onCompletion(Async<T> async, Executor executor, Consumer<Result<T>> callback)
    {
        callback = new ConsumerInExecutor<>(executor, callback);
        async.onCompletion(callback); // which calls bindToCurrExec()
    }

    // if pending, **block** till completed.
    //    no timeout parameter. try await( async.timeout(...) )
    //    if interrupted while being blocked, cancel(interruptedException) is called on async,
    //
    // blocking is not expected in async app. maybe useful for unit test.
    // good thing that this method is not an instance method of Async.
    // in unit test, Asyncs.await(x) is more clear than x.await()
    //
    // deadlock is possible if await() blocks the thread that would be processing completion callbacks of async,
    // e.g. the current thread is in the event processing loop. of course it's insane to call await() on this thread.
    public static <T> Result<T> await(Async<T> async)
    {
        // performance not a concern.

        if(async.isCompleted())
            return async.pollResult();
        // if async is completed, and we use the solution below, deadlock is possible.
        // though user is insane to call await() on an event processing thread, we don't want to
        // explain the deadlock in this case where it seems reasonable to return immediately.

        return new Consumer<Result<T>>() // odd coding, for fun
        {
            Result<T> result;

            {
                async.onCompletion(this);
            }

            @Override
            public void accept(Result<T> result)
            {
                synchronized (this)
                {
                    assert result!=null;
                    this.result = result;
                    this.notify();
                }
            }

            Result<T> await()
            {
                synchronized (this)
                {
                    while(result==null)
                    {
                        try
                        {
                            this.wait();
                        }
                        catch (InterruptedException e)
                        {
                            async.cancel(e);
                        }
                    }
                    return result;
                }
            }

        }.await();
    }

    static class ConsumerInExecutor<T> implements Consumer<T>, Runnable
    {
        Executor executor;
        Consumer<T> consumer;
        T result;

        ConsumerInExecutor(Executor executor, Consumer<T> consumer)
        {
            this.executor = executor;
            this.consumer = consumer;
        }

        @Override
        public void accept(T result)
        {
            assert this.result==null; // this consumer is invoked only once.

            this.result = result;
            executor.execute(this);
        }

        @Override
        public void run()
        {
            consumer.accept(result);
        }
    }
}
