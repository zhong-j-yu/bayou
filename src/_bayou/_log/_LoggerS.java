package _bayou._log;

import _bayou._async._Fiber_Stack_Trace_;
import _bayou._tmp._Exec;
import bayou.async.Fiber;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Function;

class _LoggerS
{
    // not volatile. can only be set in the very beginning of app, or it'll be too late.
    static Function<String, _Logger> loggerProvider =
        (name) -> new _SimpleLogger(name, _Logger._Level.INFO, System.err);

    static _Logger getLogger(String name)
    {
        return loggerProvider.apply(name);
    }




    static void asyncPrint(_Logger logger, _Logger._Level level, CharSequence message, Throwable throwable)
    {
        try
        {
            asyncExec.execute(new Print(logger, level, message, throwable));
        }
        catch (RejectedExecutionException e)
        {
            // logging is shut down. new logs are ignored.
        }
    }

    static final ThreadPoolExecutor asyncExec = _Exec.newSerialExecutor("Bayou Logging");
    // unbounded queue. will cause out-of-memory if logger is too slow

    // no shutdown hook for last effort of flushing the queue when system exits

    static class Print implements Runnable
    {
        _Logger logger;

        _Logger._Level level;
        CharSequence message;
        Throwable throwable;

        String threadName;

        Print(_Logger logger, _Logger._Level level, CharSequence message, Throwable throwable)
        {
            this.logger = logger;
            this.level = level;
            this.message = message;
            this.throwable = throwable;

            // we capture the thread name, later set it to the thread that invokes the actual logger,
            // so that the actual logger can get the thread name in the usual way.
            //
            // unfortunately we cannot do that with the logging time; the actual logger will see
            // a little delayed time. probably not a big deal.

            this.threadName = Thread.currentThread().getName();

            Fiber<?> fiber = Fiber.current();
            if(fiber!=null)
            {
                this.threadName += " #fiber# "+fiber.getName();

                if(Fiber.enableTrace && throwable!=null)
                    _Fiber_Stack_Trace_.addFiberStackTrace(throwable, fiber);
            }
        }

        @Override
        public void run() // invoke actual logger
        {
            Thread thread = Thread.currentThread();
            String prevThreadName = thread.getName();

            {
                thread.setName(threadName);
                // this will confuse someone reviewing all threads, seeing 2 threads with the same name
            }
            try
            {
                logger.impl_log(level, message, throwable);
            }
            catch (Exception|Error e)
            {
                e.printStackTrace();
                // all loggers are probably broken.
                // maybe we should shutdown asyncExec.
            }
            finally
            {
                thread.setName(prevThreadName);
            }

        }
    }

}
