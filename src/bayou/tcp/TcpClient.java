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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Non-blocking TCP client.
 */
public class TcpClient implements AutoCloseable
{
    static final _Logger logger = _Logger.of(TcpClient.class);

    /**
     * Configuration for {@link TcpClient}
     */
    public static class Conf
    {

        /**
         * Create a Conf with default values.
         */
        public Conf()
        {

        }

        /**
         * The id of the selector used by this client.
         * <p><code>
         *     default: a random value from [0, 1, ... N-1] where N is the number of processors
         * </code></p>
         * <p>
         *     Note that each TcpClient uses only one selector.
         * </p>
         * <p>
         *     See also {@link TcpServer.Conf#selectorIds}
         * </p>
         */
        public int selectorId = pickSelectorId();

        static final AtomicInteger selectorIdSeq = new AtomicInteger(0);
        static int pickSelectorId()
        {
            int N = Runtime.getRuntime().availableProcessors();
            int seq = selectorIdSeq.getAndIncrement();
            return seq % N;
        }

        /**
         * Action to configure each newly created socket.
         * <p><code>
         *     default action:
         *     enable {@link java.net.Socket#setTcpNoDelay(boolean) TCP_NODELAY}
         * </code></p>
         * <p>
         *     App may want to configure more options on each socket.
         *     The SocketChannel is in non-blocking model; it must not be changed to blocking mode.
         * </p>
         */
        public ConsumerX<SocketChannel> socketConf = socketChannel ->
        {
            socketChannel.socket().setTcpNoDelay(true);
        };

    }

    final ClientAgent agent;

    /**
     * Create a TcpClient.
     */
    public TcpClient(Conf conf) throws Exception
    {
        SelectorThread selectorThread = SelectorThread.acquire(conf.selectorId); // throws, unlikely

        agent = new ClientAgent(selectorThread, conf.socketConf);
    }


    /**
     * Connect to a remote server.
     * <p>
     *     This action succeeds when a TcpChannel is established and ready to read/write.
     * </p>
     * <p>
     *     The default timeout is probably too long; the caller should set a reasonable timeout by
     * </p>
     * <pre>
     *     client.connect(peerHost, ip, port).timeout(duration)
     * </pre>
     * <p>
     *     The `peerHost` argument will become the TcpChannel's
     *     {@link TcpChannel#getPeerHost() peerHost}; it can be null.
     * </p>
     */
    // be careful of the current executor; in `connect(...).then(action)`,
    //   action is run in the current executor, which might not be the selector thread.
    // suggestion: client.getExecutor().execute( ()-> connect(...).then(action) )
    //   or new Fiber(client.getExecutor(), ()->connect(...).then(action) )
    //
    // CAUTION: host->ip is a slow IO operation. Java has no async API for it yet.
    //   we don't deal with the problem in this class. connect() only accepts ip, not host.
    // peerHost: will become TcpChannel.peerHost.
    //      FQDN or IP. if FQDN, will be sent as SNI by ssl client
    //      in sun's impl, "localhost" is not considered a FQDN, therefore it won't be used as SNI.
    //          whether that is correct is debatable. this usually shouldn't be a problem.
    public Async<TcpChannel> connect(String peerHost, InetAddress ip, int port)
    {
        return agent.connect(peerHost, ip, port);
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

    /**
     * Get the executor associated with the selector thread.
     * <p>
     *     Tasks submitted to the executor will be executed on the selector thread,
     *     and executed sequentially in the order they are submitted.
     * </p>
     */
    public Executor getExecutor()
    {
        return agent.selectorThread;
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



        Async<TcpChannel> connect(String peerHost, InetAddress ip, int port)
        {
            Promise<TcpChannel> promise = new Promise<>();
            selectorThread.execute( ()-> onInitConnect(promise, peerHost, ip, port) );
            return promise;
        }

        void onInitConnect(Promise<TcpChannel> promise, String peerHost, InetAddress ip, int port)
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
                chann.peerHost = peerHost;
                promise.succeed(chann);
            }
            else
            {
                Pending pending = new Pending(this, peerHost, socketChannel, promise);
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
        String peerHost;
        SocketChannel socketChannel;
        Promise<TcpChannel> connectPromise;

        Pending(ClientAgent agent, String peerHost, SocketChannel socketChannel, Promise<TcpChannel> promise)
        {
            this.agent = agent;
            this.peerHost = peerHost;
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
            chann.peerHost = peerHost;
            // pass selectionKey to chann
            sk.attach(chann);
            chann.selectionKey = sk;
            chann.interestOps = SelectionKey.OP_CONNECT;
            agent.toUpdateInterest(chann);  // OP_CONNECT will be cleared before next select()

            promise.succeed( chann );
        }


    }





}