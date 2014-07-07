package bayou.async;

import _bayou._async._AsyncDoWhile;
import bayou.util.End;
import bayou.util.Result;
import bayou.util.function.ConsumerX;
import bayou.util.function.FunctionX;

import static _bayou._async._Asyncs._next;

class ForEachNoAsync<T> extends _AsyncDoWhile<Void,Void> implements FunctionX<T,Async<Void>>
{
    static final ThreadLocal<ForEachNoAsync<?>> threadLocal = new ThreadLocal<>();

    final AsyncIterator<T> iter;
    final ConsumerX<T> consumer;
    boolean warned;

    public ForEachNoAsync(AsyncIterator<T> iter, ConsumerX<T> consumer)
    {
        this.iter = iter;
        this.consumer = consumer;
    }

    @Override
    protected Async<Void> action()
    {
        return _next(iter)
            .then(/*FunctionX*/this);
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


    @Override // FunctionX
    public Async<Void> apply(T t) throws Exception
    {
        ForEachNoAsync<?> prev = threadLocal.get();
        threadLocal.set(this);
        try
        {
            consumer.accept(t);
            // it should not involve any async actions.
            // if it creates Promise/AsyncThen, we'll issue a warning, see warn()
            // the overhead of thread local seems tiny, relative to the overhead of _AsyncDoWhile
        }
        finally
        {
            threadLocal.set(prev);
        }
        return Async.VOID;
    }

    // invoked by Promise/AsyncThen constructors
    static void warn()
    {
        ForEachNoAsync<?> x = threadLocal.get();
        if(x!=null && !x.warned)
        {
            x.warned=true;
            new Exception("WARNING: AsyncIterator.forEach(action): `action` appears to be ASYNC. " +
                "Try forEach_(asyncAction) instead.")
                .printStackTrace(); // dump to console instead of to logger

            // it is extremely dangerous to pass an async action to forEach(), so we must issue a warning here.

            // we don't provide a way to disable this warning,
            // if the programmer has a legitimate use case, use forEach_(asyncAction) instead
            // (return Async.VOID in asyncAction)
        }
    }

}
