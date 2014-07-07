package bayou.tcp;


import _bayou._async._WithThreadLocalFiber;
import _bayou._log._Logger;
import _bayou._tmp._Exec;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.async.Promise;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Phaser;
import java.util.concurrent.ThreadPoolExecutor;
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
abstract public class TcpServer
{
    // ## Flow ##
    //
    // a flow is a sequence of actions each happens-before the next. flows can intersect, merge and split.
    // usually for each chann, read(or write) related actions form one read(or write) flow.
    //
    // we have N selectors. each chann belong to one selector. all select related actions happen on the selector flow.
    // originally each selector has a thread, and the flow is same as the thread.
    // after a selector is killed, further actions related to the selector are done on the orphan event
    // executor flow. that is also considered on the selector flow.
    //
    // when a select related action, e.g. awaitReadable, needs to be carried out,
    // it's submitted as an event, which the selector flow will pick up and process.
    //
    // onAccept() starts both read and write flows for a chann, and they are on the selector flow.
    // r/w flow can split from selector flow, e.g. to do some blocking actions.
    // use the executor to bring the flow back to selector flow.

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

    // hide this conf for now; fixed as number of CPUs
    //    we may change selector thread design later. multiple servers may share selectors.
    int confNumberOfSelectors = Runtime.getRuntime().availableProcessors();
    // note WindowsSelectorImpl creates one sub-selector/thread for every 1024 channels.

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

    /**
     * The server socket channel.
     */
    protected ServerSocketChannel serverSocketChannel;
    // we expose it in confServerSocket() anyway, so make it protected.

    SelectorFlow[] selectorFlows;
    Phaser phaser;
    enum ServerState{ err, init, accepting, acceptingPaused, acceptingStopped }
    volatile ServerState state_volatile;
    ConcurrentHashMap<InetAddress,AtomicInteger> ip2Channs;

    /**
     * Create a TcpServer. The server is in <code>init</code> state.
     */
    protected TcpServer()
    {
        synchronized (this)
        {
            state_volatile = ServerState.init;
        }
    }

    // note: many methods are synchronized

    /**
     * Start the server. See <a href="#life-cycle">Life Cycle</a>.
     */
    synchronized
    public void start() throws Exception
    {
        if(state_volatile !=ServerState.init)
            throw new IllegalStateException(state_volatile.toString());

        state_volatile =ServerState.err; // will be reset later if nothing goes wrong
        {
            _Util.require(confMaxConnectionsPerIp>0, "confMaxConnectionsPerIp>0");
            if(confMaxConnectionsPerIp<Integer.MAX_VALUE)
                ip2Channs = new ConcurrentHashMap<>();

            InetSocketAddress serverAddress = new InetSocketAddress(confServerIp, confServerPort);
            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.configureBlocking(false);
            confServerSocket(serverSocketChannel);
            serverSocketChannel.socket().bind(serverAddress, confServerSocketBacklog);

            final int NS = confNumberOfSelectors;
            phaser = new Phaser(NS+1); // for server to sync state with selectors

            _Util.require(confMaxConnections>0, "confMaxConnections>0");
            int maxConnPerSelector = Math.max(confMaxConnections / NS, 1); // limit evenly distributed to selectors
            selectorFlows = new SelectorFlow[NS];
            for(int i=0; i< NS; i++)
                selectorFlows[i] = new SelectorFlow(i, this, serverSocketChannel, maxConnPerSelector);

            for(int i=0; i< NS; i++)
                selectorFlows[i].start();
            phaser.arriveAndAwaitAdvance();
        }
        state_volatile =ServerState.accepting;
    }

    /**
     * Get the number of connections.
     */
    synchronized
    public int getConnectionCount()
    {
        SelectorFlow[] sts = selectorFlows;
        if(sts==null)
            return 0;

        int n=0;
        for(SelectorFlow st : sts)
            n += st.nConnections_volatile;
        return n;
    }

