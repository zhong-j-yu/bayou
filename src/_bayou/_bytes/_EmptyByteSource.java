package _bayou._bytes;

import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.bytes.ByteSource;

import java.nio.ByteBuffer;

// read() always result in End (before closed)
// an empty source is still stateful (regarding if it's closed)
public class _EmptyByteSource implements ByteSource
{
    boolean closed;

    @Override
    public Async<ByteBuffer> read() throws IllegalStateException
    {
        if(closed)
            throw new IllegalStateException("closed");

        return _Util.EOF;
    }

    @Override
    public Async<Void> close()
    {
        closed = true;
        return Async.VOID;
    }
}
