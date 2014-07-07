package bayou.async;

import _bayou._async._Asyncs;
import _bayou._async._Fiber_Stack_Trace_;
import bayou.util.Result;
import bayou.util.function.FunctionX;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.function.Consumer;

import static _bayou._tmp._Util.cast;

@SuppressWarnings({"SynchronizationOnLocalVariableOrMethodParameter", "ConstantConditions"})
class AsyncThen<T2> implements Async<T2>
{
    volatile Pending<?,T2> pending_volatile;
    volatile Async<T2> target_volatile;

    /*

    initially in Pending state, pending=={async1, func}, target==null.
    operations in this phase must synchronize on the pending object.
        if((pending=pending_volatile)!=null)
            synchronized (pending)
                if((pending=pending_volatile)!=null)


    after `async1` is completed to result1, we compute async2=func(result1),
    then `this` /collapses/ to and /links/ to async2. `pending` is discarded.
    guaranteed:  pending_volatile==null  =>  target_volatile!=null
    no need for synchronization after collapse. (though target_volatile may still change)

    assuming no circles, the collapse relation forms trees.
    for simplicity, think of a non-AsyncThen as an AsyncThen that never collapses.

    if X links to Y, X may ask Y to /back-link/ to X,
    so that when Y collapses to Z, Y /updates/ X to link to Z. and so on.

    if X0 links to X1, we write X0--->X1. if X1 back-links to X0, we write X0<---X1. if both, X0<-->X1

    initially there is X0. when it collapses to X1
        X0--->X1
    we then add a back link:
        X0<-->X1
    when X1 collapses to X2, X1 updates X0 to X2
        X0--->X2, X1--->X2
        X0<-->X2, X1--->X2
    but we don't want to establish X1<---X2. X1 is likely an intermediary object that nobody else references,
    so we don't want X2 to back-link to X1 with a strong reference. instead we do X1--->X0
        X0<-->X2, X1--->X0
    and so on:
        X0<-->Xn, X1--->X0, X2--->X0, ...
    X0 is the top level action. (likely nobody else references X0 either. that's fine, just one extra object)
    Xn is a pending action. (a non-AsyncThen is considered in Pending state for this purpose)
    others are intermediary actions that can usually be GC-ed.

    the goal is to reduce chaining. chaining is not a concern in sequential then(), e.g.
        a.then(...).then(...)
    but in nested then(), e.g.
        a.then( b.then(..) )
    usually because of recursion, e.g.
        Async<Void> echo()
            read().then(::write).then(::echo)

    upon collapse, if a node has no back link, it's a "top" node; if it has back links,
    it's a "mid" node, it'll re-link to one of the back link and never changes.
    so any chain (eventually) contains at most 3 nodes
        mid ---> top <--> pending

    if X1 already links to X2 when X0 asks X1 to back-link, X0 will ask X2 to back-link instead.
    usually when X0 collapses to X1, X1 has not collapsed yet. this is especially true because our async exec
    serializes and postpone tasks. when X1 is created, it cannot collapse yet even if X1.async1 is completed.

    there may be legit cases where X1 collapses before X0. for example, X1 is a cached object, and multiple
    request processors call a method that returns X1. X1 then maintains multiple back links. that's fine.
    it can also occur due to scheduling uncertainty, occasionally some intermediary nodes are back linked to.
    that can accumulate and increasingly slow down the system during long time of recursions.
    we try to reduce that problem through weak references. hopefully this is rare.

     */


    // naming convention of nodes:
    // `this` object is "y". it links to "z". it back-links to "x".

    public <T1> AsyncThen(Async<T1> async1, FunctionX<? super Result<T1>, ? extends Async<T2>> func)
    {
        ForEachNoAsync.warn();

        Pending<T1,T2> pending = Fiber.enableTrace
            ? new PendingDebug<>(this, async1, func)
            : new Pending     <>(this, async1, func);

        pending_volatile = pending;

        async1.onCompletion(pending); // leak `this` in constructor
    }

    static class Pending<T1,T2> implements Consumer<Result<T1>>
    {
        // could make this class an inner class. make it static to be more clear.
        final AsyncThen<T2> then;  // enclosing object

        final Async<T1> async1;
        final FunctionX<? super Result<T1>, ? extends Async<T2>> func;

        Object backLinks; // either null, a single AsyncThen<T2>, or an ArrayList<WeakReference<AsyncThen<T2>>>
        void addBackLink(AsyncThen<T2> x)
        {
            if(backLinks ==null) // most common
            {
                backLinks = x;
            }
            else if(backLinks instanceof ArrayList)
            {
                ArrayList<WeakReference<AsyncThen<T2>>> list = cast(backLinks);
                list.add(new WeakReference<>(x));
            }
            else // a single back link
            {
                AsyncThen<T2> x0 = cast(backLinks);
                ArrayList<WeakReference<AsyncThen<T2>>> list = new ArrayList<>();
                list.add(new WeakReference<>(x0));
                list.add(new WeakReference<>(x));
                backLinks = list;
            }
        }

        Exception cancelReason;
        Consumer<Result<T2>> callbacks; // null, a single callback, or a CallbackList

        Pending(AsyncThen<T2> then, Async<T1> async1, FunctionX<? super Result<T1>, ? extends Async<T2>> func)
        {
            this.then = then;
            this.async1 = async1;
            this.func = func;
        }

