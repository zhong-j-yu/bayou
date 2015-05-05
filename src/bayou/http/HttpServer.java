package bayou.http;

import _bayou._log._Logger;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.ssl.SslChannel2Connection;
import bayou.tcp.TcpChannel;
import bayou.tcp.TcpChannel2Connection;
import bayou.tcp.TcpConnection;
import bayou.tcp.TcpServer;
import bayou.util.Result;

import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Http server.
 * <p>
 *     Each server has an {@link HttpHandler} which creates a response for every request.
 * </p>
 * <p>
 *     Configuration can be done on the {@link HttpServerConf} before server start, for example
 * </p>
 * <pre>
 *     HttpServer server = new HttpServer(handler);
 *     server.conf()
 *         .port( 8080 )
 *         .trafficDump( System.out::print )
 *         ;
 *     server.start();
 * </pre>
 *
 *
 * <h4 id=life-cycle>Life Cycle</h4>
 * <p>
 *     The server can be in 4 states: <code>init, accepting, acceptingPaused, acceptingStopped.</code>
 * </p>
 * <p>
 *     After {@link #start()}, the server is in <code>accepting</code> state.
 *     New requests are accepted.
 * </p>
 * <p>
 *     If {@link #pauseAccepting()} is called, the server is in <code>acceptingPaused</code> state.
 *     New requests are not accepted.
 *     This is useful to protect the server when some resources are about to be exhausted.
 *     The server socket ports are still owned by this server.
 *     The server can be resumed to <code>accepting</code> state by {@link #resumeAccepting()}.
 * </p>
 * <p>
 *     After {@link #stopAccepting()}, the server is in <code>acceptingStopped</code> state.
 *     New requests are not accepted.
 *     The server socket ports are freed and can be grabbed by another server.
 *     Existing requests are still being processed.
 *     Their connections will be closed after their responses are written.
 *     Check the number of live connections by {@link #getConnectionCount()}.
 * </p>
 * <p>
 *     The {@link #stopAll()} call will stop accepting new requests and abort outstanding requests.
 *     <!-- The server is then back to the <code>init</code> state. (do not advertise this) -->
 * </p>
 * <p>
 *     You can use {@link #stop(java.time.Duration) stop(graceTimeout)} to gracefully stop the server.
 * </p>
 *
 */

// to ping self after start()
//     new URL("http://localhost:8080/").openConnection().getInputStream().close();

public class HttpServer
{
    final HttpHandler handler;
    // handler must not be null. if http not supported, e.g. pure ws server, install a dummy handler: req->404;

    final TcpServer tcpServer;

    final HttpServerConf conf;

    ImplTunneller tunneller;

    /**
     * Create an HttpServer.
     * <p>
     *     Equivalent to
     *     {@link #HttpServer(HttpServerConf, HttpHandler) new HttpServer( new HttpServerConf(), handler )}.
     * </p>
     */
    public HttpServer(HttpHandler handler)
    {
        this( new HttpServerConf(), handler );
    }

    /**
     * Create an HttpServer.
     * <p>
     *     After the constructor call, the `conf` object can be accessed through {@link #conf()},
     *     and it can be modified as long as the server is not started.
     * </p>
     */
    public HttpServer(HttpServerConf conf, HttpHandler handler)
    {
        _Util.require(handler!=null, "handler!=null");
        this.handler = handler;

        this.conf = conf;

        this.tcpServer = new TcpServer(conf.tcpConf);
        // app can modify tcp_conf before TcpServer start.
    }

    void onConnect(TcpConnection tcpConn)
    {
        new ImplConn(this, tcpConn);
    }


    /**
     * Get the {@link HttpServerConf} of this server.
     * <p>
     *     Changes to configuration must be done before the server is started.
     * </p>
     */
    public HttpServerConf conf()
    {
        return conf;
    }



    HashMap<String, HttpUpgrader> upgraderMap = new HashMap<>();  // keys are in lower case

    /**
     * Add an {@link HttpUpgrader} for the protocol.
     * <p>
     *     This method must be called before the server is started.
     * </p>
     */
    public void addUpgrader(String protocol, HttpUpgrader upgrader)
    {
        conf.assertCanChange();  // must be before server starts
        protocol = protocol.toLowerCase();
        upgraderMap.put(protocol, upgrader);
    }
    HttpUpgrader findUpgrader(String hvUpgrade)
    {
        hvUpgrade = hvUpgrade.toLowerCase();
        if(hvUpgrade.indexOf(',')==-1) // usually
            return upgraderMap.get(hvUpgrade);

        for(String protocol : hvUpgrade.split(","))
        {
            HttpUpgrader upgrader = upgraderMap.get(protocol.trim());
            if(upgrader!=null)
                return upgrader;
        }
        return null;
    }


    /**
     * Start the server. See <a href="#life-cycle">Life Cycle</a>.
     */
    public void start() throws Exception
    {
        conf.freeze(); // throws

        initHotHandler();

        if(conf.supportedMethods.containsValue("CONNECT"))
            tunneller = new ImplTunneller(this);

        for(HttpUpgrader upgrader : upgraderMap.values())
            upgrader.init(conf); // throws

        initTcpHandlers();
        tcpServer.start();  // throws
        printStartupMessage();
    }

    void initTcpHandlers() throws Exception
    {
        TcpServer.Conf tcpConf = conf.tcpConf;

        TcpChannel2Connection toPlain=null;
        if(!conf.plainPorts.isEmpty())
        {
            toPlain
                = new TcpChannel2Connection(conf.readBufferSize, conf.writeBufferSize);
            Consumer<TcpChannel> handlerPlain = channHandler(toPlain, null);
            for(Integer plainPort : conf.plainPorts)
            {
                InetSocketAddress address = new InetSocketAddress(conf.ip, plainPort.intValue());
                tcpConf.handlers.put(address, handlerPlain);
            }
        }

        if(!conf.sslPorts.isEmpty())
        {
            SslChannel2Connection toSsl =
                new SslChannel2Connection(false, conf.sslContext, conf.sslEngineConf);
            Consumer<TcpChannel> handlerSsl=null;
            Consumer<TcpChannel> handlerMixed=null;
            for(Integer sslPort : conf.sslPorts)
            {
                InetSocketAddress address = new InetSocketAddress(conf.ip, sslPort.intValue());
                if(!tcpConf.handlers.containsKey(address))
                {
                    if(handlerSsl==null)
                        handlerSsl = channHandler(null, toSsl);
                    tcpConf.handlers.put(address, handlerSsl);
                }
                else // same port for plain/ssl
                {
                    assert toPlain!=null;
                    if(handlerMixed==null)
                        handlerMixed = channHandler(toPlain, toSsl);
                    tcpConf.handlers.put(address, handlerMixed);
                }
            }
        }

        if(tcpConf.handlers.isEmpty())
            throw new Exception("no server ports are specified");
    }

    Consumer<TcpChannel> channHandler(TcpChannel2Connection toPlain, SslChannel2Connection toSsl)
    {
        if(toSsl==null) // plain only
            return chann->
            {
                TcpConnection conn = toPlain.convert(chann);
                onConnect(conn);
            };

        Consumer<Result<TcpConnection>> onConnResult = result ->
        {
            TcpConnection tcpConn = result.getValue();
            if (tcpConn != null)
            {
                onConnect(tcpConn);
            }
            else
            {
                Exception ex = result.getException();
                assert ex != null;
                logErrorOrDebug(ex); // e.g. ssl handshake error
            }
        };

        return chann->
        {
            Async<TcpConnection> asyncConn;
            if(toPlain==null)
                asyncConn = toSsl.convert(chann).covary();
            else
                asyncConn = toSsl.convert(chann, toPlain);

            asyncConn = asyncConn.timeout(conf.sslHandshakeTimeout);

            Result<TcpConnection> result = asyncConn.pollResult();
            if(result!=null) // often the case for plain conn
                onConnResult.accept(result);
            else
                asyncConn.onCompletion(onConnResult);
        };
    }

    void initHotHandler()
    {
        if(handler instanceof HotHttpHandler)
        {
            HotHttpHandler hot = (HotHttpHandler)handler;
            try
            {
                hot.reloader().getAppInstance(hot.getAppHandlerClassName()); // compile java files, etc.
            }
            catch (Exception e) // not crippling; can be recovered
            {
                _Util.printStackTrace(e, hot.reloader().getMessageOut());
            }
        }
    }

    /**
     * Get server sockets.
     * <p>
     *     Returns an empty set if the server is not started, or has been stopped.
     * </p>
     */
    public Set<ServerSocketChannel> getServerSockets()
    {
        return tcpServer.getServerSockets();
    }

    /**
     * Get the number of connections.
     */
    public int getConnectionCount()
    {
        return tcpServer.getConnectionCount();
    }

    /**
     * Pausing accepting new requests. See <a href="#life-cycle">Life Cycle</a>.
     */
    public void pauseAccepting()
    {
        tcpServer.pauseAccepting();
    }

    /**
     * Resume accepting new requests. See <a href="#life-cycle">Life Cycle</a>.
     */
    public void resumeAccepting()
    {
        tcpServer.resumeAccepting();
    }

    /**
     * Stop accepting new requests. See <a href="#life-cycle">Life Cycle</a>.
     */
    public void stopAccepting()
    {
        tcpServer.stopAccepting();
        // message?
    }

    /**
     * Stop accepting new requests and abort outstanding requests. See <a href="#life-cycle">Life Cycle</a>.
     */
    public void stopAll()
    {
        tcpServer.stopAll();
        // message?

        if(tunneller !=null)
            tunneller.close();
    }

    /**
     * Gracefully stop the server.
     * <ol>
     *     <li>
     *         Call {@link #stopAccepting()} to stop accepting new requests.
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
        tcpServer.stop(graceTimeout);
        // message?

        if(tunneller !=null)
            tunneller.close();
    }

    void printStartupMessage()
    {
        ArrayList<String> protocols = new ArrayList<>();
        protocols.add("http");
        protocols.addAll(upgraderMap.keySet());

        String ip = conf.ip.getHostAddress(); // numeric ip, no other stuff. // e.g. 0:0:0:0:0:0:0:0

        System.out.printf("%nBayou HttpServer, protocols=%s, IP=%s %n", protocols, ip);

        TreeSet<Integer> ports = new TreeSet<>();
        for(ServerSocketChannel chann : tcpServer.getServerSockets())
        {
            Integer port = chann.socket().getLocalPort();
            ports.add(port);
        }
        for(Integer port : ports)
        {
            String type = portType(port);
            if(type==null) // auto-port from 0
                type = portType(0);
            assert type!=null;
            System.out.printf(" port %s\t-  %s %n", port, type);
        }

        System.out.printf("Started on %s %n%n", new Date());
    }
    String portType(Integer port)
    {
        boolean plain = conf.plainPorts.contains(port);
        boolean ssl = conf.sslPorts.contains(port);
        if(ssl&&plain)
            return "SSL + plain";
        else if(plain)
            return "plain";
        else if(ssl)
            return "SSL";
        else
            return null;
    }

    static final _Logger logger = _Logger.of(HttpServer.class);

    static void logUnexpected(Throwable t)
    {
        logger.error("Unexpected error: %s", t);
    }
    static void logErrorOrDebug(Throwable error)  // depends on if exception is checked
    {
        _Util.logErrorOrDebug(logger, error);
    }

}
