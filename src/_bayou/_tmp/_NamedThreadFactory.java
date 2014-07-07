package _bayou._tmp;

import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

public class _NamedThreadFactory implements ThreadFactory
{
    final Supplier<String> nameSupplier;

    public _NamedThreadFactory(final String name)
    {
        this(() -> name);
    }

    public _NamedThreadFactory(Supplier<String> nameSupplier)
    {
        this.nameSupplier = nameSupplier;
    }

    @Override public Thread newThread(Runnable r)
    {
        Thread thread = new Thread(r, nameSupplier.get());
        // it inherits current thread's daemon-ness, priority, and thread-group.
        // need to reset the first two. thread groups are obsolete so we don't care.
        thread.setDaemon(false);
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    }
}
