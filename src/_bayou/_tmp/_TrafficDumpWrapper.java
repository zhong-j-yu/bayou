package _bayou._tmp;

import _bayou._tmp._Exec;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;

// `dump` is invoked on a single thread. preventing interleaving outputs.
public class _TrafficDumpWrapper
{
    public final String name;
    public final Consumer<CharSequence> dump;

    // 1 thread, not daemon, expires after 10sec. unbounded queue. tasks are serialized
    final ThreadPoolExecutor serialExec;

    public _TrafficDumpWrapper(String name, Consumer<CharSequence> dump)
    {
        this.name = name;
        this.dump = dump;

        serialExec = _Exec.newSerialExecutor(name+" Traffic Dump");
    }

    public void print(CharSequence... chars)
    {
        print(new _Array2ReadOnlyList<>(chars));
    }

    public void print(List<? extends CharSequence> chars)
    {
        Runnable task = () ->
        {
            try
            {
                for(CharSequence cs : chars)
                    dump.accept(cs);
            }
            catch (Error | RuntimeException e ) // printer is broken
            {
                serialExec.shutdownNow();

                System.err.println(name+" trafficDump is broken: "+e);
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
