package bayou.async;

import _bayou._async._Asyncs;
import _bayou._tmp._Exec;
import bayou.util.Result;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

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

class AsyncTimeout<T> implements Async<T>, Consumer<Result<T>>
{
    final Object lock(){ return this; }

    final Async<T> target;

    int state;
    static final int
        NEW=0,           //
        TIMING=1,        // alarm, callbacks
        TIMEOUT=2,       // callbacks
        COMPLETE=3;      // result

    ScheduledFuture<?> alarm;

    Consumer<Result<T>> callbacks;

    Result<T> result;

    static String msg(Duration duration){ return "duration="+duration.toString().substring(2).toLowerCase(); }

    public AsyncTimeout(Async<T> target, Duration duration)
    {
        this.target = target;

        target.onCompletion(this); // note: `this` is leaked in constructor

        schedule(duration, msg(duration)); // note: `this` is leaked in constructor
    }


    @Override
    public Async<T> timeout(Duration duration)
    {
        // it's not rare that timeout() is called on a TimeoutAsync. for example:
        //     Async<Foo> read(){ return xxx().timeout(xxx); } // default timeout
        //     read().timeout(xxx);  // user sets another timeout, usually a shorter one
        // so we optimize for it, instead of creating another TimeoutAsync

        schedule(duration, msg(duration));
        return this;
    }

    @Override
    public void cancel(Exception reason)
    {
        // direct cancelTarget.cancel() may cause deep stack. so do it async-ly
        Fiber.currentExecutor().execute(() -> target.cancel(reason));

        // we could proactively change our internal state here, e.g. unschedule alarm.
        // but we figure that after target.cancel() we'll soon receive a completion event anyway.
    }

    @Override
    public Result<T> pollResult()
    {
        return target.pollResult();
    }





    void schedule(Duration duration, String msg)
    {
        // references `this`. unschedule it if it's not used.
        ScheduledFuture<?> alarm = _Exec.execNbDelayed(duration, () -> timeoutReached(msg));

        synchronized (lock())
        {
            switch (state)
            {
                case NEW:
                    this.alarm = alarm;
                    state = TIMING;
                    return;

                case TIMING:
                    ScheduledFuture<?> prev = this.alarm;
                    if(prev.compareTo(alarm)>0) // new alarm is earlier; reschedule to new alarm
                    {
                        this.alarm = alarm;
                        alarm = prev; // will unschedule prev
                    }
                    // else prev alarm is earlier. ignore new alarm.
                    break;

                case TIMEOUT:
                    break;
                case COMPLETE:
                    break;

                default: throw new AssertionError();
            }
        }

        alarm.cancel(true);
    }

    void timeoutReached(String msg)
    {
        synchronized (lock())
        {
            switch (state)
            {
                case NEW:
                    state = TIMEOUT;
                    break;

                case TIMING:
                    alarm = null;
                    state = TIMEOUT;
                    break;

                case TIMEOUT : throw new AssertionError();

                case COMPLETE:
                    return;

                default: throw new AssertionError();
            }
        }

        target.cancel(new TimeoutException(msg));
    }


    @Override
    public void onCompletion(Consumer<Result<T>> callback)
    {
        // we could simply forward callback to `target`.
        // however, we try to keep each async with only one callback.
        // previously we have added a callback to `target`,
        // so keep the callback here in this async

        callback = _Asyncs.bindToCurrExec(callback);

        synchronized (lock())
        {
            switch (state)
            {
                case NEW: throw new AssertionError(); // user can't call this method before constructor exit

                case TIMING:
                case TIMEOUT:
                    callbacks = CallbackList.concat(callbacks, callback);
                    return;

                case COMPLETE:
                    break; // invoke callback immediately

                default: throw new AssertionError();
            }
        }

        callback.accept(result);
    }


    @Override // Consumer<Result<T>> // target is completed
    public void accept(Result<T> result)
    {
        synchronized (lock())
        {
            switch (state)
            {
                case NEW:
                case TIMING:
                case TIMEOUT:
                    this.result = result;
                    state = COMPLETE;
                    break;
                // COMPLETE is final state, so it's ok to do the rest outside sync{}

                case COMPLETE: throw new AssertionError();

                default: throw new AssertionError();
            }
        }

        if(alarm!=null)
            alarm.cancel(true);

        if(callbacks!=null)
            callbacks.accept(result);

        alarm = null;
        callbacks = null;
    }




}
