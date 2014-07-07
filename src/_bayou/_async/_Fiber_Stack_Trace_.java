package _bayou._async;

import bayou.async.Fiber;

import java.util.Arrays;
import java.util.HashSet;

// not used as a real exception
public class _Fiber_Stack_Trace_ extends Exception
{
    public _Fiber_Stack_Trace_()
    {

    }

    @Override
    public Throwable fillInStackTrace()
    {
        return this;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////

    public static boolean isAsyncScaffold(StackTraceElement frame)
    {
        String clazz = frame.getClassName();
        if(specialMethods.contains(clazz+"#"+frame.getMethodName()))
            return false;
        if(clazz.startsWith("bayou.util.Result"))
            return true;
        if(clazz.startsWith("_bayou._async."))
            return true;
        if(clazz.startsWith("bayou.async."))
            return true;

        return false;
    }

    static final HashSet<String> specialMethods = new HashSet<>(Arrays.asList(
        "bayou.async.Async#execute",
        // terminal ops in AsyncIterator? forEach() etc
        "bayou.async.Fiber#sleep", "bayou.async.Fiber#join",
        "bayou.async.AsyncBundle#anyOf", "bayou.async.AsyncBundle#allOf", "bayou.async.AsyncBundle#someOf"
        ));

    public static boolean isLambdaScaffold(StackTraceElement frame)
    {
        return frame.getFileName()==null
            && frame.getClassName().contains("$$Lambda$");
    }

    public static StackTraceElement[] captureTrace()
    {
        StackTraceElement[] frames = new Exception().getStackTrace();

        // bottom of stack:
        //    async scaffold code
        //    executor stack
        // remove them
        int x=frames.length-1;
        while( x>=0 && !frames[x].getClassName().equals("bayou.async.Fiber$TaskWrap"))
            x--;
        if(x==-1) // not executor stack; on some other threads
            x = frames.length-1;

        // top of stack:
        //     async scaffold code
        //     user code
        //     async scaffold code
        //     ...
        // remove scaffold

        int count=0;
        for(int i=0; i<=x; i++)
        {
            if(isAsyncScaffold(frames[i]))
            {
                frames[i] = null;
            }
            else if(isLambdaScaffold(frames[i])) // noise, not interesting
                frames[i] = null;
            else
                count++;
        }

        StackTraceElement[] trace = new StackTraceElement[count];
        count=0;
        for(int i=0; i<=x; i++)
            if(frames[i]!=null)
                trace[count++] = frames[i];
        return trace;
    }

    public static void addFiberStackTrace(Throwable exception, Fiber fiber)
    {
        if(!Fiber.enableTrace)
            return;
        if(fiber==null)
            return;

        StackTraceElement[] trace = fiber.getStackTrace();

        for(Throwable x : exception.getSuppressed())
            if(x instanceof _Fiber_Stack_Trace_ && covers(x.getStackTrace(), trace)) // avoid duplicates
                return;

        _Fiber_Stack_Trace_ traceEx = new _Fiber_Stack_Trace_();
        traceEx.setStackTrace(trace);
        exception.addSuppressed(traceEx);
    }

    public static boolean covers(StackTraceElement[] a, StackTraceElement[] b)
    {
        if(a.length<b.length)
            return false;
        for(int i=0; i<b.length; i++)
        {
            if(!eq( b[b.length-1-i], a[a.length-1-i] ))
                return false;
        }
        return true;
    }

    static boolean eq(StackTraceElement x, StackTraceElement y)
    {
        if(x.getLineNumber()<0 || y.getLineNumber()<0) // can't be certain without line number
            return false;
        return x.getLineNumber()==y.getLineNumber()
            && x.getClassName().equals(y.getClassName());
        // if class/line are the same, file/method must be the same too.
    }

}
