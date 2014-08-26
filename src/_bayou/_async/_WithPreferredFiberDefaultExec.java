package _bayou._async;

import java.util.concurrent.Executor;

// implemented by threads that
public interface _WithPreferredFiberDefaultExec
{
    Executor getPreferredFiberDefaultExec();
}