        @Override // Consumer<Result<T1>>, async1 completes
        public void accept(Result<T1> result1)
        {
            Async<T2> async2 = Asyncs.applyRA(result1, func);
            then.collapseTo(this, async2);
        }
    }
    // for debugging, we do not do tail call elimination on fiber stack trace
    // so deep recursions may cause OOM error.
    // may fix this in future with a flag: fiberStackTraceTCO
    static class PendingDebug<T1,T2> extends Pending<T1,T2>
    {
        Fiber fiber; // can be null
        StackTraceElement[] trace;

        PendingDebug(AsyncThen<T2> then, Async<T1> async1, FunctionX<? super Result<T1>, ? extends Async<T2>> func)
        {
            super(then, async1, func);

            fiber = Fiber.current(); // can be null
            if(fiber!=null)
                trace = _Fiber_Stack_Trace_.captureTrace();
        }
        @Override
        public void accept(Result<T1> result1)
        {
            if(fiber!=null)
                fiber.pushStackTrace(trace);
            Async<T2> async2 = Asyncs.applyRA(result1, func);
            if(fiber!=null)
                async2.onCompletion( v-> fiber.popStackTrace(trace) );
            then.collapseTo(this, async2);
        }
    }

    void collapseTo(Pending<?, T2> pending, Async<T2> z)
    {
        assert pending_volatile==pending;
        synchronized (pending)
        {
            target_volatile = z;
            pending_volatile = null;
        }

        if(pending.cancelReason!=null)
            z.cancel(pending.cancelReason);
        // during pending phase, the cancel request was already sent to async1.
        // however async1 might have missed it, therefore we send it to z as well.
        // if async1 didn't miss it, "z" should be completed, and z.cancel() has no effect.

        // for each callback, do z.onCompletion(callback)
        CallbackList.registerTo(pending.callbacks, z);


        final AsyncThen<T2> y = this;
        if(pending.backLinks ==null)
        {
            makeBackLink(z, y);
        }
        else if(pending.backLinks instanceof ArrayList) // multiple back links, not common
        {
            AsyncThen<T2> x = null;
            ArrayList<WeakReference<AsyncThen<T2>>> list = cast(pending.backLinks);  // size>=2
            for(WeakReference<AsyncThen<T2>> xRef : list)
            {
                x = xRef.get();
                if(x==null)  // hopefully, all but one survives.
                    continue;
                x.target_volatile = z;
                makeBackLink(z, x);
            }
            if(x!=null)
                y.target_volatile = x;
            else // all back links became garbage. there's actually no back-link. unlikely
                makeBackLink(z, y);
        }
        else // a single back link
        {
            AsyncThen<T2> x = cast(pending.backLinks);
            x.target_volatile = z;
            makeBackLink(z, x);

            y.target_volatile = x;  // do this after x-->z
        }

        // `pending` is now garbage
    }

    // try to make X<--Y
    static <T2> void makeBackLink(Async<T2> y, AsyncThen<T2> x)
    {
        /* pseudo code with recursion:

           void makeBackLink(y, x)
               // x--->y
               if(y is pending)
                   x<---y
               else // y--->z
                   x--->z
                   makeBackLink(z, x);
        */
        while(y instanceof AsyncThen)
        {
            AsyncThen<T2> y_ = cast(y);
            Async<T2> z = y_.tryBackLinkTo(x);
            if(z==null) // y is pending, added back link to x
                return;
            // else, y is linking to z
            x.target_volatile = z;
            y = z;
        }
    }
    Async<T2> tryBackLinkTo(AsyncThen<T2> x)
    {
        Pending<?,T2> pending;
        if((pending=pending_volatile)!=null)
        {
            synchronized (pending)
            {
                if((pending=pending_volatile)!=null)
                {
                    pending.addBackLink(x);
                    return null;
                }
            }
        }
        // not pending. linking to z. return z.
        return target_volatile; // z
    }



    // Async methods ------------------------------------------------

    @Override
    public String toString()
    {
        Async<T2> target = target_volatile;
        if(target==null)
            return "Async:Pending";
        else
            return target.toString();
    }

    @Override
    public Result<T2> pollResult()
    {
        Async<T2> target = target_volatile;
        if(target==null)
            return null;
        else
            return target.pollResult();
    }

    @Override
    public void onCompletion(Consumer<Result<T2>> callback)
    {
        callback = _Asyncs.bindToCurrExec(callback);

        Pending<?,T2> pending;
        if((pending=pending_volatile)!=null)
        {
            synchronized (pending)
            {
                if((pending=pending_volatile)!=null)
                {
                    pending.callbacks = CallbackList.concat(pending.callbacks, callback);
                    return;
                }
            }
        }

        Async<T2> target = target_volatile;
        target.onCompletion(callback);
    }

    @Override
    public void cancel(Exception reason)
    {
        Pending<?,T2> pending;
        if((pending=pending_volatile)!=null)
        {
            synchronized (pending)
            {
                if((pending=pending_volatile)!=null)
                {
                    if(pending.cancelReason!=null)
                        return;

                    pending.cancelReason = reason;
                    // will do async1.cancel(reason);
                }
            }
        }

        Async<?> cancelTarget;
        if(pending!=null)
            cancelTarget = pending.async1;
        else
            cancelTarget = target_volatile;

        // direct cancelTarget.cancel() may cause deep stack. so do it async-ly
        Fiber.currentExecutor().execute(() -> cancelTarget.cancel(reason));
    }



}
