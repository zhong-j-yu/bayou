package _bayou._async;

import bayou.async.Async;
import bayou.async.Promise;
import bayou.util.Result;

import java.util.function.Consumer;

// async version of
//     do{ action } while( condition )
public abstract class _AsyncDoWhile<T,R> implements Consumer<Result<T>>
{
    final Promise<R> promise = new Promise<>();

    protected abstract Async<T> action();

    // return null to continue the loop
    // otherwise, loop ends, yielding the returned value.
    protected abstract Result<R> condition(Result<T> result);

    public Async<R> run()
    {
        loop();
        return promise;
    }

    void loop()
    {
        // if the promise receives a cancel request, it'll forward to `action`.
        // however `action` may miss or ignore the cancel request.
        // so the loop itself also checks and honors the cancel request.
        Exception cancelReq = promise.pollCancel();
        if(cancelReq!=null)
        {
            promise.fail(cancelReq);
            return;
        }

        Async<T> action = action();
        promise.onCancel(action::cancel);
        action.onCompletion(this);

        // if action is completed, we could optimize by a local loop, avoiding heavy onCompletion mechanism.
        // then the loop may occupy the current thread; run() may not return before loop is finished,
        // giving no chance for caller to cancel it.
        // so we don't do the optimization. in a typical async app, action is usually not so cheap,
        // so the optimization wouldn't do much good anyway.
    }

    // action completes
    @Override
    final
    public void accept(Result<T> result)
    {
        Result<R> end = condition(result);
        if(end!=null)
            promise.complete(end);
        else
            loop();
    }
}
