package bayou.tcp;


import _bayou._log._Logger;
import _bayou._tmp._Tcp;
import _bayou._tmp._Util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Non-blocking TCP server.
 * <p>
 *     A subclass overrides {@link #onAccept(TcpChannel)} to handle each new connection.
 * </p>
 * <p>
 *     (You may want to use {@link TcpServerX} with {@link TcpConnection} instead which have more features.)
 * </p>
 * <h4>Configuration</h4>
 * <p>
 *     Configure the server by setting <code>confXxx</code> variables, for example, {@link #confServerPort},
 *     before starting the server.
 * </p>
 * <p>
 *     See also {@link #confServerSocket(ServerSocketChannel)} and {@link #confSocket(SocketChannel)} methods.
 * </p>
 * <h4 id=life-cycle>Life Cycle</h4>
 * <p>
 *     The server can be in 4 states: <code>init, accepting, acceptingPaused, acceptingStopped.</code>
 * </p>
 * <p>
 *     After {@link #start()}, the server is in <code>accepting</code> state.
 *     New incoming connections are accepted.
 * </p>
 * <p>
 *     If {@link #pauseAccepting()} is called, the server is in <code>acceptingPaused</code> state.
 *     New connections are not accepted.
 *     This is useful to protect the server when some resources are about to be exhausted.
 *     The server socket port is still owned by this server.
 *     The server can be resumed to <code>accepting</code> state by {@link #resumeAccepting()}.
 * </p>
 * <p>
 *     After {@link #stopAccepting()}, the server is in <code>acceptingStopped</code> state.
 *     New connections are not accepted.
 *     The server socket port is freed and can be grabbed by another server.
 *     Existing connections are still functional until app closes them.
 *     Check the number of live connections by {@link #getConnectionCount()}.
 * </p>
 * <p>
 *     The {@link #stopAll()} call will stop accepting new connections and kill all existing connections.
 *     The server is then back to the <code>init</code> state.
 * </p>
 * <p>
 *     You can use {@link #stop(java.time.Duration) stop(graceTimeout)} to gracefully stop the server.
 * </p>
 */

// abstract class/method is not a good design choice. too late to fix it now.
// no big deal, since this class is rarely used directly by app.

abstract public class TcpServer
{
    static final _Logger logger = _Logger.of(TcpServer.class);

    /**
     * Handle an accepted channel.
     * <p>
     *     This method is invoked when a new incoming connection is accepted;
     *     subclass overrides this method to handle it.
     * </p>
     * <p>
     *     This method must not block; must not throw Exception.
     * </p>
     */
    abstract protected void onAccept(TcpChannel channel);



    // conf -------------------------------------------------------------------------------


    /**
     * IP address the server socket binds to.
     * <p><code>
     *     default: the wildcard address
     * </code></p>
     * <p>See {@link InetSocketAddress}.</p>
     */
    public InetAddress confServerIp = new InetSocketAddress(2000).getAddress();
    // null is ok, meaning the wildcard address. however it's better non-null.

    /**
     * Port number the server socket binds to.
     * <p><code>
     *     default: 2048
     * </code></p>
     */
    public int confServerPort = 2048; // not a well-known or registered port.
    // 0 is ok, meaning an automatic port

    /**
     * Ids of selectors for this server.
     * <p><code>
     *     default: [0, 1, ... N-1] where N is the number of processors
     * </code></p>
     * <p>
     *     Conceptually there are infinitely number of selectors, each associated with a dedicated thread.
     *     A server may choose to use any one or several selectors.
     *     Different servers/clients can share same selectors or use different selectors.
     * </p>
     */
    public int[] confSelectorIds = defaultSelectorIds();

    static int[] defaultSelectorIds()
    {
        int N = Runtime.getRuntime().availableProcessors();
        int[] ids = new int[N];
        for(int i=0; i<N; i++)
            ids[i] = i;
        return ids;
    }

    /**
     * Server socket backlog.
     * <p><code>
     *     default: 50
     * </code></p>
     * <p>See {@link ServerSocket#bind(SocketAddress endpoint, int backlog)}.</p>
     */
    public int confServerSocketBacklog = 50;

    /**
     * Max number of connections. Must be positive.
     * <p><code>
     *     default: {@link Integer#MAX_VALUE}
     * </code></p>
     * <p>
     *     If this limit is reached, no new incoming connections are accepted,
     *     until some existing connections are closed.
     * </p>
     */
    public int confMaxConnections = Integer.MAX_VALUE;

    /**
     * Max number of connections per IP. Must be positive.
     * <p><code>
     *     default: {@link Integer#MAX_VALUE}
     * </code></p>
     * <p>
     *     For any IP, if this limit is reached, no new incoming connections are accepted,
     *     until some existing connections are closed.
     * </p>
     */
    public int confMaxConnectionsPerIp = Integer.MAX_VALUE;
    // max conn per ip is a defence against abuse. it should be set quite high.
    // finer control, like max conn per specific ips, is not provided here.

    /**
     * Configure the server socket.
     * <p>
     *     This method is invoked before
     *     {@link ServerSocket#bind(SocketAddress endpoint, int backlog) bind()}.
     * </p>
     * <p>
     *     The default implementation enables {@link ServerSocket#setReuseAddress(boolean) SO_REUSEADDR}
     *     if the OS is not Windows.
     * </p>
     * <p>
     *     Subclass can override this method to set more options, e.g.
     *     {@link ServerSocket#setReceiveBufferSize(int)}.
     *     The serverSocketChannel is in non-blocking model; it must not be changed to blocking mode.
     * </p>
     */
    protected void confServerSocket(ServerSocketChannel serverSocketChannel) throws Exception
    {
        if(!_Util.isWindows())
            serverSocketChannel.socket().setReuseAddress(true);
        // if we enable SO_REUSEADDR on windows, two servers can bind to the same port!

    }


    // the receive buffer can be as large as the BDP - Bandwidth Delay Product, which may >64KB
    // leave the setting to subclass
    // in a request-response protocol, receive buffer only needs to hold one whole typical request;
    // no point to receive more data before we respond.
    // for http, a request head isn't too big; default buffer size should be fine.
    // if request has a body, default size can under utilize the network - but only for that request
    // from that client. for some app, we may want BDP to fully utilize network for such requests.


    /**
     * Configure each newly accepted socket.
     * <p>
     *     The default implementation enables {@link Socket#setTcpNoDelay(boolean) TCP_NODELAY}.
     * </p>
     * <p>
     *     Subclass can override this method to set more options.
     *     The socketChannel is in non-blocking model; it must not be changed to blocking mode.
     * </p>
     */
    // note: SO_LINGER on non-blocking socket is not well defined. enable it only if you are sure
    //     it's ok on your tcp stack.
    protected void confSocket(SocketChannel socketChannel) throws Exception
    {
        // Nagle's algorithm is only a defense against careless applications.
        // A well written application can only be hurt by it. It should've never existed.
        socketChannel.socket().setTcpNoDelay(true);
        // setting socket option requires a system call; it can take thousands of nanos. no big deal?
        // we may consider add get/setOption() to NbChannel/Connection,
        // more flexibility for user to set option whenever desired.
    }

    // ------------------------------------------------------------------------------------

    final Object lock = new Object();

    /**
     * The server socket channel.
     */
    protected ServerSocketChannel serverSocketChannel;
    // we expose it in confServerSocket() anyway, so make it protected.
    // subclass may read info from it, e.g. the actual ip/port after bind()

    ServerAgent[] serverAgentList; // one per selector thread
    Phaser phaser;
    enum ServerState{ err, init, accepting, acceptingPaused, acceptingStopped, allStopped }
    volatile ServerState state;
    ConcurrentHashMap<InetAddress,AtomicInteger> ip2Channs;

    /**
     * Create a TcpServer. The server is in <code>init</code> state.
     */
    protected TcpServer()
    {
        synchronized (lock)
        {
            state = ServerState.init;
        }
    }

    ServerState getState()
    {
        synchronized (lock)
        {
            return state;
        }
    }

    /**
     * Start the server. See <a href="#life-cycle">Life Cycle</a>.
     */
    public void start() throws Exception
    {
        synchronized (lock)
        {
            if(state !=ServerState.init)
                throw new IllegalStateException(state.toString());

            state =ServerState.err; // will be reset later if nothing goes wrong
            {
                final int NS = confSelectorIds.length;
                _Tcp.validate_confSelectorIds(confSelectorIds);

                _Util.require(confMaxConnections>0, "confMaxConnections>0");
                int maxConnPerAgent = confMaxConnections/NS;
                if(maxConnPerAgent*NS!=confMaxConnections)
                    ++maxConnPerAgent;
                // maxConnPerAgent>0

                _Util.require(confMaxConnectionsPerIp>0, "confMaxConnectionsPerIp>0");
                if(confMaxConnectionsPerIp<Integer.MAX_VALUE)
                    ip2Channs = new ConcurrentHashMap<>();

                InetSocketAddress serverAddress = new InetSocketAddress(confServerIp, confServerPort);
                serverSocketChannel = ServerSocketChannel.open();
                serverSocketChannel.configureBlocking(false);
                confServerSocket(serverSocketChannel);
                serverSocketChannel.socket().bind(serverAddress, confServerSocketBacklog); // throws

                phaser = new Phaser(NS+1); // for server to sync state with selectors

                serverAgentList = new ServerAgent[NS];
                for(int i=0; i< NS; i++)
                {
                    SelectorThread selectorThread = SelectorThread.acquire(confSelectorIds[i]); // throws, unlikely tho
                    serverAgentList[i] = new ServerAgent(i, this, selectorThread, maxConnPerAgent);
                }
            }
            state =ServerState.acceptingPaused;

            resumeAccepting();
        }
    }

    /**
     * Get the number of connections.
     */
    public int getConnectionCount()
    {
        ServerAgent[] serverAgentList;
        synchronized (lock)
        {
            serverAgentList = this.serverAgentList;
        }

        if(serverAgentList ==null)
            return 0;

        int n=0;
        for(ServerAgent sa : serverAgentList)
            n += sa.nConnections_volatile;
        return n;
    }

    /**
     * Pause accepting new connections. See <a href="#life-cycle">Life Cycle</a>.
     */
    public void pauseAccepting()
    {
        synchronized (lock)
        {
            if(state !=ServerState.accepting)
                throw new IllegalStateException(state.toString());

            for(ServerAgent sa : serverAgentList)
                sa.selectorThread.execute( sa::onPauseAccepting);

            phaser.arriveAndAwaitAdvance();
            // afterwards, no new chann will be accepted, and awaitReadable(accepting=false) will fail.

            state =ServerState.acceptingPaused;
            // we still own the port.
        }
    }

    /**
     * Resume accepting new connections. See <a href="#life-cycle">Life Cycle</a>.
     */
    public void resumeAccepting()
    {
        synchronized (lock)
        {
            if(state !=ServerState.acceptingPaused)
                throw new IllegalStateException(state.toString());

            for(ServerAgent sa : serverAgentList)
                sa.selectorThread.execute( ()->sa.onResumeAccepting(serverSocketChannel) );

            ServerAgent sa = serverAgentList[0]; // anyone would do
            sa.selectorThread.execute(sa::onBecomeAccepter);

            state =ServerState.accepting;
        }
    }

    /**
     * Stop accepting new connections. See <a href="#life-cycle">Life Cycle</a>.
     */
    public void stopAccepting()
    {
        synchronized (lock)
        {
            if(state !=ServerState.acceptingPaused)
                pauseAccepting(); // blocks till all selectors have handled `onPauseAccepting`

            _Util.closeNoThrow(serverSocketChannel, logger);
            serverSocketChannel=null;
            // we don't own the port now. another server can take the port.

            state =ServerState.acceptingStopped;
        }
    }

    /**
     * Stop accepting new connections and kill all connections. See <a href="#life-cycle">Life Cycle</a>.
     */
    public void stopAll()
    {
        synchronized (lock)
        {
            if(state !=ServerState.acceptingStopped)
                stopAccepting();

            for(ServerAgent sd : serverAgentList)
                sd.selectorThread.execute( sd::onKill);  // close all connections, brutely

            phaser.arriveAndAwaitAdvance();

            for(ServerAgent sd : serverAgentList)
                SelectorThread.release(sd.selectorThread);

            serverAgentList = null;
            phaser=null;
            ip2Channs =null;

            state =ServerState.allStopped;
        }
    }

    /**
     * Gracefully stop the server.
     * <ol>
     *     <li>
     *         Call {@link #stopAccepting()} to stop accepting new connections.
     *     </li>
     *     <li>
     *         Wait for {@link #getConnectionCount()} to reach 0, within `graceTimeout`.
     *     </li>
     *     <li>
     *         Call {@link #stopAll()}.
     *     </li>
     * </ol>
     */
    public void stop(Duration graceTimeout)
    {
        synchronized (lock)
        {
            long deadline = System.currentTimeMillis() + graceTimeout.toMillis();

            stopAccepting();

            while(System.currentTimeMillis()<deadline && getConnectionCount()>0)
            {
                try
                {
                    Thread.sleep(10);
                }
                catch (InterruptedException e)
                {
                    break;
                }
            }

            stopAll();
        }
    }

    // number of connections per ip. in very rare cases we may allow an ip a few extra connections.
    boolean ip2Channs_tryInc(InetAddress ip)
    {
        if(ip2Channs ==null) // no limit
            return true;

        AtomicInteger counter = ip2Channs.get(ip);
        if(counter==null) // probably common
        {
            counter = new AtomicInteger(1);
            AtomicInteger counter_x = ip2Channs.putIfAbsent(ip, counter);
            if(counter_x==null) // no conflict
                return true;
            //otherwise, another thread wins in init the counter. very rare
            counter = counter_x;
        }

        int channs = counter.get();
        if(channs>=confMaxConnectionsPerIp)
            return false;
        counter.incrementAndGet(); // non-atomic check-inc. may over max. rare and fine
        return true;
    }
    void ip2Channs_dec(InetAddress ip)
    {
        if(ip2Channs ==null) // no limit
            return;
        AtomicInteger counter = ip2Channs.get(ip);
        if(counter==null) // possible due to under-count
            return;
        int channs = counter.decrementAndGet();

        if(channs<=0) // <0 is possible due to under-count
            ip2Channs.remove(ip);
        // non-atomic check-remove, we may lose inc from another thread, then we'll under-count. rare and fine
    }




    // one per selector. accessed by only select flow
    static class ServerAgent implements ChannImpl.Agent, SelectorThread.OnSelected, SelectorThread.BeforeSelect
    {
        final int index;
        final TcpServer server;
        final SelectorThread selectorThread;
        final int maxConnPerAgent;

        // non-null only when server is accepting
        ServerSocketChannel serverSocketChannel;
        SelectionKey acceptSK;

        volatile int nConnections_volatile;
        HashSet<ChannImpl> allChann = new HashSet<>();

        ArrayList<ChannImpl> interestUpdateList = new ArrayList<>();

        ServerAgent(int index, TcpServer server, SelectorThread selectorThread, int maxConnPerAgent)
        {
            this.index = index;
            this.server = server;
            this.selectorThread = selectorThread;
            this.maxConnPerAgent = maxConnPerAgent;

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
            // this method is called by new ChannImpl()
            // nConnections_volatile &  ip2Channs handled by caller
        }
        @Override
        public void removeChann(ChannImpl chann)
        {
            allChann.remove(chann);
            nConnections_volatile--;
            server.ip2Channs_dec(chann.getRemoteIp());
        }

        @Override
        public boolean allowAcceptingRead()
        {
            return acceptSK!=null;
        }


        // only one thread is the "accepter" at a time.
        // accepted connections are distributed among all threads evenly.
        // then, the thread with the least connections becomes the next accepter

        void onResumeAccepting(ServerSocketChannel serverSocketChannel)
        {
            this.serverSocketChannel = serverSocketChannel;
            try
            {
                acceptSK = serverSocketChannel.register(selectorThread.selector, 0, this);
                // note: interestOp==0, this thread is not the "accepter" yet
            }
            catch(ClosedChannelException e) // impossible
            {
                throw new AssertionError(e);
            }
        }

        void onBecomeAccepter()
        {
            if(acceptSK==null)  // an onPauseAccepting event came earlier
                return;

            acceptSK.interestOps(SelectionKey.OP_ACCEPT);

            // we could immediately try accept() here, but it most likely would fail.
        }

        @Override
        public void onSelected(SelectionKey sk)
        {
            ServerAgent[] serverAgentList = server.serverAgentList;
            int connList[] = new int[serverAgentList.length];
            for(int i=0; i<serverAgentList.length; i++)
                connList[i] = serverAgentList[i].nConnections_volatile;
            // we are not counting connections still queued for onInitChann()
            // which can be a problem on a very busy server

            while(true)
            {
                SocketChannel socketChannel;
                try
                {   socketChannel = serverSocketChannel.accept();   }
                catch(Exception e) // fatal, can't handle
                {   throw new RuntimeException(e);   }
                // note: serverSocketChannel can be closed only after PauseAccepting,
                // therefore it must be not-closed here.

                if(socketChannel==null)
                    break;

                // tcp handshake was complete, the client considers that the connection is established.
                // however the server may immediately close the connection because of limits,
                // the client won't know until it reads from or write to the connection.

                // dispatch the connection to the thread with the least connections
                int indexMin = findMinConn(this.index, connList);
                ++connList[indexMin];
                ServerAgent agent = serverAgentList[indexMin];
                agent.selectorThread.execute( ()->agent.onInitChann(socketChannel) );
                // typically, agent==this, in which case we still postpone onInitChann() to later time
                // because we want to do accept() first to empty the backlog.

                // try accept() again.
                // typically, next accept() will return null. unless there's a flood of connections.
            }

            // choose the next "accepter", the thread with the least connections
            int indexMin = findMinConn(this.index, connList);
            if(indexMin!=this.index)
            {
                ServerAgent agent = serverAgentList[indexMin];
                agent.selectorThread.execute(agent::onBecomeAccepter);
                acceptSK.interestOps(0);
            }
            // else, this thread is still the accepter.

        }
        // find the thread with the least connections. preferably this thread.
        static int findMinConn(int thisIndex, int[] connList)
        {
            int indexMin = thisIndex;
            int connMin = connList[indexMin];
            for(int i=0; i<connList.length; i++)
                if(connList[i]<connMin)
                    connMin = connList[indexMin=i];
            return indexMin;
        }

        Void _abandon(SocketChannel socketChannel)
        {
            --nConnections_volatile;
            _Util.closeNoThrow(socketChannel, logger);
            return (Void)null;
        }
        Void onInitChann(SocketChannel socketChannel)
        {
            ++nConnections_volatile; // inc it asap. won't overflow.

            if(acceptSK==null) // onPauseAccepting arrived earlier
                return _abandon(socketChannel);

            if(nConnections_volatile>maxConnPerAgent)
                return _abandon(socketChannel);

            InetAddress ip = socketChannel.socket().getInetAddress();

            if(!server.ip2Channs_tryInc(ip)) // too many connections from that ip
                return _abandon(socketChannel);

            try
            {
                socketChannel.configureBlocking(false);
                server.confSocket(socketChannel);
            }
            catch(Exception e)
            {
                _Util.logUnexpected(logger, e);
                server.ip2Channs_dec(ip);
                return _abandon(socketChannel);
            }

            ChannImpl chann = new ChannImpl(socketChannel, selectorThread, this);
            try
            {
                server.onAccept(chann); // user code, must not throw
            }
            catch(RuntimeException t) // probably not crippling. not sure what to do with chann here.
            {
                _Util.logUnexpected(logger, t);
            }
            return (Void)null;
        }


        void onPauseAccepting()
        {
            assert acceptSK!=null;

            acceptSK.cancel();
            acceptSK=null;
            serverSocketChannel=null;

            // for every chann in awaitReadable(accepting=true), call back with error
            for(ChannImpl chann : allChann)
            {
                if(chann.acceptingR) // => chann.readablePromise!=null
                {
                    chann.onReadable(new IOException("server stops accepting new connections"));
                    // most likely callback will raise a close event
                }
            }

            server.phaser.arrive();
            // afterwards, event issuer may choose to close server socket.
            //
            // also it's guaranteed that no more new chann will be created,
            // and no more successful readable of "accepting" nature
            // (so for http, no more new http requests)
            // event issuer may rely on this guarantee for its state management.
        }

        void onKill()
        {
            assert acceptSK==null; // after PauseAccepting

            // close all connections.
            for(ChannImpl chann : allChann)
            {
                assert !chann.closed;
                chann.xClose();
                // that may invoke r/w callback with error, which may add more (close) events.
                // those events are queued and processed by event loop later. (not orphan)
            }
            allChann=null;
            nConnections_volatile=0;

            interestUpdateList=null;

            selectorThread.actionsBeforeSelect.remove(this);

            server.phaser.arrive();
            // event issuer now knows that all chann closed; no read/write/awaitRW/yield() works.
        }

    }


}