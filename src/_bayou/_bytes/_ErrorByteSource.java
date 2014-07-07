package _bayou._bytes;

import bayou.async.Async;
import bayou.bytes.ByteSource;
import bayou.util.Result;

import java.nio.ByteBuffer;

// read() always result in error (before closed)
// an error source is still stateful (regarding if it's closed)
public class _ErrorByteSource implements ByteSource
{
    Async<ByteBuffer> failure;
    boolean closed;

    public _ErrorByteSource(Exception exception)
    {
        this.failure = Result.failure(exception);
    }

    @Override
    public Async<ByteBuffer> read() throws IllegalStateException
    {
        if(closed)
            throw new IllegalStateException("closed");

        return failure;
    }

    @Override
    public Async<Void> close()
    {
        closed = true;
        failure = null;
        return Async.VOID;
    }
}
