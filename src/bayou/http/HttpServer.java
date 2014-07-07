package bayou.http;

import _bayou._log._Logger;
import _bayou._tmp._Util;
import bayou.tcp.TcpConnection;
import bayou.tcp.TcpServerX;

import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

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
 *         .ip( 8080 )
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
 *     The server socket port is still owned by this server.
 *     The server can be resumed to <code>accepting</code> state by {@link #resumeAccepting()}.
 * </p>
 * <p>
 *     After {@link #stopAccepting()}, the server is in <code>acceptingStopped</code> state.
 *     New requests are not accepted.
 *     The server socket port is freed and can be grabbed by another server.
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

// http version:
//   request:  Http/1.1 or 1.0
//   response: http version same as request

// to ping self after start()
//     new URL("http://localhost:8080/").openConnection().getInputStream().close();

public class HttpServer
{
    final HttpHandler handler;
    // handler must not be null. if http not supported, e.g. pure ws server, install a dummy handler: req->404;

    /**
     * Create an HttpServer.
     */
    public HttpServer(HttpHandler handler)
    {
        _Util.require(handler!=null, "handler!=null");
        this.handler = handler;
    }

    class TcpServer extends TcpServerX
    {
        ServerSocketChannel getServerSocketChannel()
        {
            return super.serverSocketChannel;
        }

        @Override
        protected void confServerSocket(ServerSocketChannel serverSocketChannel) throws Exception
        {
            // don't call super
            conf.onServerSocket.accept(serverSocketChannel);
        }

        @Override
        protected void confSocket(SocketChannel socketChannel) throws Exception
        {
            // don't call super
            conf.onSocket.accept(socketChannel);
        }

        @Override
        protected void onConnect(TcpConnection nbConn)
        {
            new ImplConn(HttpServer.this, nbConn);
        }
    }
    final TcpServer tcpServer = new TcpServer();


    final HttpServerConf conf = new HttpServerConf(tcpServer);

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

        for(HttpUpgrader upgrader : upgraderMap.values())
            upgrader.init(conf); // throws

        tcpServer.start();  // throws
        printStartupMessage(tcpServer.getServerSocketChannel());
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
     * Get the number of connections.
     */
    public int getConnectionCount()
    {
        return tcpServer.getConnectionCount();
    }

    /**
     * Pausing accepting new requests. See <a href="#life-cycle">Life Cycle</a>.
     */
    public void pauseAccepting() throws Exception
    {
        tcpServer.pauseAccepting();
    }

    /**
     * Resume accepting new requests. See <a href="#life-cycle">Life Cycle</a>.
     */
    public void resumeAccepting() throws Exception
    {
        tcpServer.resumeAccepting();
    }

    /**
     * Stop accepting new requests. See <a href="#life-cycle">Life Cycle</a>.
     */
    public void stopAccepting() throws Exception
    {
        tcpServer.stopAccepting();
        // message?
    }

    /**
     * Stop accepting new requests and abort outstanding requests. See <a href="#life-cycle">Life Cycle</a>.
     */
    public void stopAll() throws Exception
    {
        tcpServer.stopAll();
        // message?
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
    public void stop(Duration graceTimeout) throws Exception
    {
        tcpServer.stop(graceTimeout);
        // message?
    }

    void printStartupMessage(ServerSocketChannel serverSocketChannel)
    {
        // confServerSocketIp can be null, then InetAddress.anyLocalAddress() is used.
        // unfortunately we can't call that method. get it from server socket
        InetSocketAddress ip_port = (InetSocketAddress)serverSocketChannel.socket().getLocalSocketAddress();
        String ip = ip_port.getAddress().getHostAddress(); // numeric ip, no other stuff. // e.g. 0:0:0:0:0:0:0:0
        int port = ip_port.getPort();

        String ssl;
        if(!conf.get_plainEnabled())
            ssl = "SSL only";  // plain disabled
        else if(conf.get_sslEnabled())
            ssl = "SSL+plain";
        else
            ssl = "no SSL"; // plain only

        ArrayList<String> protocols = new ArrayList<>();
        protocols.add("http");
        protocols.addAll(upgraderMap.keySet());

        System.out.printf("%nBayou HttpServer%n  port=%d, IP=%s, protocols=%s, %s %nStarted %s %n%n",
            port, ip , protocols, ssl, new Date());
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
