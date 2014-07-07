package _bayou._tmp;

import bayou.async.Async;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;

// a timeout util for this use case:
//    a job contains a series of async actions. we want to set a timeout for the whole job.
//    when timeout is reached, cancel the current async action
public class _JobTimeout implements Runnable
{
    int state;
    static final int
        NEW=0,           // ex
        TIMING=1,        // ex, alarm, [action]
        TIMEOUT=2,       // ex
        COMPLETE=3;      //

    String timeoutMessage;

    ScheduledFuture<?> alarm;

    Async<?> currAction;


    public _JobTimeout(Duration duration, String timeoutMessage)
    {
        this.timeoutMessage = timeoutMessage;

        // note: `this` is leaked in constructor
        ScheduledFuture<?> alarm = _Exec.execNbDelayed(duration, this);

        synchronized (this)
        {
            // it's possible that alarm has fired
            switch (state)
            {
                case NEW:
                    this.alarm = alarm;
                    state = TIMING;
                    break;

                case TIMEOUT:
                    break;

                default: throw new AssertionError();
            }
        }
    }

    // user calls these methods in strict order:
    //     constructor - 0 or more setCurrAction() - complete()
    // alarm can be fired any time

    public void setCurrAction(Async<?> _currAction)
    {
        String _timeoutMessage;
        synchronized (this)
        {
            switch (state)
            {
                case TIMING:
                    currAction = _currAction;
                    return;

                case TIMEOUT:
                    _timeoutMessage = timeoutMessage;
                    break;

                default: throw new AssertionError();
            }
        }

        _currAction.cancel(new TimeoutException(_timeoutMessage));
    }

    // job is done. cancel alarm if it has not fired
    public void complete()
    {
        synchronized (this)
        {
            switch (state)
            {
                case TIMING:
                    state = COMPLETE;
                    break;
                // COMPLETE is final state, so we can do the rest of the work outside sync{}

                case TIMEOUT:
                    timeoutMessage = null;
                    state = COMPLETE;
                    return;

                default: throw new AssertionError();
            }
        }

        alarm.cancel(true);

        alarm = null;
        timeoutMessage = null;
        currAction = null;
    }


    // alarm is fired
    public void run()
    {
        Async<?> _currAction;
        String _timeoutMessage;

        synchronized (this)
        {
            switch(state)
            {
                case COMPLETE:
                    return;

                case NEW:
                    state = TIMEOUT;
                    return;

                case TIMING:
                    state = TIMEOUT;
                    alarm = null;
                    // cancel curr action
                    _currAction = currAction;
                    if(_currAction==null)
                        return;
                    currAction = null;
                    _timeoutMessage = timeoutMessage;
                    break;

                default: throw new AssertionError();
            }
        }

        _currAction.cancel(new TimeoutException(_timeoutMessage));
    }

}
