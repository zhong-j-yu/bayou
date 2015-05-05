package bayou.http;

import _bayou._async._Asyncs;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.async.Promise;
import bayou.bytes.ByteSource;
import bayou.util.End;
import bayou.util.Result;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

// wrap an origin source stream, create a http chunked stream.
// internally used. not checking for errors like read after close.
// origin source can yield small buffers, we accumulate them to form bigger chunks
class ImplChunkedSource implements ByteSource
{
    long bufferSize;
    ByteSource originSource;
    public ImplChunkedSource(long bufferSize, ByteSource originSource)
    {
        this.bufferSize = bufferSize;
        this.originSource = originSource;
    }

    Async<ByteBuffer> originPendingRead;

    ArrayDeque<ByteBuffer> dataBuffers = new ArrayDeque<>();  // from origin source, to be given to caller
    Exception originError; // after existing data buffers are served. may be recoverable.

    int nChunk;
    int state;
    static final int sToIssueLastChunk=2, sLastChunkIssued=3;

    // framing bytes are any bytes other than chunk-data. they are very small
    Async<ByteBuffer> frame(Promise<ByteBuffer> promise, String... strings)
    {
        byte[] bb = new byte[16];   // 16 bytes more than enough
        int ibb=0;
        for(String string : strings)
            for(int i=0; i<string.length(); i++)
                bb[ibb++] = (byte)string.charAt(i);
        return _Asyncs.succeed(promise, ByteBuffer.wrap(bb, 0, ibb));
    }

    @Override
    public Async<ByteBuffer> read()
    {
        ByteBuffer obb = dataBuffers.pollFirst();
        if(obb!=null)
            return Result.success(obb);
        if(originError!=null)
        {
            Async<ByteBuffer> failure = Result.failure(originError);
            originError=null;
            return failure;
        }

        if(state==sToIssueLastChunk)
        {
            state =sLastChunkIssued;
            return frame(null, nChunk == 0 ? "" : "\r\n", "0\r\n\r\n");
        }

        if(state==sLastChunkIssued)
            return _Util.EOF;

        return hoardAndServe(null);
    }

    // read some data from origin, up to bufferSize, or till EOF/STALL, make one chunk
    Async<ByteBuffer> hoardAndServe(Promise<ByteBuffer> promise)
    {
        long dataSize=0;
        while(true)
        {
            // [read]
            Async<ByteBuffer> originReadAsync = originPendingRead;
            if(originReadAsync!=null)
                originPendingRead = null;
            else
                originReadAsync = originSource.read();

            // if originSource.read() always completes immediately with small buffers,
            // this loop is busy and doesn't respond to cancel. that's fine.

            Result<ByteBuffer> originReadResult = originReadAsync.pollResult();
            if( originReadResult==null )
            {
                originPendingRead = originReadAsync;

                if(dataSize>0)
                    break;  // we have some hoarded data. serve them now, while origin is read pending
                // that's why _AsyncUtil.scan() won't work here; we want to end loop while a read is pending.
                // next hoardAndServe() will see originPendingRead, which may or may not have completed.

                // we got nothing at this instant while origin read is pending.
                // retry hoardAndServe after origin read is completed
                final Promise<ByteBuffer> promiseF = promise!=null? promise : new Promise<ByteBuffer>();
                promiseF.onCancel(originReadAsync::cancel);
                // if origin.read() misses or ignores cancel, so will our read().
                originReadAsync.onCompletion(byteBufferResult -> {
                    hoardAndServe(promiseF); // -> [read] -> [complete]
                    // retry may stall again, if originReadResult yield a spurious/empty bb,
                    // and next originReadResult stalls. we'll do another retry.
                });
                return promiseF;
            }

            // [complete]

            ByteBuffer obb;
            try
            {
                obb = originReadResult.getOrThrow();
            }
            catch (End end)
            {
                obb = null;
            }
            catch (Exception e)  // may be recoverable
            {
                if(dataSize>0)
                {
                    originError=e; // report it after hoarded data are served
                    break;
                }
                return _Asyncs.fail(promise, e);
            }

            if(obb==null) // EOF
            {
                state =sToIssueLastChunk;
                if(dataSize>0)
                    break;
                // else return last-chunk
                state =sLastChunkIssued;
                return frame(promise, nChunk==0?"":"\r\n", "0\r\n\r\n");
            }

            int obb_len = obb.remaining();
            if(obb_len==0) // don't queue it.
                continue;

            // obb has bytes
            dataSize += (long)obb_len;
            dataBuffers.addLast(obb);

            if(dataSize >= bufferSize)
                break;
            // we need to know chunk size ahead of time.
            // if we want to support huge chunk, we need to read and buffer a lot of data

            // it's ok if origin source gives us small buffers. our chunks will be big.
        }

        // here, dataSize>0, we have hoarded some data buffers.
        // return the frame first, followed by those data buffers.
        return frame(promise, nChunk++ == 0 ? "" : "\r\n", Long.toHexString(dataSize), "\r\n");
        // origin read might be pending
    }

    // skip()
    // never invoked by internal use

    @Override
    public Async<Void> close()
    {
        dataBuffers.clear();
        originError = null;

        if(originPendingRead==null)
            originSource.close();
        else
        {
            // can't close origin during read pending; wait till read complete
            // it's possible that our read() completes, but an origin read() is still pending.

            originPendingRead.cancel(new Exception("cancelled"));
            originPendingRead.onCompletion(result ->
                originSource.close());
            originPendingRead = null;
        }
        return Async.VOID;
    }
}
