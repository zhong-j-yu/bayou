package bayou.async;

import _bayou._tmp._Util;
import bayou.util.End;
import bayou.util.Result;
import bayou.util.function.FunctionX;

class FlatMapIterator<T,R> implements AsyncIterator<R>
{
    AsyncIterator<T> streamT; // null if it's ended
    FunctionX<T, AsyncIterator<R>> func;
    FunctionX<End, AsyncIterator<R>> endFunc;

    AsyncIterator<R> streamR;

    FlatMapIterator(AsyncIterator<T> streamT, FunctionX<T, AsyncIterator<R>> func, FunctionX<End, AsyncIterator<R>> endFunc)
    {
        this.streamT = streamT;
        this.func = func;
        this.endFunc = endFunc;
    }

    @Override
    public Async<R> next()
    {
        if(streamR!=null)
            return nextR();
        else
            return nextT();
    }

    // tail recursion: nextR()->nextT()->nextR()->...

    // cancellation: relies on streamR/streamT.next() to respond to cancellation requests.

    Async<R> nextR()
    {
        Async<R> nextR = streamR.next();

        if(streamT==null) // no more T
            return nextR;

        return nextR.transform(resultR->
        {
            Exception ex = resultR.getException();
            if(!(ex instanceof End)) // null or other exception
                return resultR;
            // streamR ended.
            streamR=null;
            return nextT();
        });
    }

    Async<R> nextT()
    {
        Async<T> nextT = streamT.next();

        return nextT.transform(resultT ->
        {
            Exception ex = resultT.getException();
            if (ex == null)
            {
                try
                {
                    streamR = func.apply(resultT.getValue()); // throws
                }
                catch (End end)
                {
                    ex = end;
                }
                if(ex==null)
                    return nextR();
                // else, feed end to endFunc
            }

            assert ex!=null;
            if (ex instanceof End)
            {
                streamR = endFunc.apply((End) ex); // throws
                streamT = null;
                func = null;
                endFunc = null;
                return nextR();
            }
            else // other exception
            {
                return _Util.<Result<R>>cast(resultT); // can cast because of erasure
            }
        });
    }

}
