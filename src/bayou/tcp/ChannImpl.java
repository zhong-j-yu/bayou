package bayou.tcp;

import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.async.Fiber;
import bayou.async.Promise;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;

// internally termed as 'chann', to distinguish it from Java NIO channel
class ChannImpl implements TcpChannel, SelectorThread.OnSelected
{
    interface Agent
    {
        void addChann(ChannImpl chann);
        void removeChann(ChannImpl chann);

        boolean allowAcceptingRead();

        // don't update chann interest immediately.
        // chan interest may change multiple times for opposite purposes. example case:
        //   a chann becomes readable - we turn off read interest - awaitReadable() turns it back on.
        // wait till beforeSelect() to call chann.updateInterest()
        void toUpdateInterest(ChannImpl chann);
    }


    // these two fields can be accessed by any flow
    final SocketChannel socketChannel;
    final SelectorThread selectorThread;

    // following fields are accessed only by selector flow
    Agent agent;

    boolean closed;

    SelectionKey selectionKey; // may be null; register when needed
    int interestOps; // of the selectionKey

    boolean acceptingR;
    Promise<Void> readablePromise;

    Promise<Void> writablePromise;

    ChannImpl(SocketChannel socketChannel, SelectorThread selectorThread, Agent agent)
    {
        this.socketChannel = socketChannel;
        this.selectorThread = selectorThread;

        this.agent = agent;
        this.agent.addChann(this);
    }

    String peerHost;
    @Override
    public String getPeerHost()
    {
        return peerHost;
    }

    @Override
    public InetAddress getPeerIp()
    {
        return socketChannel.socket().getInetAddress();
    }

    @Override
    public int getPeerPort()
    {
        return socketChannel.socket().getPort();
    }



    @Override public int read(ByteBuffer bb) throws Exception
    {
        return socketChannel.read(bb);
    }
    @Override public Async<Void> awaitReadable(boolean accepting)
    {
        Promise<Void> promise = new Promise<>();
        selectorThread.execute( ()->onAwaitReadable(accepting, promise) );
        return promise;
    }

    @Override public long write(ByteBuffer... srcs) throws Exception
    {
        return socketChannel.write(srcs);
    }

    @Override public void shutdownOutput() throws Exception
    {
        socketChannel.shutdownOutput();
    }

    @Override public Async<Void> awaitWritable()
    {
        Promise<Void> promise = new Promise<>();
        selectorThread.execute( ()->onAwaitWritable(promise) );
        return promise;
    }

    @Override
    public Executor getExecutor()
    {
        return selectorThread;
    }

    @Override public void close() // no throw
    {
        selectorThread.execute( this::onCloseChann  );
    }

    // before select
    void updateInterest()
    {
        if(closed)
            return;

        int newOps = 0;
        if(readablePromise!=null)
            newOps |= SelectionKey.OP_READ;
        if(writablePromise!=null)
            newOps |= SelectionKey.OP_WRITE;

        if(newOps==interestOps)
            return;

        // else update interestOps on selection key
        interestOps=newOps;
        if(selectionKey!=null)
        {
            selectionKey.interestOps(newOps); // this is not cheap; that's why we try to avoid it
        }
        else // not registered yet. chann.interestOps was zero, therefore newOps must be non-zero
        {
            try
            {
                selectionKey=socketChannel.register(selectorThread.selector, newOps, this); // expensive
            }
            catch (ClosedChannelException e) // impossible
            {    throw new AssertionError(e);   }
        }
    }

    @Override
    public void onSelected(SelectionKey sk)
    {
        if(sk.isReadable())
        {
            onReadable(null);
        }
        if(sk.isWritable())
        {
            onWritable(null);
        }
    }




    void onAwaitReadable(boolean accepting, Promise<Void> promise)
    {
        if(readablePromise!=null) // programming error
        {
            promise.fail(new IllegalStateException("already awaiting readable"));
        }
        else if(closed) // likely closed by non-read flow; or agent is killed
        {
            promise.fail(new AsynchronousCloseException());
        }
        else if(accepting && !agent.allowAcceptingRead())
        {
            promise.fail(new IOException("server is not accepting"));
        }
        else
        {
            acceptingR = accepting;
            readablePromise = promise;
            agent.toUpdateInterest(this); // to turn on read interest

            promise.onCancel( reason -> onCancelAwaitReadable(promise, reason) );
        }
    }
    void onCancelAwaitReadable(Promise<Void> promise, Exception reason)
    {
        // cancel can come arbitrarily late; check to see if it's still relevant
        if(promise==readablePromise)
            onReadable(reason);
    }
    void onReadable(Exception error) // no throw
    {
        acceptingR = false;
        Promise<Void> promise = readablePromise;
        readablePromise = null;
        agent.toUpdateInterest(this); // to turn off read interest

        if(error==null)
            promise.succeed(null);
        else
            promise.fail(error);
    }


    void onAwaitWritable(Promise<Void> promise)
    {
        if(writablePromise!=null) // programming error
        {
            promise.fail(new IllegalStateException("already awaiting writable"));
        }
        else if(closed) // likely closed by non-write flow; or agent is killed
        {
            promise.fail(new AsynchronousCloseException());
        }
        else
        {
            writablePromise = promise;
            agent.toUpdateInterest(this); // to turn on write interest

            promise.onCancel( reason -> onCancelAwaitWritable(promise, reason) );
        }
    }
    void onCancelAwaitWritable(Promise<Void> promise, Exception reason)
    {
        // cancel can come arbitrarily late; check to see if it's still relevant
        if(promise==writablePromise)
            onWritable(reason);
    }
    void onWritable(Exception error) // no throw
    {
        Promise<Void> promise = writablePromise;
        writablePromise = null;
        agent.toUpdateInterest(this); // to turn off write interest

        if(error==null)
            promise.succeed(null);
        else
            promise.fail(error);
    }


    void onCloseChann()
    {
        // not unusually to try to close a chann multiple times
        if(closed)
            return;

        xClose();

        agent.removeChann(this);
        // do not call removeChann() inside xClose() -
        // server/client may iterate allChann and xClose() each chann.

        // no need to remove chann from interestUpdateList. chann.updateInterest() checks chann.closed.
    }
    void xClose() // can be triggered by chann close, server/client stop
    {
        assert !closed;

        _Util.closeNoThrow(socketChannel, TcpServer.logger); // will cancel selection key if any
        selectionKey=null; // possibly was null (never registered)
        interestOps=0;

        // if awaiting r/w-able, call back with exception.
        if(readablePromise !=null)
        {
            onReadable(new AsynchronousCloseException());
            // callback may add another close event
        }
        if(writablePromise !=null)
        {
            onWritable(new AsynchronousCloseException());
            // callback may add another close event
        }

        closed=true;
        // keep chann 's reference to socketChannel and selectorFlow
    }

}
