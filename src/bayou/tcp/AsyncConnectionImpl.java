package bayou.tcp;

import bayou.async.Async;
import bayou.async.Promise;
import bayou.ssl.SslConnection;
import bayou.util.End;
import bayou.util.Result;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

class AsyncConnectionImpl implements AsyncConnection
{
    final TcpConnection nbConn;

    // read/write flows are independent

    final Object lockR = new Object();
    boolean closedR;
    Promise<ByteBuffer> pendingRead;
    boolean acceptingRead;

    final Object lockW = new Object();
    boolean closedW;
    Promise<Long> pendingWrite;
    long writeN, writeW;


    public AsyncConnectionImpl(TcpConnection nbConn)
    {
        this.nbConn = nbConn;
    }

    @Override
    public InetAddress getRemoteIp()
    {
        return nbConn.getPeerIp();
    }

    // -- read --

    @Override
    public Async<ByteBuffer> read(boolean accepting)
    {
        synchronized (lockR)
        {
            if(closedR)
                return Result.failure(new AsynchronousCloseException());

            if(pendingRead!=null)
                throw new IllegalStateException("there is already a pending read");

            return read1(accepting);
        }
    }
    // todo: relation between End and FIN/CLOSE_NOTIFY
    // in SSL, if we get FIN before CLOSE_NOTIFY, maybe we should treat it as an error instead of End
    Async<ByteBuffer> read1(final boolean accepting)
    {
        ByteBuffer bb;
        try
        {
            bb = nbConn.read();
        }
        catch (Exception e)
        {
            return Result.failure(e);
        }

        if(bb== TcpConnection.TCP_FIN)
            return End.async();
        if(bb== SslConnection.SSL_CLOSE_NOTIFY)
            return End.async();

        if(bb!= TcpConnection.STALL) // data, remaining>0
            return Result.success(bb);

        // STALL
        pendingRead = new Promise<>();
        acceptingRead = accepting;
        Async<Void> await = nbConn.awaitReadable(accepting);
        pendingRead.onCancel(await::cancel);
        await.onCompletion(cbReadable);
        return pendingRead;
    }
    TcpConnection this_nbConn(){ return this.nbConn; } // to overcome IntelliJ lambda bug
    final Consumer<Result<Void>> cbReadable = result -> {
        ByteBuffer bb=null;
        Exception error=null;
        Promise<ByteBuffer> _pendingRead=null;
        synchronized (lockR)
        {
            try
            {
                if(closedR) // closed during awaiting. most likely `completion` is failure of CancelledException
                    throw new AsynchronousCloseException();

                result.getOrThrow(); // throws

                bb = this_nbConn().read(); // throws

                if(bb== TcpConnection.TCP_FIN)
                    throw End.instance();
                if(bb== SslConnection.SSL_CLOSE_NOTIFY)
                    throw End.instance();

                if(bb== TcpConnection.STALL) // spurious wakeup from awaitReadable(). await again
                {
                    Async<Void> await = this_nbConn().awaitReadable(acceptingRead);
                    pendingRead.onCancel(await::cancel);
                    await.onCompletion(this.cbReadable);
                    return;
                }

                // bb is data (remaining>0)
            }
            catch (Exception e)
            {
                error = e;
            }

            // to complete pendingRead outside
            _pendingRead = pendingRead;
            pendingRead = null;
        }

        // completing pendingRead may trigger other actions that require locking (e.g. close()).
        // so do it without lock here to avoid deadlocks.
        if(error!=null)
            _pendingRead.fail(error);
        else
            _pendingRead.succeed(bb);
    };

    @Override
    public void unread(ByteBuffer bb)
    {
        synchronized (lockR)
        {
            if(closedR)
                throw new IllegalStateException("closed");

            if(pendingRead!=null)
                throw new IllegalStateException("there is a pending read");

            nbConn.unread(bb);
        }
    }

    // -- write --

    @Override
    public long getWriteQueueSize()
    {
        synchronized (lockW)
        {
            if(closedW)
                throw new IllegalStateException("closed");

            return nbConn.getWriteQueueSize();
        }
    }

    @Override
    public long queueWrite(ByteBuffer bb)
    {
        synchronized (lockW)
        {
            if(closedW)
                throw new IllegalStateException("closed");

            return nbConn.queueWrite(bb);
        }
    }

    @Override
    public Async<Long> write(long n)
    {
        synchronized (lockW)
        {
            if(closedW)
                return Result.failure(new AsynchronousCloseException());

            if(pendingWrite!=null)
                throw new IllegalStateException("there is already a pending write");

            if(n<=0)
                throw new IllegalArgumentException("n<=0");

            if(n>nbConn.getWriteQueueSize())
                throw new IllegalArgumentException("n>getWriteQueueSize()");

            return write1(n);
        }
    }
    Async<Long> write1(long n)
    {
        long w;
        try
        {
            w = nbConn.write();  // write as much as possible without blocking
        }
        catch (Exception e)
        {
            return Result.failure(e);
        }

        if(w>=n)
            return Result.success(new Long(w));

        // write again after writable
        pendingWrite = new Promise<>();
        writeN = n;
        writeW = w;
        Async<Void> await = nbConn.awaitWritable();
        pendingWrite.onCancel(await::cancel);
        await.onCompletion(onWritable);
        return pendingWrite;
    }
    final Consumer<Result<Void>> onWritable = result -> {
        Exception error=null;
        Promise<Long> _pendingWrite;
        synchronized (lockW)
        {
            try
            {
                if(closedW) // closed during awaitWritable(). most likely completion is failure CancelledException
                    throw new AsynchronousCloseException();

                result.getOrThrow(); // throws

                writeW += this_nbConn().write(); // throws

                boolean done = (writeW>=writeN);
                if(!done) // await then write again
                {
                    Async<Void> await = this_nbConn().awaitWritable();
                    pendingWrite.onCancel(await::cancel);
                    await.onCompletion(this.onWritable);
                    return;
                }

                // done writing
            }
            catch (Exception e)
            {
                error = e;
            }

            // to complete pendingWrite outside
            _pendingWrite = pendingWrite;
            pendingWrite = null;
        }

        // completing pendingWrite may trigger other actions that require locking (e.g. close()).
        // so do it without lock here to avoid deadlocks.
        if(error!=null)
            _pendingWrite.fail(error);
        else
            _pendingWrite.succeed(new Long(writeW));
    };






    @Override
    public Executor getExecutor()
    {
        return nbConn.getExecutor();
    }

    @Override
    public void close(Duration drainTimeout)
    {
        Promise<ByteBuffer> _pendingRead;
        synchronized (lockR)
        {
            if(closedR) // another close() was called before me
                return;

            closedR = true;
            _pendingRead = pendingRead;
        }
        if(_pendingRead!=null)
            _pendingRead.cancel(new Exception("cancelled"));


        Promise<Long> _pendingWrite;
        synchronized (lockW)
        {
            closedW = true;
            _pendingWrite = pendingWrite;
        }
        if(_pendingWrite!=null)
            _pendingWrite.cancel(new Exception("cancelled"));


        // it is safe to call nbConn.close() now. closedR/W are set, r/w flows will not operate on nbConn any more.
        nbConn.close(drainTimeout);
    }
}
