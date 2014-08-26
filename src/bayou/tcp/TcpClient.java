package bayou.tcp;


import _bayou._log._Logger;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.async.Fiber;
import bayou.async.Promise;
import bayou.util.function.ConsumerX;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.Executor;

/**
 * Non-blocking TCP client.
 */

// TODO: public

class TcpClient implements AutoCloseable
{
    static final _Logger logger = _Logger.of(TcpClient.class);

    /**
     * Configure each new socket.
     * <p>
     *     The default implementation
     * </p>
     * <p>
     *     Subclass can override this method to set more options.
     *     The socketChannel is in non-blocking model; it must not be changed to blocking mode.
     * </p>
     */
    // note: SO_LINGER on non-blocking socket is not well defined. enable it only if you are sure
    //     it's ok on your tcp stack.
    static final ConsumerX<SocketChannel> defaultSocketConf = socketChannel ->
    {
        socketChannel.socket().setTcpNoDelay(true);
    };



    final ClientAgent agent;

    /**
     * Create a TcpClient with a default `socketConf`.
     * See {@link #TcpClient(int, bayou.util.function.ConsumerX)}.
     * <p>
     *     The default `socketConf`
     *     enables {@link java.net.Socket#setTcpNoDelay(boolean) TCP_NODELAY} on each socket.
     * </p>
     */
    public TcpClient(int selectorId) throws Exception
    {
        this(selectorId, defaultSocketConf);
    }

    /**
     * Create a TcpClient.
     * @param selectorId
     *        the id of the selector.
     *        Note that each TcpClient uses only one selector.
     *        See also {@link TcpServer#confSelectorIds}
     * @param socketConf
     *        action to configure each newly established socket.
     *        The socketChannel is in non-blocking model; it must not be changed to blocking mode.
     */
    public TcpClient(int selectorId, ConsumerX<SocketChannel> socketConf) throws Exception
    {
        SelectorThread selectorThread = SelectorThread.acquire(selectorId); // throws, unlikely

        agent = new ClientAgent(selectorThread, socketConf);
    }


    /**
     * Get the executor associated with the selector thread.
     * <p>
     *     Tasks submitted to the executor will be executed in the selector thread.
     * </p>
     */
    public Executor getExecutor()
    {
        return agent.selectorThread;
    }

    /**
     * Get the number of established connections.
     */
    public int getConnectionCount()
    {
        return agent.nConnections_volatile;
    }
    // another method for number of pending connections? probably unnecessary.

    /**
     * Connect to a remote server.
     * <p>
     *     This action succeeds when a TcpChannel is established and ready to read/write.
     * </p>
     * <p>
     *     The default timeout is probably too long; the caller should set a reasonable timeout by
     *     `client.connect(ip, port).timeout(duration)`.
     * </p>
     */
    // be careful of the current executor; in `connect(...).then(action)`,
    //   action is run in the current executor, which might not be the selector thread.
    // suggestion: client.getExecutor().execute( ()-> connect(...).then(action) )
    //   or new Fiber(client.getExecutor(), ()->connect(...).then(action) )
    //
    // CAUTION: host->ip is a slow IO operation. Java has no async API for it yet.
    //   we don't deal with the problem in this class. connect() only accepts ip, not host.
    public Async<TcpChannel> connect(InetAddress ip, int port)
    {
        return agent.connect(ip, port);
    }

    /**
     * Close the client and free resources.
     * <p>
     *     All existing connections will be forcefully closed.
     * </p>
     */
    @Override
    public void close()
    {
        agent.selectorThread.execute( agent::onKill );
    }






    // accessed only from the selector thread
    static class ClientAgent implements ChannImpl.Agent, SelectorThread.BeforeSelect
    {
        final SelectorThread selectorThread;
        final ConsumerX<SocketChannel> socketConf;

        boolean closed;

        HashSet<Pending> allPending = new HashSet<>();

        HashSet<ChannImpl> allChann = new HashSet<>();
        volatile int nConnections_volatile; // may be read by other threads

        ArrayList<ChannImpl> interestUpdateList = new ArrayList<>();


        ClientAgent(SelectorThread selectorThread, ConsumerX<SocketChannel> socketConf)
        {
            this.selectorThread = selectorThread;
            this.socketConf = socketConf;

            selectorThread.execute( ()->selectorThread.actionsBeforeSelect.add(this) );
        }

        @Override
        public void beforeSelect()
        {
            for(ChannImpl chann : interestUpdateList)
                chann.updateInterest();
            interestUpdateList.clear();
        }

