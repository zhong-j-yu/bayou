package bayou.async;

import _bayou._async._Asyncs;
import _bayou._tmp._Exec;
import bayou.util.Result;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

// for impl Async.timeout()

// Currently we use ScheduledThreadPoolExecutor for timeout.
// We may be able to create a better tool.
// In most timeout(duration) calls, duration is one of several fixed values.
// For example, we call timeout(server.confKeepAliveTimeout) a lot.
// We can have a very simple data structure for timeouts of the same duration.
// Another fact: most promise completes way before timeout is reached,
// for example, socket read/write timeout. we may optimize for that to save space/time.
//
// Currently, ScheduledThreadPoolExecutor seems fine,
// so we leave improvement for future versions.

class AsyncTimeout<T> implements Consumer<Result<T>>, Runnable
{

    Async<T> target;

    Duration duration;
    Supplier<Exception> exSupplier;  // may be null

    ScheduledFuture<?> alarm;

    public AsyncTimeout(Async<T> target, Duration duration, Supplier<Exception> exSupplier)
    {
        this.target = target;

        this.duration = duration;
        this.exSupplier = exSupplier;

        ScheduledFuture<?> alarm = _Exec.execNbDelayed(duration, this/*run()*/);
        // note: `this` is leaked. timeout could reach at any time, even before [A]
        this.alarm = alarm;  // [A]

        _Asyncs.onCompletion(target, Runnable::run, this/*accept()*/);
        // note: `this` is leaked
    }


    // we don't need precise atomic state management here;
    // it's OK if both events arrive and both are processed.
    //
    // if timeout is reached before completion (which should be rare)
    //     run()->target.cancel()->this.accept()->alarm.cancel()
    // here, alarm.cancel() is unnecessary; but it's cheap, just a volatile read.


    @Override // Consumer<Result<T>> // target is completed // after [A]
    public void accept(Result<T> result)
    {
        alarm.cancel(false);
    }

    @Override // Runnable // timeout event // may arrive before [A]
    public void run()
    {
        Exception ex = exSupplier!=null? exSupplier.get() :
            new TimeoutException(msg(duration));

        target.cancel(ex);
    }

    static String msg(Duration duration){ return "duration="+duration.toString().substring(2).toLowerCase(); }


}
