package bayou.async;

import bayou.util.Result;

import java.util.ArrayList;
import java.util.function.Consumer;

interface CallbackList<T> extends Consumer<Result<T>>
{
    CallbackList<T> concat(Consumer<Result<T>> callback);

    void registerTo(Async<T> async);



    static final boolean warn =false; // if true, report if there are more than 1 callbacks

    // may modify callbacks and return it
    public static <T> Consumer<Result<T>> concat(Consumer<Result<T>> callbacks, Consumer<Result<T>> callback)
    {
        if(callbacks==null)
            return callback;

        if(callbacks instanceof CallbackList)
            return ((CallbackList<T>)callbacks).concat(callback);

        if(warn) System.out.println("num of callbacks = "+2);
        return new L2<>(callbacks, callback);
    }

    // for each callback, do async2.onCompletion(callback)
    public static <T> void registerTo(Consumer<Result<T>> callbacks, Async<T> async)
    {
        if(callbacks==null)
            ;
        else if(callbacks instanceof CallbackList)
            ((CallbackList<T>)callbacks).registerTo(async);
        else
            async.onCompletion(callbacks);
    }

    // L2 is probably common.
    static class L2<T> implements CallbackList<T>
    {
        final Consumer<Result<T>> callback1, callback2;

        L2(Consumer<Result<T>> callback1, Consumer<Result<T>> callback2)
        {
            this.callback1 = callback1;
            this.callback2 = callback2;
        }

        @Override
        public void accept(Result<T> result)
        {
            callback1.accept(result);
            callback2.accept(result);
        }

        @Override
        public CallbackList<T> concat(Consumer<Result<T>> callback)
        {
            if(warn) System.out.println("num of callbacks = "+3);
            return new Lx<>(callback1, callback2, callback);
        }

        @Override
        public void registerTo(Async<T> async)
        {
            async.onCompletion(callback1);
            async.onCompletion(callback2);
        }
    }

    static class Lx<T> extends ArrayList<Consumer<Result<T>>> implements CallbackList<T>
    {
        public Lx(Consumer<Result<T>> callback1, Consumer<Result<T>> callback2, Consumer<Result<T>> callback3)
        {
            add(callback1);
            add(callback2);
            add(callback3);
        }

        @Override
        public CallbackList<T> concat(Consumer<Result<T>> callback)
        {
            if(warn) System.out.println("num of callbacks = "+ (size()+1) );
            add(callback);
            return this;
        }

        @Override
        public void accept(Result<T> result)
        {
            for(Consumer<Result<T>> callback : this)
                callback.accept(result);
        }

        @Override
        public void registerTo(Async<T> async)
        {
            for(Consumer<Result<T>> callback : this)
                async.onCompletion(callback);
        }
    }
}