        @Override
        public void toUpdateInterest(ChannImpl chann)
        {
            interestUpdateList.add(chann);
        }


        @Override
        public void addChann(ChannImpl chann)
        {
            allChann.add(chann);
            nConnections_volatile++;
        }
        @Override
        public void removeChann(ChannImpl chann)
        {
            allChann.remove(chann);
            nConnections_volatile--;
        }

        @Override
        public boolean allowAcceptingRead()
        {
            return true; // "accepting" arg is not meaningful for client
        }



        Async<TcpChannel> connect(InetAddress ip, int port)
        {
            Promise<TcpChannel> promise = new Promise<>();
            selectorThread.execute( ()-> onInitConnect(promise, ip, port) );
            return promise;
        }

        void onInitConnect(Promise<TcpChannel> promise, InetAddress ip, int port)
        {
            if(closed)
            {
                promise.fail(new Exception("TcpClient is closed"));
                return;
            }

            SocketChannel socketChannel;
            try
            {
                socketChannel = SocketChannel.open();
            }
            catch (IOException e)
            {
                promise.fail(e);
                return;
            }

            boolean connected;
            try
            {
                socketChannel.configureBlocking(false);
                socketConf.accept(socketChannel);

                connected = socketChannel.connect(new InetSocketAddress(ip, port));
            }
            catch (Exception e)
            {
                _Util.closeNoThrow(socketChannel, logger);
                promise.fail(e);
                return;
            }

            if(connected) // connect() javadoc says this can happen. not observed in practice.
            {
                ChannImpl chann = new ChannImpl(socketChannel, selectorThread, this);
                promise.succeed(chann);
            }
            else
            {
                Pending pending = new Pending(this, socketChannel, promise);
                pending.onAwaitConnectable();
            }

        }

        void onKill()
        {
            if(closed)
                return;

            // close all connections.

            for(Pending pending : allPending)
            {
                pending.xCancel(new AsynchronousCloseException());
            }
            allPending=null;

            for(ChannImpl chann : allChann)
            {
                assert !chann.closed;
                chann.xClose();
                nConnections_volatile--;
            }
            allChann=null;

            interestUpdateList=null;

            closed = true;

            selectorThread.actionsBeforeSelect.remove(this);

            SelectorThread.release(selectorThread);

        }

    }




    static class Pending implements SelectorThread.OnSelected
    {
        ClientAgent agent;
        SocketChannel socketChannel;
        Promise<TcpChannel> connectPromise;

        Pending(ClientAgent agent, SocketChannel socketChannel, Promise<TcpChannel> promise)
        {
            this.agent = agent;
            this.socketChannel = socketChannel;
            this.connectPromise = promise;
        }

        void onAwaitConnectable()
        {
            assert !agent.closed;

            agent.allPending.add(this);

            try
            {
                socketChannel.register(agent.selectorThread.selector, SelectionKey.OP_CONNECT, this);
            }
            catch (ClosedChannelException e) // impossible
            {    throw new AssertionError(e);   }

            assert Fiber.currentExecutor() == agent.selectorThread;
            connectPromise.onCancel(this::onCancelAwaitConnectable);
        }
        void onCancelAwaitConnectable(Exception reason)
        {
            if(connectPromise==null)
                return;

            agent.allPending.remove(this);

            xCancel(reason);
        }
        void xCancel(Exception reason)
        {
            Promise<TcpChannel> promise = connectPromise;
            connectPromise = null;

            _Util.closeNoThrow(socketChannel, logger);  // selectionKey is canceled.
            promise.fail(reason);
        }

        @Override
        public void onSelected(SelectionKey sk)
        {
            assert sk.isConnectable();

            agent.allPending.remove(this);

            Promise<TcpChannel> promise = connectPromise;
            connectPromise = null;

            try
            {
                socketChannel.finishConnect(); // throws
            }
            catch (Exception e) // connect failed
            {
                // socketChannel is closed by finishConnect(). selectionKey is canceled.
                promise.fail(e);
                return; // socketChannel has never been exposed to app
            }

            // here, the client has received server ACK/SYN, and sent client ACK.

            ChannImpl chann = new ChannImpl(socketChannel, agent.selectorThread, agent);
            // pass selectionKey to chann
            sk.attach(chann);
            chann.selectionKey = sk;
            chann.interestOps = SelectionKey.OP_CONNECT;
            agent.toUpdateInterest(chann);  // OP_CONNECT will be cleared before next select()

            promise.succeed( chann );
        }


    }





}