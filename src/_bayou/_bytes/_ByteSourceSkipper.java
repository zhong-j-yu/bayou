package _bayou._bytes;

// a wrapper source, where skip(n) is guaranteed to return n
// if origin.skip() isn't guaranteed, we'll read-and-discard internally

import _bayou._async._Asyncs;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.bytes.ByteSource;

import java.nio.ByteBuffer;

public class _ByteSourceSkipper implements ByteSource
{
    final ByteSource origin;
    long toSkip;

    public _ByteSourceSkipper(ByteSource origin)
    {
        this.origin = origin;
    }

    @Override
    public long skip(long n) throws IllegalArgumentException
    {
        _Util.require(n>=0, "n>=0");

        toSkip += n;

        toSkip -= origin.skip(toSkip); // may throw

        return n;
    }

    @Override
    public Async<ByteBuffer> read()
    {
        if(toSkip==0) // fast path
        {
            return origin.read();
        }

        return _Asyncs.scan(origin::read,
            bb ->
            {
                long r = (long) bb.remaining();
                if (r <= toSkip) // discard entire bb
                {
                    toSkip -= r;
                    return null; // read again
                }

                // 0<=toSkip<r
                if (toSkip > 0)
                {
                    bb.position(bb.position() + (int) toSkip);
                    toSkip = 0;
                }
                return bb; // loop ends with bb
            },
            end ->
            {
                // if origin EOF, this EOF too.
                throw end;
            }
        );
    }



    @Override
    public Async<Void> close()
    {
        return origin.close();
    }
}
