package bayou.http;

import _bayou._tmp._Exec;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;

// for concurrent access
class HttpAccessLoggerWrapper
{
    final Consumer<HttpAccess> printer;

    // 1 thread, not daemon, expires after 10sec. unbounded queue. tasks are serialized
    final ThreadPoolExecutor serialExec;

    public HttpAccessLoggerWrapper(Consumer<HttpAccess> printer)
    {
        this.printer = printer;

        serialExec = _Exec.newSerialExecutor("Http Access Logger");
    }

    public void print(HttpAccess entry)
    {
        Runnable task = () ->
        {
            try
            {
                printer.accept(entry);
            }
            catch (Error | RuntimeException e ) // printer is broken
            {
                serialExec.shutdownNow();

                System.err.println("Bayou HttpAccessLog printer is broken: "+e);
                e.printStackTrace(); // dump to console instead of to logger
            }
        };

        try
        {
            serialExec.execute(task);
        }
        catch (RejectedExecutionException e)
        {
            // ok. printer was broken. new logs are ignored.
        }
    }
}
