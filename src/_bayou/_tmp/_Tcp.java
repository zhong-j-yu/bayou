package _bayou._tmp;

import _bayou._async._Asyncs;
import bayou.async.Async;
import bayou.async.Promise;
import bayou.tcp.TcpChannel;

import java.nio.ByteBuffer;
import java.time.Duration;

public class _Tcp
{
    public static void validate_confSelectorIds(int[] confSelectorIds) throws IllegalArgumentException
    {
        _Util.require(confSelectorIds.length>0, "confSelectorIds.length>0");
        final int NS = confSelectorIds.length;

        // no duplicate ids
        for(int i = 0; i<NS; i++)
            for(int j=i+1; j<NS; j++)
                if(confSelectorIds[i]==confSelectorIds[j])
                    throw new IllegalArgumentException("duplicate in confSelectorIds: id="+confSelectorIds[i]);

    }

    public static Async<Void> close(TcpChannel channel, Duration drainTimeout, _ByteBufferPool bufferPool)
    {
        try
        {
            channel.shutdownOutput();
        }
        catch (Exception e)
        {
            return simply_close(channel);
        }

        if(drainTimeout!=null&&drainTimeout.toNanos()>0)
            return drain_then_close(channel, bufferPool, null).timeout(drainTimeout);
        else // no drain
            return simply_close(channel);
    }

    static Async<Void> simply_close(TcpChannel channel)
    {
        channel.close();
        return Async.VOID;
    }

    static Async<Void> drain_then_close(TcpChannel channel, _ByteBufferPool bufferPool, Promise<Void> promise)
    {
        ByteBuffer readBuffer = bufferPool.checkOut();
        try
        {
            while(true)
            {
                int r = channel.read(readBuffer); // throws
                if(r==-1) // eof. drained.
                    return done(channel, promise, null);

                if(r==0)
                {
                    Async<Void> await =  channel.awaitReadable(false);
                    final Promise<Void> promiseF = promise!=null? promise : new Promise<Void>();
                    promiseF.onCancel(await::cancel);
                    await.onCompletion(result -> {
                        if (result.isFailure())
                            done(channel, promiseF, result.getException());
                        else
                            drain_then_close(channel, bufferPool, promiseF);
                    });
                    return promiseF;
                }

                // r>0
                readBuffer.clear();
                // read again
            }
        }
        catch(Exception t) // from channel.read()
        {
            return done(channel, promise, t);
        }
        finally
        {
            bufferPool.checkIn(readBuffer);
        }
    }

    static Async<Void> done(TcpChannel channel, Promise<Void> promise, Exception ex)
    {
        channel.close();
        if(ex!=null)
            return _Asyncs.fail(promise, ex);
        else
            return _Asyncs.succeed(promise, null);
    }
}