    /**
     * Pausing accepting new connections. See <a href="#life-cycle">Life Cycle</a>.
     */
    synchronized
    public void pauseAccepting() throws Exception
    {
        if(state_volatile !=ServerState.accepting)
            throw new IllegalStateException(state_volatile.toString());

        for(SelectorFlow st : selectorFlows)
            st.addEvent(st.new PauseAcceptingEvent());
        phaser.arriveAndAwaitAdvance();
        // afterwards, no new chann will be accepted, and awaitReadable(accepting=false) will fail.

        state_volatile =ServerState.acceptingPaused;
        // we still own the port.
    }

    /**
     * Resume accepting new connections. See <a href="#life-cycle">Life Cycle</a>.
     */
    synchronized
    public void resumeAccepting() throws Exception
    {
        if(state_volatile !=ServerState.acceptingPaused)
            throw new IllegalStateException(state_volatile.toString());

        for(SelectorFlow st : selectorFlows)
            st.addEvent(st.new ResumeAcceptingEvent(serverSocketChannel));

        state_volatile =ServerState.accepting;
    }

    /**
     * Stop accepting new connections. See <a href="#life-cycle">Life Cycle</a>.
     */
    synchronized
    public void stopAccepting() throws Exception
    {
        if(state_volatile !=ServerState.acceptingPaused)
            pauseAccepting();

        ServerSocketChannel ssc = serverSocketChannel; // to be closed
        serverSocketChannel=null;

        state_volatile =ServerState.acceptingStopped;

        ssc.close(); // may throw; don't care

        // we don't own the port now. another server can take the port.
    }

