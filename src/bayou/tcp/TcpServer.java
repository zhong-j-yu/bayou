package bayou.tcp;


import _bayou._log._Logger;
import _bayou._tmp._Tcp;
import _bayou._tmp._Util;
import bayou.util.function.ConsumerX;

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
import java.util.function.Consumer;

/**
 * Non-blocking TCP server.
 * <p>
 *     A TcpServer can be bound to multiple addresses;
 *     each address has a corresponding channel handler.
 *     See {@link Conf#handlers}.
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
 *     The server socket ports are still owned by this server.
 *     The server can be resumed to <code>accepting</code> state by {@link #resumeAccepting()}.
 * </p>
 * <p>
 *     After {@link #stopAccepting()}, the server is in <code>acceptingStopped</code> state.
 *     New connections are not accepted.
 *     The server socket ports are freed and can be grabbed by another server.
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

public class TcpServer
{
    static final _Logger logger = _Logger.of(TcpServer.class);



    /**
     * Configuration for TcpServer.
     */
    public static class Conf
    {
        /**
         * Create a Conf with default values
         */
        public Conf()
        {

        }

        /**
         * Handlers for each server address.
         * <p><code>
         *     default: empty
         * </code></p>
         * <p>
         *     A channel handler is invoked whenever a new incoming connection is accepted on the address;
         *     the handler must not block, must not throw Exception.
         * </p>
         * <p>
         *     Example:
         * </p>
         * <pre>
         *     conf.handlers.put( new InetSocketAddress(port), channel-&gt;{...} );
         * </pre>
         */
        public Map<InetSocketAddress, Consumer<TcpChannel>> handlers = new HashMap<>();

        /**
         * Ids of selectors for this server.
         * <p><code>
         *     default: [0, 1, ... N-1] where N is the number of processors
         * </code></p>
         * <p>
         *     Conceptually there are infinite number of selectors, each associated with a dedicated thread.
         *     A server may choose to use any one or several selectors.
         *     Different servers/clients can share same selectors or use different selectors.
         * </p>
         */
        public int[] selectorIds = _Tcp.defaultSelectorIds();


        /**
         * Server socket backlog.
         * <p><code>
         *     default: 50
         * </code></p>
         * <p>See {@link ServerSocket#bind(SocketAddress endpoint, int backlog)}.</p>
         */
        public int serverSocketBacklog = 50;

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
        public int maxConnections = Integer.MAX_VALUE;

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
        public int maxConnectionsPerIp = Integer.MAX_VALUE;
        // max conn per ip is a defence against abuse. it should be set quite high.
        // finer control, like max conn per specific ips, is not provided here.

        /**
         * Action to configure the server socket.
         * <p><code>
         *     default action:
         *     enable {@link ServerSocket#setReuseAddress(boolean) SO_REUSEADDR} if the OS is not Windows.
         * </code></p>
         * <p>
         *     App may want to configure more options on the server socket, e.g.
         *     {@link ServerSocket#setReceiveBufferSize(int)}.
         *     The ServerSocketChannel is in non-blocking model; it must not be changed to blocking mode.
         * </p>
         * <p>
         *     The action will be invoked before
         *     {@link ServerSocket#bind(SocketAddress endpoint, int backlog) ServerSocket.bind()}.
         * </p>
         */
        public ConsumerX<ServerSocketChannel> serverSocketConf = serverSocketChannel ->
        {
            if(!_Util.isWindows())
                serverSocketChannel.socket().setReuseAddress(true);
            // if we enable SO_REUSEADDR on windows, two servers can bind to the same port!
        };

        // the receive buffer can be as large as the BDP - Bandwidth Delay Product, which may >64KB
        // leave the setting to subclass
        // in a request-response protocol, receive buffer only needs to hold one whole typical request;
        // no point to receive more data before we respond.
        // for http, a request head isn't too big; default buffer size should be fine.
        // if request has a body, default size can under utilize the network - but only for that request
        // from that client. for some app, we may want BDP to fully utilize network for such requests.




        /**
         * Action to configure each newly accepted socket.
         * <p><code>
         *     default action:
         *     enable {@link Socket#setTcpNoDelay(boolean) TCP_NODELAY}
         * </code></p>
         * <p>
         *     App may want to configure more options on each socket.
         *     The SocketChannel is in non-blocking model; it must not be changed to blocking mode.
         * </p>
         */
        // note: SO_LINGER on non-blocking socket is not well defined. enable it only if you are sure
        //     it's ok on your tcp stack.
        public ConsumerX<SocketChannel> socketConf = socketChannel ->
        {
            // Nagle's algorithm is only a defense against careless applications.
            // A well written application can only be hurt by it. It should've never existed.
            socketChannel.socket().setTcpNoDelay(true);
            // setting socket option requires a system call; it can take thousands of nanos. no big deal?
            // we may consider add get/setOption() to NbChannel/Connection,
            // more flexibility for user to set option whenever desired.
        };

    }

    // ------------------------------------------------------------------------------------

    final Conf conf;

    final Object lock = new Object();

    LinkedHashMap<ServerSocketChannel, Consumer<TcpChannel>> handlers;

    ServerAgent[] serverAgentList; // one per selector thread
    enum ServerState{ err, init, accepting, acceptingPaused, acceptingStopped, allStopped }
    volatile ServerState state;
    ConcurrentHashMap<InetAddress,AtomicInteger> ip2Channs;

    /**
     * Create a TcpServer. The server is in <code>init</code> state.
     */
    public TcpServer(Conf conf)
    {
        this.conf = conf;

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

            ArrayDeque<Runnable> rollbacks = new ArrayDeque<>();
            try
            {
                final int NS = conf.selectorIds.length;
                _Tcp.validate_confSelectorIds(conf.selectorIds);

                _Util.require(conf.maxConnections >0, "confMaxConnections>0");
                int maxConnPerAgent = conf.maxConnections /NS;
                if(maxConnPerAgent*NS!=conf.maxConnections)
                    ++maxConnPerAgent;
                // maxConnPerAgent>0

                _Util.require(conf.maxConnectionsPerIp >0, "confMaxConnectionsPerIp>0");
                if(conf.maxConnectionsPerIp <Integer.MAX_VALUE)
                    ip2Channs = new ConcurrentHashMap<>();

                handlers = new LinkedHashMap<>();
                for(Map.Entry<InetSocketAddress, Consumer<TcpChannel>> entry : conf.handlers.entrySet())
                {
                    InetSocketAddress serverAddress = entry.getKey();
                    Consumer<TcpChannel> handler = entry.getValue();

                    ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
                    rollbacks.addFirst(() -> _Util.closeNoThrow(serverSocketChannel, logger));

                    serverSocketChannel.configureBlocking(false);
                    conf.serverSocketConf.accept(serverSocketChannel);
                    serverSocketChannel.socket().bind(serverAddress, conf.serverSocketBacklog); // may fail

                    handlers.put(serverSocketChannel, handler);
                }

                serverAgentList = new ServerAgent[NS];
                for(int i=0; i< NS; i++)
                {
                    SelectorThread selectorThread = SelectorThread.acquire(conf.selectorIds[i]); // throws, unlikely tho
                    rollbacks.addFirst(() -> SelectorThread.release(selectorThread));
                    serverAgentList[i] = new ServerAgent(i, this, selectorThread, maxConnPerAgent);
                }
            }
            catch(Exception e)
            {
                state =ServerState.err;

                rollbacks.forEach( Runnable::run );
                serverAgentList=null;
                handlers=null;
                ip2Channs = null;

                throw e;
            }

            for(ServerAgent sa : serverAgentList)
                sa.selectorThread.execute( sa::onInit );

            ServerAgent sa0 = serverAgentList[0];
            sa0.selectorThread.execute( sa0::onBecomeAcceptor0 );
            // onBecomeAcceptor0 must be sent AFTER all threads received onInit

            state=ServerState.accepting;

        } // synchronized(lock)
    }

    // note that our use of `lock` guarantees that each selector thread receives these events in good order
    //     onInit -> [onBecomeAccepter0] ->
    //     *( onPauseAccepting -> onResumeAccepting -> )
    //     onStop -> onKill
    // if we don't enforce the order here, the selector thread then needs to handle out of order events.


    /**
     * Get server sockets.
     * <p>
     *     Returns an empty set if the server is not started, or has been stopped.
     * </p>
     */
    public Set<ServerSocketChannel> getServerSockets()
    {
        HashSet<ServerSocketChannel> set = new HashSet<>();
        synchronized (lock)
        {
            if(handlers!=null)
                set.addAll( handlers.keySet() ); // copy
        }
        return set;
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

            Phaser phaser = new Phaser(serverAgentList.length+1);

            for(ServerAgent sa : serverAgentList)
                sa.selectorThread.execute( ()->sa.onPauseAccepting(phaser) );

            phaser.arriveAndAwaitAdvance();
            // afterwards, no new chann will be accepted, and awaitReadable(accepting=false) will fail.

            state =ServerState.acceptingPaused;
            // we still own the port.
        }
    }

    // # during pause, the server sockets are not closed; we retain the ports so that others cannot poach them.
    // however, as long as the backlog is not full, clients can still complete handshakes,
    // consider the connections established; client write/read still works, just blocked on waiting.
    // # that is not good. therefore during pause, we still do accept(), but immediately close the socket,
    // sending FIN to client.
    // # after receiving server FIN, client read should succeed with FIN. client write should fail with RST;
    // tho, write()s immediately after connect() may appear to succeed to client app.
    // # if this is not desired, server app can call stopAccepting() instead, so that client connect() won't succeed.
    // (server may not respond with RST to client SYN, leaving client waiting for a long time)
    // to resume accepting, start a new server.

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
                sa.selectorThread.execute(sa::onResumeAccepting);

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
                pauseAccepting();

            Phaser phaser = new Phaser(serverAgentList.length+1);

            for(ServerAgent sa : serverAgentList)
                sa.selectorThread.execute(()->sa.onStopAccepting(phaser));

            phaser.arriveAndAwaitAdvance();
            // afterwards, server sockets can be closed

            for(ServerSocketChannel serverSocketChannel : handlers.keySet())
                _Util.closeNoThrow(serverSocketChannel, logger);
            handlers=null;
            // we don't own the ports now. another server can take the ports.

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

            Phaser phaser = new Phaser(serverAgentList.length+1);

            for(ServerAgent sd : serverAgentList)
                sd.selectorThread.execute( ()->sd.onKill(phaser) );  // close all connections, brutely

            phaser.arriveAndAwaitAdvance();

            for(ServerAgent sd : serverAgentList)
                SelectorThread.release(sd.selectorThread);

            serverAgentList = null;
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
        if(channs>=conf.maxConnectionsPerIp)
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
    static class ServerAgent implements ChannImpl.Agent, SelectorThread.BeforeSelect
    {
        final int index;
        final TcpServer server;
        final SelectorThread selectorThread;
        final int maxConnPerAgent;

        boolean accepting = true;
        ArrayList<AcceptAgent> acceptAgents;
        // for each address, only one thread is the "accepter" at a time that does serverSocket.accept()
        // accepted connections are distributed among all threads evenly.
        // then, the thread with the least connections becomes the next accepter for the address.
        // initially thread[0] is the accepter.
        // during pause (accepting=false), accepter is still working, by accept() and discard.

        volatile int nConnections_volatile;
        HashSet<ChannImpl> allChann = new HashSet<>();

        ArrayList<ChannImpl> interestUpdateList = new ArrayList<>();

        ServerAgent(int index, TcpServer server, SelectorThread selectorThread, int maxConnPerAgent)
        {
            this.index = index;
            this.server = server;
            this.selectorThread = selectorThread;
            this.maxConnPerAgent = maxConnPerAgent;

            // call onInit() on the selector thread.
        }

        void onInit()
        {
            acceptAgents = new ArrayList<>();
            // `handlers` has predictable iteration order, same across threads.
            // therefore `acceptAgents` have same order across threads.
            server.handlers.forEach((serverSocketChannel, handler)->
            {
                AcceptAgent aa = new AcceptAgent(acceptAgents.size(), this, serverSocketChannel, handler);
                acceptAgents.add(aa);
                try
                {
                    aa.acceptSK = serverSocketChannel.register(selectorThread.selector, 0, aa);
                }
                catch(ClosedChannelException e) // impossible
                {
                    throw new AssertionError(e);
                }
            });

            selectorThread.actionsBeforeSelect.add(this);
        }

        void onBecomeAcceptor0() // cannot be part of onInit of serverAgent0; must be arranged after ALL onInit.
        {
            acceptAgents.forEach( aa->aa.acceptSK.interestOps(SelectionKey.OP_ACCEPT) );
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
            server.ip2Channs_dec(chann.getPeerIp());
        }

        @Override
        public boolean allowAcceptingRead()
        {
            return accepting;
        }

        void onResumeAccepting()
        {
            assert !accepting;
            accepting = true;
            acceptAgents.forEach( aa -> aa.accepting = true );
        }

        void onBecomeAccepter(int aaIndex)
        {
            if(acceptAgents==null)  // onStopAccepting event came earlier
                return;

            AcceptAgent aa = acceptAgents.get(aaIndex);
            aa.acceptSK.interestOps(SelectionKey.OP_ACCEPT);
            // we could immediately try accept() here, but it most likely would fail.

            if(trace)trace("onBecomeAccepter ", Thread.currentThread().getName(), aa.serverSocketChannel);
        }

        static class AcceptAgent implements SelectorThread.OnSelected
        {
            int aaIndex;
            int saIndex;
            ServerAgent[] serverAgentList;
            ServerSocketChannel serverSocketChannel;
            Consumer<TcpChannel> handler;

            SelectionKey acceptSK;
            boolean accepting=true;

            AcceptAgent(int aaIndex, ServerAgent sa, ServerSocketChannel serverSocketChannel, Consumer<TcpChannel> handler)
            {
                this.aaIndex = aaIndex;

                saIndex = sa.index;
                serverAgentList = sa.server.serverAgentList;

                this.serverSocketChannel = serverSocketChannel;
                this.handler = handler;

                // acceptSK is set later
            }

            @Override
            public void onSelected(SelectionKey sk)
            {
                int connList[] = new int[serverAgentList.length];
                for(int i=0; i<serverAgentList.length; i++)
                    connList[i] = serverAgentList[i].nConnections_volatile;
                // a local understanding of number of connections on each thread.
                // not accounting for updates by other threads at the same time.

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

                    if(trace)trace("accept()", Thread.currentThread().getName(), socketChannel);
                    // tcp handshake was complete, the client considers that the connection is established.
                    // however the server may immediately close the connection because of limits,
                    // the client won't know until it reads from or writes to the connection.

                    if(!accepting) // PauseAccepting
                    {
                        _Util.closeNoThrow(socketChannel, logger);
                        continue;
                    }

                    // dispatch the connection to the thread with the least connections
                    int indexMin = findMinConn(saIndex, connList);
                    ++connList[indexMin];
                    ServerAgent agent = serverAgentList[indexMin];
                    agent.selectorThread.execute( ()->agent.onInitChann(socketChannel, handler) );
                    // typically, agent==this, in which case we still postpone onInitChann() to later time
                    // because we want to do accept() first to empty the backlog.

                    // try accept() again.
                    // typically, next accept() will return null. unless there's a flood of connections.

                } // while(true)

                // choose the next "accepter", the thread with the least connections
                int indexMin = findMinConn(saIndex, connList);
                if(indexMin!= saIndex)
                {
                    ServerAgent agent = serverAgentList[indexMin];
                    agent.selectorThread.execute( ()->agent.onBecomeAccepter(aaIndex) );
                    acceptSK.interestOps(0);
                }
                // else, this thread is still the accepter.

            } // onSelected

        } // AcceptAgent

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
        Void onInitChann(SocketChannel socketChannel, Consumer<TcpChannel> handler)
        {
            if(trace)trace("onInitChann", Thread.currentThread().getName(), socketChannel);

            ++nConnections_volatile; // inc it asap. won't overflow.

            if(!accepting) // onPauseAccepting arrived earlier
                return _abandon(socketChannel);

            if(nConnections_volatile>maxConnPerAgent)
                return _abandon(socketChannel);

            InetAddress ip = socketChannel.socket().getInetAddress();

            if(!server.ip2Channs_tryInc(ip)) // too many connections from that ip
                return _abandon(socketChannel);

            try
            {
                socketChannel.configureBlocking(false);
                server.conf.socketConf.accept(socketChannel);
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
                handler.accept(chann); // user code, must not throw
            }
            catch(RuntimeException t) // probably not crippling.
            {
                chann.close();
                _Util.logUnexpected(logger, t);
            }
            return (Void)null;
        }


        void onPauseAccepting(Phaser phaser)
        {
            assert accepting;
            accepting=false;
            acceptAgents.forEach( aa -> aa.accepting = false );
            // acceptSK not touched. accept() calls are still going on.

            // for every chann in awaitReadable(accepting=true), call back with error
            for(ChannImpl chann : allChann)
            {
                if(chann.acceptingR) // => chann.readablePromise!=null
                {
                    chann.onReadable(new IOException("server stops accepting new connections"));
                    // most likely callback will raise a close event
                }
            }

            phaser.arrive();
            // afterwards, event issuer may choose to close server socket.
            //
            // also it's guaranteed that no more new chann will be created,
            // and no more successful readable of "accepting" nature
            // (so for http, no more new http requests)
            // event issuer may rely on this guarantee for its state management.
        }

        void onStopAccepting(Phaser phaser)
        {
            assert !accepting; // after PauseAccepting
            acceptAgents.forEach(aa -> aa.acceptSK.cancel());
            acceptAgents =null;

            phaser.arrive();
        }

        void onKill(Phaser phaser)
        {
            assert acceptAgents ==null; // after StopAccepting

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

            phaser.arrive();
            // event issuer now knows that all chann closed; no read/write/awaitRW/yield() works.
        }

    }


    static final boolean trace = false;
    synchronized static void trace(Object... args)
    {
        System.out.print("TcpServer ");
        for(Object arg : args)
        {
            System.out.print(arg);
            System.out.print(" ");
        }
        System.out.println();
    }

}