    /**
     * Stop accepting new connections and kill all connections. See <a href="#life-cycle">Life Cycle</a>.
     */
    synchronized
    public void stopAll() throws Exception
    {
        if(state_volatile !=ServerState.acceptingStopped)
            stopAccepting();

        for(SelectorFlow st : selectorFlows)
            st.addEvent(st.new KillEvent());  // close all connections, brutely
        phaser.arriveAndAwaitAdvance();

        selectorFlows =null;
        phaser=null;
        ip2Channs =null;

        state_volatile =ServerState.init;
        // user could call start() again on this server. but there's not much value.
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
    synchronized
    public void stop(Duration graceTimeout) throws Exception
    {
        long deadline = System.currentTimeMillis() + graceTimeout.toMillis();

        stopAccepting();

        while(System.currentTimeMillis()<deadline && getConnectionCount()>0)
            Thread.sleep(10);

        stopAll();
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


    // internally termed as 'chann', to distinguish from Java NIO channel
    static class ChannImpl implements TcpChannel
    {
        // following fields can be accessed by any flow
        final SocketChannel socketChannel;
        final SelectorFlow selectorFlow;
        final int id;
        ChannImpl(SocketChannel socketChannel, SelectorFlow selectorFlow, int id)
        {
            this.socketChannel = socketChannel;
            this.selectorFlow = selectorFlow;
            this.id=id;
        }

        // for the HashSet of all ChannImpl of a selector.
        @Override public int hashCode(){ return id; } // ids are sequential, nice as hash code
        // default Object.equals()

        @Override public InetAddress getRemoteIp(){ return socketChannel.socket().getInetAddress(); }


        @Override public int read(ByteBuffer bb) throws Exception
        {
            return socketChannel.read(bb);
        }
        @Override public Async<Void> awaitReadable(boolean accepting)
        {
            SelectorFlow.AwaitReadableEvent event = selectorFlow.new AwaitReadableEvent(this, accepting);
            selectorFlow.addEvent(event);
            return event.promise;
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
            SelectorFlow.AwaitWritableEvent event = selectorFlow.new AwaitWritableEvent(this);
            selectorFlow.addEvent(event);
            return event.promise;
        }

        @Override
        public Executor getExecutor()
        {
            return selectorFlow;
        }

        @Override public void close() // no throw
        {
            selectorFlow.addEvent(selectorFlow.new CloseChannEvent(this));
        }

        // following fields are accessed only by selector flow

        boolean closed;
        SelectionKey selectionKey; // initially null; register only when necessary
        int interestOps;

        boolean accepting;
        Promise<Void> readablePromise;

        Promise<Void> writablePromise;

    }

    static class SelectorFlow extends Thread implements Executor, _WithThreadLocalFiber
    {
        TcpServer server;
        Selector selector;
        ServerSocketChannel serverSocketChannel;
        SelectionKey acceptSK;

        volatile int nConnections_volatile;
        final int maxConnHi, maxConnLo;
        int idSeq;
        HashSet<ChannImpl> allChann = new HashSet<>();

        ArrayList<ChannImpl> interestUpdateList = new ArrayList<>();

        enum SelState{ accepting, not_accepting, killed}
        SelState state;

        // remote events from other threads. sync on `remoteEvents`
        final ArrayDeque<Runnable> remoteEvents = new ArrayDeque<>(); // also as the lock
        volatile boolean remoteEventFlag_volatile;
        boolean blockingOnSelect =true;
        boolean selectorThreadKilled;

        final ArrayDeque<Runnable> localEvents = new ArrayDeque<>();

        Object threadLocalFiber;

        SelectorFlow(int id, TcpServer server, ServerSocketChannel serverSocketChannel,
                     int maxConn) throws Exception
        {
            super("bayou TCP server selector thread #" + id);

            this.server = server;

            this.maxConnHi = maxConn; // >=1
            this.maxConnLo = Math.max( maxConn-10, maxConn-maxConn/100 ); // 1<=lo<=hi

            selector = Selector.open();

            this.serverSocketChannel = serverSocketChannel;
            this.acceptSK = serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        }

        @Override // Executor
        public void execute(Runnable asyncTask)
        {
            Objects.requireNonNull(asyncTask);
            addEvent(asyncTask);  // can be called on any thread

            // if killed, task is diverted to the single thread orphanFlow.
            // not big deal for tasks after kill.
        }

        @Override // _WithThreadLocalFiber
        public Object getThreadLocalFiber()
        {
            return threadLocalFiber;
        }
        @Override // _WithThreadLocalFiber
        public void setThreadLocalFiber(Object obj)
        {
            threadLocalFiber = obj;
        }

        void addEvent(Runnable event)
        {
            if(Thread.currentThread()==this)
            {
                localEvents.addLast(event);
                return;
            }

            // addEvent() called from another thread. hopefully this is not common.
            int x = 0;
            synchronized (remoteEvents)
            {
                if(selectorThreadKilled) // orphan event, to be processed on the orphan flow
                {
                    x = 1; // orphanFlow.execute(event);
                }
                else
                {
                    remoteEvents.addLast(event);
                    remoteEventFlag_volatile = true;

                    if(blockingOnSelect)
                        x=2; // selector.wakeup();
                }
            }
            if(x==1)
                orphanFlow.execute(event);
            else if(x==2)
                selector.wakeup();
        }

        void moveRemoteEventsToLocal()
        {
            assert Thread.holdsLock(remoteEvents);

            localEvents.addAll(remoteEvents);
            remoteEvents.clear();
            remoteEventFlag_volatile = false;
        }


        @Override
        public void run()
        {
            state = SelState.accepting;

            server.phaser.arriveAndAwaitAdvance(); // happens-before any addEvent()

            try
            {
                while( run1() ) continue;
            }
            catch(RuntimeException|Error t) // unrecoverable; `this` is corrupt.
            {
                logUnexpected(t); // extra log
                throw t;
                // residue events and future events won't be run
            }
        }

        // events
        //     channel events from select()
        //         acceptable, readable, writable
        //     channel ops from app
        //         AwaitR, AwaitW, close
        //         start/stopAccept, kill
        //     tasks from app
        // when processing an event, it may raise more events, which are put in local event queue.
        // app may raise events from other threads, these are remote events, that need to enter this thread.

        boolean run1()
        {
            int selectR;
            try
            {
                if(blockingOnSelect) // can read it without sync{}, cause only this thread writes to it.
                {
                    selectR = selector.select();     // await channel events

                    synchronized (remoteEvents)
                    {   blockingOnSelect = false;   }
                }
                else
                {
                    selectR = selector.selectNow(); //  poll channel events
                }
            }
            catch (Exception t) // fatal, can't handle
            {
                throw new RuntimeException(t);
            }

            // channel events ==============================================================================
            // process acceptable/readable/writable events. may generate local events
            if(selectR>0)
            {
                for(SelectionKey sk : selector.selectedKeys())
                {
                    if(!sk.isValid()) // impossible; no other threads mess with the key.
                    {
                        logUnexpected(new AssertionError("sk not valid"));
                        continue; // the problem is probably not crippling
                    }

                    if(sk.isAcceptable())
                    {
                        onAcceptable();
                    }
                    else
                    {
                        ChannImpl chann = (ChannImpl)sk.attachment();
                        if(sk.isReadable())
                        {
                            onReadable(chann, null);
                        }
                        if(sk.isWritable())
                        {
                            onWritable(chann, null);
                        }
                    }
                }
                selector.selectedKeys().clear();
            }

            // local event loop =========================================================================

            long loopEndTime = System.nanoTime() + 100_000;
            // local event loop may be dominated by some channels (by successively adding new events)
            // so that we don't have a chance to check channel events, or update channel interests.
            // so we'll run loop only for a finite time, then exit to take care of the two concerns.
            int iLoop=0;

            while(true)
            {
                if(remoteEventFlag_volatile)  /*0*/
                {
                    synchronized (remoteEvents)
                    {
                        moveRemoteEventsToLocal();
                    }
                }

                Runnable event = localEvents.pollFirst();
                if(event==null)
                {
                    synchronized (remoteEvents)
                    {
                        if(!remoteEventFlag_volatile) // local & remove events are depleted. end this event loop
                        {
                            if(state==SelState.killed) // there was a KillEvent. rare.
                            {
                                // kill this selector thread. no more channel events. no more local events.
                                selectorThreadKilled = true; // future remote events go to the orphan thread
                                return false;  // exit run()
                            }
                            else // awaiting channel events, block on select()
                            {
                                blockingOnSelect = true;
                                break;
                            }
                        }
                        else // remote events. should be rare, coz we just checked the flag at /*0*/
                        {
                            moveRemoteEventsToLocal();
                            // goto /*1*/, then goto /*2*/
                        }
                    }
                    /*1*/
                    event = localEvents.pollFirst(); // non null
                }

                /*2*/
                try
                {
                    event.run(); // may add more local events
                }
                catch (RuntimeException e) // in case user code throws unexpectedly
                {
                    logUnexpected(e);
                    // one chann flow may be corrupted; but keep the selector going
                }

                // nanoTime() can be expensive (600ns on win7 !?). do it occasionally.
                if( ( ++iLoop & ((1<<8)-1) ) !=0 )  // check time once per 2^8
                    continue;

                if(loopEndTime-System.nanoTime()<0) // not `loopEndTime<nanoTime()`, see nanoTime() javadoc
                {
                    synchronized (remoteEvents)
                    {
                        if(remoteEventFlag_volatile)
                            moveRemoteEventsToLocal();

                        blockingOnSelect = localEvents.isEmpty();
                        // usually false, meaning we'll poll channel events by selectNow().
                        // it'll be coincidental that loopEndTime is passed && events are depleted.
                    }
                    break;
                }

            } // while(true)

            // simple/fast events: CloseChann, AwaitReadable, AwaitWritable.
            // PauseAccepting, Kill can be slow. ResumeAccepting is fast.


            // interest ops =========================================================================

            // a chann may be added in the list multiple times for opposite purposes. example case:
            // a chann becomes readable - we turn off read interest - awaitReadable() turns it back on.
            // here we check accumulated effect, so we may avoid unnecessary interest updates.
            for(ChannImpl chann : interestUpdateList)
                mayUpdateInterest(chann);
            interestUpdateList.clear();

            if(state==SelState.accepting) // turn on/off OP_ACCEPT near maxConn
            {
                int nConn = nConnections_volatile;
                int acceptInterest = acceptSK.interestOps();
                if(nConn >= maxConnHi && acceptInterest!=0)
                    acceptSK.interestOps(0);
                else if(nConn < maxConnLo && acceptInterest==0)
                    acceptSK.interestOps(SelectionKey.OP_ACCEPT);
            }

            return true; // repeat, goto select()/selectNow()
        }

        void onAcceptable()
        {
            int nConn = nConnections_volatile;
            while(true) // may accept multiple connections
            {
                if(nConn>=maxConnHi)
                    break;

                SocketChannel socketChannel;
                try
                {   socketChannel = serverSocketChannel.accept();   }
                catch(Exception e) // fatal, can't handle
                {   throw new RuntimeException(e);   }

                if(socketChannel==null) // no more. also possible another selector flow accepted before us.
                    break;

                InetAddress ip = socketChannel.socket().getInetAddress();
                if(!server.ip2Channs_tryInc(ip)) // too many connections from the ip
                {
                    closeNoThrow(socketChannel);
                    continue;
                }

                try
                {
                    server.confSocket(socketChannel);
                    socketChannel.configureBlocking(false); // do it after user code in confSocket()
                }
                catch(Exception e)
                {
                    logUnexpected(e);
                    closeNoThrow(socketChannel);
                    break; // another accept() probably will fail too
                }

                ChannImpl chann = new ChannImpl(socketChannel, this, idSeq++);
                allChann.add(chann);
                nConn++;
                nConnections_volatile = nConn;

                try
                {   server.onAccept(chann);   } // user code, must not throw
                catch(Exception t)
                {   logUnexpected(t);   } // probably not crippling
            }
        }

        void closeNoThrow(SocketChannel socketChannel)
        {
            try
            {   socketChannel.close();   }
            catch(Exception e)
            {   logUnexpected(e);   }
        }

        class PauseAcceptingEvent implements Runnable
        {
            @Override public void run()
            {
                assert state==SelState.accepting;

                acceptSK.cancel();
                acceptSK=null;
                serverSocketChannel=null; // not up to me to close it

                // for every `accepting` chann, call back with error
                // brute un-indexed iteration. ok to be slow for this event
                for(ChannImpl chann : allChann)
                {
                    if(chann.accepting) // => it must be awaiting readable
                    {
                        onReadable(chann, new IOException("server stops accepting new connections"));
                        // most likely callback will raise a close event
                    }
                }

                state = SelState.not_accepting;

                server.phaser.arrive();
                // afterwards, event issuer may choose to close server socket.
                //
                // also it's guaranteed that no more new chann will be created,
                // and no more successful readable of "accepting" nature
                // (so for http, no more new http requests)
                // event issuer may rely on this guarantee for its state management.
            }
        }

        class ResumeAcceptingEvent implements Runnable
        {
            ServerSocketChannel serverSocketChannel;
            ResumeAcceptingEvent(ServerSocketChannel serverSocketChannel)
            {
                this.serverSocketChannel = serverSocketChannel;
            }

            @Override public void run()
            {
                assert state==SelState.not_accepting;

                SelectionKey acceptSK;
                try
                {   acceptSK = serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);   }
                catch(ClosedChannelException e)
                {   throw new AssertionError(e); } // fatal, can't handle

                SelectorFlow.this.serverSocketChannel = serverSocketChannel;
                SelectorFlow.this.acceptSK = acceptSK;

                state = SelState.accepting;
            }
        }

        class KillEvent implements Runnable
        {
            @Override public void run()
            {
                assert state==SelState.not_accepting;

                // close all connections.
                for(ChannImpl chann : allChann)
                {
                    assert !chann.closed;
                    doClose(chann);
                    // that may invoke r/w callback with error, which may add more (close) events.
                    // those events are queued and processed by event loop later. (not orphan)
                }
                allChann=null;

                interestUpdateList=null;

                try
                {   selector.close();   }
                catch(Exception e)
                {   logUnexpected(e);   }
                selector = null;

                Phaser phaser = server.phaser; // need it later
                server = null;

                state=SelState.killed;

                // we cleared stuff that no future events should need.
                // note `events` list may still contain events. these events are post-kill, but not orphan.
                // event loop will handle these events. they can only be Close, AwaitR, AwaitW, events.
                // they must check kill state (or check chann.closed - all channels are closed after kill event)
                // afterwards `events` list becomes empty, events after that are orphans.

                phaser.arrive();
                // event issuer now knows that all chann closed; no read/write/awaitRW/yield() works.
            }
        }

        class AwaitReadableEvent implements Runnable
        {
            ChannImpl chann;
            Promise<Void> promise;
            boolean accepting;
            AwaitReadableEvent(ChannImpl _chann, boolean accepting)
            {
                this.chann = _chann;
                this.accepting = accepting;
                this.promise = new Promise<>();
                promise.onCancel(reason ->
                    addEvent(new CancelAwaitReadableEvent(chann, promise, reason)));
            }
            @Override  public void run()
            {
                if(chann.readablePromise!=null) // programming error
                {
                    promise.fail(new IllegalStateException("already awaiting readable"));
                }
                else if(chann.closed) // likely closed by non-read flow; or server is killed
                {
                    promise.fail(new AsynchronousCloseException());
                }
                else if(accepting && state==SelState.not_accepting)
                {
                    promise.fail(new IOException("server is not accepting"));
                }
                else
                {
                    chann.readablePromise = promise;
                    chann.accepting = accepting;
                    interestUpdateList.add(chann); // to turn on read interest
                }
            }
        }

        class CancelAwaitReadableEvent implements Runnable
        {
            ChannImpl chann;
            Promise<Void> promise;
            Exception reason;
            CancelAwaitReadableEvent(ChannImpl chann, Promise<Void> promise, Exception reason)
            {
                this.chann = chann;
                this.promise = promise;
                this.reason = reason;
            }
            @Override public void run()
            {
                // cancel can come arbitrarily late; check to see if it's still relevant
                if(this.promise==chann.readablePromise)
                    onReadable(chann, reason);
            }
        }

        class AwaitWritableEvent implements Runnable
        {
            ChannImpl chann;
            Promise<Void> promise;
            AwaitWritableEvent(ChannImpl _chann)
            {
                chann = _chann;
                promise = new Promise<>();
                promise.onCancel(reason ->
                    addEvent(new CancelAwaitWritableEvent(chann, promise, reason)));
            }
            @Override  public void run()
            {
                if(chann.writablePromise!=null) // programming error
                {
                    promise.fail(new IllegalStateException("already awaiting writable"));
                }
                else if(chann.closed) // likely closed by non-write flow; or server is killed
                {
                    promise.fail(new AsynchronousCloseException());
                }
                else
                {
                    chann.writablePromise = promise;
                    interestUpdateList.add(chann); // to turn on write interest
                }
            }
        }

        class CancelAwaitWritableEvent implements Runnable
        {
            ChannImpl chann;
            Promise<Void> promise;
            Exception reason;
            CancelAwaitWritableEvent(ChannImpl chann, Promise<Void> promise, Exception reason)
            {
                this.chann = chann;
                this.promise = promise;
                this.reason = reason;
            }
            @Override public void run()
            {
                // cancel can come arbitrarily late; check to see if it's still relevant
                if(this.promise==chann.writablePromise)
                    onWritable(chann, reason);
            }
        }


        class CloseChannEvent implements Runnable
        {
            ChannImpl chann;
            CloseChannEvent(ChannImpl chann)
            {
                this.chann = chann;
            }
            @Override public void run()
            {
                // not unusually to try to close a chann multiple times
                if(chann.closed)
                    return;
                // note, if state==killed/eventLoopGone, chann.close==true

                doClose(chann);
                allChann.remove(chann);
            }
        }


        void doClose(ChannImpl chann) // no throw
        {
            assert !chann.closed;

            closeNoThrow(chann.socketChannel); // will cancel selection key if any
            chann.selectionKey=null; // possibly was null (never registered)
            chann.interestOps=0;

            // if awaiting r/w-able, call back with exception.
            if(chann.readablePromise !=null)
            {
                onReadable(chann, new AsynchronousCloseException());
                // callback may add another close event
            }
            if(chann.writablePromise !=null)
            {
                onWritable(chann, new AsynchronousCloseException());
                // callback may add another close event
            }

            // keep chann 's reference to socketChannel and selectorFlow
            chann.closed=true;
            //allChann.remove(chann);

            nConnections_volatile--;
            server.ip2Channs_dec(chann.getRemoteIp());

            // no need to remove chann from interestUpdateList. mayUpdateInterest() checks chann.closed.
        }

        void onReadable(ChannImpl chann, Exception error) // no throw
        {
            Promise<Void> promise = chann.readablePromise;
            chann.readablePromise = null;
            interestUpdateList.add(chann); // to turn off read interest

            chann.accepting = false; // clean other read state

            if(error==null)
                promise.succeed(null);
            else
                promise.fail(error);
        }
        void onWritable(ChannImpl chann, Exception error) // no throw
        {
            Promise<Void> promise = chann.writablePromise;
            chann.writablePromise = null;
            interestUpdateList.add(chann); // to turn off write interest

            if(error==null)
                promise.succeed(null);
            else
                promise.fail(error);
        }

        void mayUpdateInterest(ChannImpl chann)
        {
            if(chann.closed)
                return;

            int newOps = 0;
            if(chann.readablePromise!=null)
                newOps |= SelectionKey.OP_READ;
            if(chann.writablePromise!=null)
                newOps |= SelectionKey.OP_WRITE;

            if(newOps==chann.interestOps)
                return;

            // else update interestOps on selection key
            chann.interestOps=newOps;
            if(chann.selectionKey!=null)
            {
                chann.selectionKey.interestOps(newOps); // this is not cheap; that's why we try to avoid it
            }
            else // not registered yet
            {
                try
                {
                    chann.selectionKey=chann.socketChannel.register(selector, newOps, chann); // expensive
                }
                catch (ClosedChannelException e) // impossible
                {    throw new AssertionError(e);   }
            }
        }


    } // class SelectorFlow

    static final _Logger logger = _Logger.of(TcpServer.class);

    static void logUnexpected(Throwable t) // not supposed to happen, should print by default
    {
        logger.error("Unexpected error: %s", t);
    }
    static void logErrorOrDebug(Throwable error)  // depends on if exception is checked
    {
        _Util.logErrorOrDebug(logger, error);
    }

    // tasks are serialized
    // 1 flow for all (even multiple servers), since tasks are small, quick, non-blocking.
    //   (orphan events can only be Close, AwaitR/W/Turn, and chann is closed when an orphan event is processed)
    static final ThreadPoolExecutor orphanFlow = _Exec.newSerialExecutor("NbTcpServer.orphanFlow");
    // the thread is useless most of time. fortunately it expires quickly (after 10 sec)


}