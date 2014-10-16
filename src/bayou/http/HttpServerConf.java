package bayou.http;

import _bayou._tmp._Tcp;
import _bayou._tmp._TrafficDumpWrapper;
import _bayou._tmp._ChArr;
import _bayou._tmp._Util;
import bayou.bytes.RangedByteSource;
import bayou.ssl.SslConf;
import bayou.tcp.TcpServer;
import bayou.util.function.ConsumerX;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.net.*;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

import static _bayou._tmp._Util.require;

/**
 * Configuration for HttpServer.
 * <p>
 *     Each config property can be set by the setter method, for example
 * </p>
 * <pre>
 *     httpServer.conf().port( 8080 );
 * </pre>
 * <p>
 *     The setters return `this` for method chaining, for example
 * </p>
 * <pre>
 *     httpServer.conf()
 *         .port( 8080 )
 *         .trafficDump( System.out::print )
 *         ;
 * </pre>
 * <p>
 *     The config properties must be set before server start.
 * </p>
 * <h4>
 *     Categories of Config Properties
 * </h4>
 * <dl>
 *     <dt>TCP</dt>
 *     <dd>
 *         {@link #ip(java.net.InetAddress) ip} ,
 *         {@link #port(int...) port} ,
 *         {@link #maxConnections(int) maxConnections} ,
 *         {@link #maxConnectionsPerIp(int) maxConnectionsPerIp}
 *     </dd>
 *     <dt>SSL</dt>
 *     <dd>
 *         {@link #sslPort(int...) sslPort} ,
 *         {@link #sslContext(javax.net.ssl.SSLContext) sslContext} ,
 *         {@link #sslEngineConf(bayou.util.function.ConsumerX) sslEngineConf}
 *     </dd>
 *     <dt>HTTP</dt>
 *     <dd>
 *         {@link #supportedMethods(String...) supportedMethods} ,
 *         {@link #xForwardLevel(int) xForwardLevel},
 *         {@link #autoGzip(boolean) autoGzip} ,
 *         {@link #autoConditional(boolean) autoConditional} ,
 *         {@link #autoRange(boolean) autoRange} ,
 *         {@link #autoCacheControl(boolean) autoCacheControl}
 *     </dd>
 *     <dt>Logging</dt>
 *     <dd>
 *         {@link #accessLogger(java.util.function.Consumer) accessLogger} ,
 *         {@link #trafficDump(java.util.function.Consumer) trafficDump}
 *     </dd>
 *     <dt>Throughput</dt>
 *     <dd>
 *         {@link #readMinThroughput(long) readMinThroughput} ,
 *         {@link #writeMinThroughput(long) writeMinThroughput}
 *     </dd>
 *     <dt>Timeout</dt>
 *     <dd>
 *         {@link #keepAliveTimeout(java.time.Duration) keepAliveTimeout} , etc.
 *     </dd>
 *     <dt>Others</dt>
 *     <dd>
 *         misc properties, not commonly used
 *     </dd>
 * </dl>
 */

@SuppressWarnings("UnusedDeclaration")
public class HttpServerConf
{
    final TcpServer.Conf tcpConf = new TcpServer.Conf();

    // for TcpChannel2Connection. not public yet; apps probably don't care
    int readBufferSize = 16*1024;
    int writeBufferSize = 16*1024;


    /**
     * Create an HttpServerConf with default values.
     */
    public HttpServerConf()
    {
    }

    boolean frozen;
    void freeze() throws Exception
    {
        frozen = true;

        // may do extra validations here

        // calculate derived fields

        accessLoggerWrapper = _accessLogger==null ? null : new HttpAccessLoggerWrapper(_accessLogger);

        trafficDumpWrapper = _trafficDump ==null? null : new _TrafficDumpWrapper("Http", _trafficDump);
    }
    void assertCanChange()
    {
        if(frozen)
            throw new IllegalStateException("cannot change conf after server start");
    }









    // conf NbTcpServer ............................................................................

    InetAddress ip = new InetSocketAddress(2000).getAddress(); // wildcard address

    /**
     * IP address the server socket binds to.
     * <p><code>
     *     default: the wildcard address
     * </code></p>
     * <p>
     *     See also {@link #ip(String)}.
     * </p>
     * @return `this`
     */
    public HttpServerConf ip(InetAddress ip)
    {
        assertCanChange();
        require(ip != null, "ip!=null");
        this.ip = ip;
        return this;
    }

    /**
     * IP address the server socket binds to.
     * <p>
     *     This method is equivalent to <code>ip(InetAddress.getByName(ip))</code>,
     *     see {@link #ip(InetAddress)}.
     * </p>
     * @return `this`
     */
    public HttpServerConf ip(String ip)
    {
        try
        {
            return ip(InetAddress.getByName(ip));
        }
        catch (UnknownHostException e)
        {
            throw new IllegalArgumentException("ip="+ip, e);
        }
    }

    HashSet<Integer> plainPorts = new HashSet<Integer>();
    {   plainPorts.add(8080);   }

    /**
     * Ports for plain connections.
     * <p><code>
     *     default: {8080}
     * </code></p>
     * <p>
     *     Port 0 means an automatically allocated port.
     * </p>
     * <p>
     *     See {@link #sslPort(int...)} for SSL ports.
     * </p>
     * @return `this`
     */
    public HttpServerConf port(int... ports)
    {
        assertCanChange();
        this.plainPorts = checkPorts(ports);
        return this;
    }

    static HashSet<Integer> checkPorts(int... ports)
    {
        if(ports==null)
            throw new NullPointerException("ports==null");
        HashSet<Integer> set = new HashSet<Integer>();
        for(int port : ports)
        {
            _Util.require( 0<=port && port<=0xffff, "0<=port<=0xffff");
            set.add(port); // remove duplicates
        }
        return set;
    }

    // number of selectors - not configurable now. may change the design

    /**
     * Server socket backlog.
     * <p><code>
     *     default: 50
     * </code></p>
     * <p>
     *     See {@link ServerSocket#bind(SocketAddress endpoint, int backlog)}.
     * </p>
     * @return `this`
     */
    public HttpServerConf serverSocketBacklog(int serverSocketBacklog)
    {
        assertCanChange();
        tcpConf.serverSocketBacklog = serverSocketBacklog;
        return this;
    }


    /**
     * Action to configure the server socket.
     * <p><code>
     *     default action:
     *     enable {@link ServerSocket#setReuseAddress(boolean) SO_REUSEADDR} if the OS is not Windows.
     * </code></p>
     * <p>
     *     This action will be invoked before
     *     {@link ServerSocket#bind(SocketAddress endpoint, int backlog) ServerSocket.bind()}.
     * </p>
     * <p>
     *     App may want to configure more options on the server socket, e.g.
     * </p>
     * <pre>
     *      ConsumerX&lt;ServerSocketChannel&gt; defaultAction = server.conf().get_serverSocketConf();
     *      server.conf().serverSocketConf(
     *          defaultAction.then(serverSocketChannel -&gt;
     *          {
     *              serverSocketChannel.setOption(StandardSocketOptions.SO_RCVBUF, 16*1024);
     *          })
     *      );
     * </pre>
     * <p>
     *     The ServerSocketChannel is in non-blocking model; it must not be changed to blocking mode.
     * </p>
     *
     * @return `this`
     */
    public HttpServerConf serverSocketConf(ConsumerX<ServerSocketChannel> action)
    {
        require(action != null, "action!=null");
        tcpConf.serverSocketConf = action;
        return this;
    }

    /**
     * Action to configure each newly accepted socket.
     * <p><code>
     *     default action:
     *     enable {@link Socket#setTcpNoDelay(boolean) TCP_NODELAY}
     * </code></p>
     * <p>
     *     App may want to configure more options on each socket, for example
     * </p>
     * <pre>
     *     server.conf().socketConf( socketChannel -&gt;
     *     {
     *         socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
     *         ...
     *     });
     * </pre>
     * <p>
     *     The SocketChannel is in non-blocking model; it must not be changed to blocking mode.
     * </p>
     *
     * @return `this`
     */
    public HttpServerConf socketConf(ConsumerX<SocketChannel> action)
    {
        require(action != null, "action!=null");
        tcpConf.socketConf = action;
        return this;
    }

    /**
     * Max number of connections. Must be positive.
     * <p><code>
     *     default: {@link Integer#MAX_VALUE}
     * </code></p>
     * <p>
     *     If this limit is reached, no new incoming connections are accepted,
     *     until some existing connections are closed.
     * </p>
     * @return `this`
     */
    public HttpServerConf maxConnections(int maxConnections)
    {
        assertCanChange();
        require(maxConnections > 0, "maxConnections>0");
        tcpConf.maxConnections = maxConnections;
        return this;
    }

    /**
     * Max number of connections per IP. Must be positive.
     * <p><code>
     *     default: {@link Integer#MAX_VALUE}
     * </code></p>
     * <p>
     *     For any remote IP, if this limit is reached, no new incoming connections are accepted,
     *     until some existing connections are closed.
     * </p>
     * <p>
     *     Note: this limit is only useful if the server is directly connected to clients.
     *     If there is a reverse proxy in front of the server, do not set this limit,
     *     because it limits the number of connections between the reverse proxy and the server.
     *     Instead, configure the reverse proxy to limit connections from clients.
     * </p>
     * @return `this`
     */
    public HttpServerConf maxConnectionsPerIp(int maxConnectionsPerIp)
    {
        assertCanChange();
        require(maxConnectionsPerIp > 0, "maxConnectionsPerIp>0");
        tcpConf.maxConnectionsPerIp = maxConnectionsPerIp;
        return this;
    }


    /**
     * Ids of selectors for this server.
     * <p><code>
     *     default: [0, 1, ... N-1] where N is the number of processors
     * </code></p>
     * <p>
     *     Conceptually there are infinite number of selectors, each associated with a dedicated thread.
     *     A server may choose to use any one or several selectors.
     *     Different servers/clients can share selectors or use different selectors.
     * </p>
     * @return `this`
     */
    public HttpServerConf selectorIds(int... selectorIds)
    {
        assertCanChange();
        _Tcp.validate_confSelectorIds(selectorIds);
        tcpConf.selectorIds = selectorIds;
        return this;
    }



    // conf SSL ............................................................................

    HashSet<Integer> sslPorts = new HashSet<>();

    /**
     * SSL port numbers.
     * <p><code>
     *     default: {} (none)
     * </code></p>
     * <p>
     *     {@link #port(int...) Plain ports} and SSL ports can overlap;
     *     if a port is specified for both plain and SSL, the same port will server both plain and SSL connections.
     * </p>
     * <pre>
     *     server.conf()
     *         .port   (8080)  // for http
     *         .sslPort(8080)  // for https
     * </pre>
     * <p>
     *     Port 0 means an automatically allocated port.
     *     If port 0 is specified for both plain and SSL,
     *     a port is automatically allocated to server both plain and SSL connections.
     * </p>
     * <p>
     *     To forbid plain connections and only accept SSL connections, call
     * </p>
     * <pre>
     *     server.conf()
     *         .port() // no plain ports
     *         .sslPort( SSL_PORT )
     * </pre>
     *
     * @return `this`
     */
    public HttpServerConf sslPort(int... ports)
    {
        assertCanChange();
        this.sslPorts = checkPorts(ports);
        return this;
    }


    SSLContext sslContext = null;
    /**
     * SSLContext for SSL connections.
     * <p><code>
     *     default: null (the default)
     * </code></p>
     * <p>
     *     If <code>null</code>, {@link SSLContext#getDefault()} is used. Typically it requires
     *         system properties <code>javax.net.ssl.keyStore/keyStorePassword</code> etc,
     *         see <a href=
     *         "http://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html#CustomizingStores"
     *         >JSSE Guide</a>.
     * </p>
     * <p>
     *     See {@link #sslKeyStore(String, String)} for a typical way of setting the SSLContext.
     * </p>
     * <p>
     *     See also {@link bayou.ssl.SslConf} for creating SSLContext.
     * </p>
     * @return `this`
     */
    public HttpServerConf sslContext(SSLContext sslContext)
    {
        assertCanChange();
        this.sslContext = sslContext;
        return this;
    }

    /**
     * Set {@link #sslContext(javax.net.ssl.SSLContext) sslContext} using a key store file.
     * <p>
     *     This is a convenience method, equivalent to
     * </p>
     * <pre>
     *      sslContext(
     *          new SslConf()
     *              .keyStoreFile(filePath)
     *              .keyStorePass(password)
     *              .createContext()
     *      )
     * </pre>
     * <p>
     *     See {@link bayou.ssl.SslConf} for more options.
     * </p>
     * @return `this`
     */
    public HttpServerConf sslKeyStore(String filePath, String password) throws Exception
    {
        return this.sslContext(new SslConf()
            .keyStoreFile(filePath)
            .keyStorePass(password)
            .createContext()
        );
    }

    ConsumerX<SSLEngine> sslEngineConf = engine->{};
    /**
     * Action to configure each SSLEngine.
     * <p><code>
     *     default action:
     *     do nothing
     * </code></p>
     * <p>
     *     Example:
     * </p>
     * <pre>
     *   server.conf().sslEngineConf( engine-&gt;
     *   {
     *       engine.setWantClientAuth(true);
     *   });
     * </pre>
     * @return `this`
     */
    public HttpServerConf sslEngineConf(ConsumerX<SSLEngine> action)
    {
        assertCanChange();
        this.sslEngineConf = action;
        return this;
    }


    Duration sslHandshakeTimeout = Duration.ofSeconds(15);
    /**
     * Timeout for completing the SSL handshake on an SSL connection.
     * <p><code>
     *     default: 15 seconds
     * </code></p>
     * @return `this`
     */
    public HttpServerConf sslHandshakeTimeout(Duration sslHandshakeTimeout)
    {
        assertCanChange();
        require(sslHandshakeTimeout != null, "sslHandshakeTimeout!=null");
        this.sslHandshakeTimeout = sslHandshakeTimeout;
        return this;
    }







    // conf HttpServer ............................................................................

    HashMap<_ChArr, String> supportedMethods;
    { // default
        supportedMethods("GET", "HEAD", "POST", "PUT", "DELETE");
        // CONNECT, OPTIONS, TRACE are standard methods listed in RFC7231, but they are
        //    excluded by default since most applications don't expect them
    }
    /**
     * HTTP methods supported by the server.
     * <p><code>
     *     default: { "GET", "HEAD", "POST", "PUT", "DELETE" }
     * </code></p>
     * <p>
     *     The server will send a 501 response if the method of an HTTP request is not among the supported.
     * </p>
     *
     * @return `this`
     */
    // App may want to reduce methods, e.g. to HEAD/GET/POST only.
    // Or app may want to add non-standard methods.
    public HttpServerConf supportedMethods(String... methods)
    {
        assertCanChange();

        require(methods.length > 0, "methods.length>0");

        HashMap<_ChArr, String> map = new HashMap<>();
        for(String method : methods)
        {
            _ChArr str = new _ChArr(method.toCharArray(), method.length());
            map.put(str, method.intern());  // most likely already intern-ed.
        }
        supportedMethods = map;

        return this;
    }

    Duration readTimeout = Duration.ofSeconds(15);
    /**
     * Timeout for read() when reading a request body.
     * <p><code>
     *     default: 15 seconds
     * </code></p>
     * <p>
     *     The <code>request.entity().body().read()</code> action will fail if
     *     the server does not receive any client data within this timeout.
     * </p>
     * <p>
     *     Note that this timeout is for each read() action, not for reading the entire request body.
     * </p>
     * <p>
     *     See also {@link #readMinThroughput(long) readMinThroughput}.
     * </p>
     * @return `this`
     */
    public HttpServerConf readTimeout(Duration readTimeout)
    {
        assertCanChange();
        require(readTimeout != null, "readTimeout!=null");
        this.readTimeout = readTimeout;
        return this;
    }

    // (dial-up speed 7KB/s)
    // to limit client upload/download throughput, wrap request/response entity with ThrottledEntity
    long readMinThroughput = 4*1024;
    /**
     * Min throughput (bytes/second) when reading a request body.
     * <p><code>
     *     default: 4*1024 (4KB/s)
     * </code></p>
     * <p>
     *     The <code>request.entity().body().read()</code> action will fail if
     *     the throughput is below this limit.
     * </p>
     * @return `this`
     */
    public HttpServerConf readMinThroughput(long readMinThroughput)
    {
        assertCanChange();
        require(readMinThroughput >= 0, "readMinThroughput>=0");
        this.readMinThroughput = readMinThroughput;
        return this;
    }

    Duration writeTimeout = Duration.ofSeconds(15);
    /**
     * Timeout for write() when writing a response.
     * <p><code>
     *     default: 15 seconds
     * </code></p>
     * <p>
     *     If the client refuses to accept server data within this timeout,
     *     the response is abandoned, and the connection is closed.
     * </p>
     * <p>
     *     Note that this timeout is for each write() action when writing the response,
     *     not for writing the entire response.
     * </p>
     * <p>
     *     See also {@link #writeMinThroughput(long) writeMinThroughput}.
     * </p>
     *
     * @return `this`
     */
    public HttpServerConf writeTimeout(Duration writeTimeout)
    {
        assertCanChange();
        require(writeTimeout != null, "writeTimeout!=null");
        this.writeTimeout = writeTimeout;
        return this;
    }

    // (dial-up speed 7KB/s)
    long writeMinThroughput = 4*1024;
    /**
     * Min throughput (bytes/second) when writing a response.
     * <p><code>
     *     default: 4*1024 (4KB/s)
     * </code></p>
     * <p>
     *     If the throughput is below this limit when writing a response,
     *     the response is abandoned, and the connection is closed.
     * </p>
     * @return `this`
     */
    public HttpServerConf writeMinThroughput(long writeMinThroughput)
    {
        assertCanChange();
        require(writeMinThroughput >= 0, "writeMinThroughput>=0");
        this.writeMinThroughput = writeMinThroughput;
        return this;
    }


    long outboundBufferSize = 16*1024;  // >0
    /**
     * Outbound buffer size.
     * <p><code>
     *     default: 16*1024 (16KB)
     * </code></p>
     * <p>
     *     The server will attempt to buffer this amount of bytes before write().
     * </p>
     * @return `this`
     */
    public HttpServerConf outboundBufferSize(long outboundBufferSize)
    {
        assertCanChange();
        require(outboundBufferSize > 0, "outboundBufferSize>0");
        this.outboundBufferSize = outboundBufferSize;
        return this;
    }


    Duration keepAliveTimeout = Duration.ofSeconds(15);
    /**
     * Timeout for keep-alive connections.
     * <p><code>
     *     default: 15 seconds
     * </code></p>
     * <p>
     *     On a keep-alive connection, after a response is sent,
     *     if  a new request does not arrive within this timeout,
     *     the connection will be closed.
     * </p>
     * @return `this`
     */
    public HttpServerConf keepAliveTimeout(Duration keepAliveTimeout)
    {
        assertCanChange();
        require(keepAliveTimeout != null, "keepAliveTimeout!=null");
        this.keepAliveTimeout = keepAliveTimeout;
        return this;
    }

    Duration requestHeadTimeout = Duration.ofSeconds(15);
    /**
     * Timeout for reading a request head.
     * <p><code>
     *     default: 15 seconds
     * </code></p>
     * <p>
     *     If the server cannot read an entire request head within this timeout,
     *     the request is abandoned, and the connection is closed.
     * </p>
     * @return `this`
     */
    public HttpServerConf requestHeadTimeout(Duration requestHeadTimeout)
    {
        assertCanChange();
        require(requestHeadTimeout != null, "requestHeadTimeout!=null");
        this.requestHeadTimeout = requestHeadTimeout;
        return this;
    }

    int requestHeadFieldMaxLength = 8*1024; // max length of method, URI, header name/value.
    /**
     * Max length for each field in a request: <code>method, uri, header name, header value</code>.
     * <p><code>
     *     default: 8*1024 (8KB)
     * </code></p>
     * <p>
     *     See also {@link #requestHeadTotalMaxLength(int) requestHeadTotalMaxLength}.
     * </p>
     * @return `this`
     */
    public HttpServerConf requestHeadFieldMaxLength(int requestHeadFieldMaxLength)
    {
        assertCanChange();
        require(requestHeadFieldMaxLength > 0, "requestHeadFieldMaxLength>0");
        this.requestHeadFieldMaxLength = requestHeadFieldMaxLength;
        return this;
    }

    int requestHeadTotalMaxLength = 32*1024;
    /**
     * Max length of a request head.
     * <p><code>
     *     default: 32*1024 (32KB)
     * </code></p>
     * @return `this`
     */
    public HttpServerConf requestHeadTotalMaxLength(int requestHeadTotalMaxLength)
    {
        assertCanChange();
        require(requestHeadTotalMaxLength > 0, "requestHeadTotalMaxLength>0");
        this.requestHeadTotalMaxLength = requestHeadTotalMaxLength;
        return this;
    }

    // for self-defense. (do we really need it?) if exceeded, the response isn't pretty.
    //   if Content-Length exceeds the limit, serve immediately writes a simple error response (then drain and close)
    //   if chunked encoding, and body is discovered to exceed the limit during reading, there's nothing
    //      we can do. since we refuse to read further, inbound can't be drained, response can't be written.
    // the default is pretty high, should be fine for most of today's apps.
    // an app usually has its own limits when parsing entity, e.g. max file size in a form post
    long requestBodyMaxLength = 1024*1024*1024;
    /**
     * Max length of a request body.
     * <p><code>
     *     default: 1024*1024*1024 (1GB)
     * </code></p>
     * <p>
     *     0 is a legal value for this limit - the server does not allow request bodies (except empties ones).
     * </p>
     * @return `this`
     */
    public HttpServerConf requestBodyMaxLength(long requestBodyMaxLength)
    {
        assertCanChange();
        require(requestBodyMaxLength >= 0, "requestBodyMaxLength>=0"); // 0 is legal
        this.requestBodyMaxLength = requestBodyMaxLength;
        return this;
    }

    // before we can write response, we must drain request body. this is the timeout for that.
    Duration drainRequestTimeout = Duration.ofSeconds(15);
    /**
     * Timeout for draining the request body before writing a response.
     * <p><code>
     *     default: 15 seconds
     * </code></p>
     * <p>
     *     App may generate a response without reading the entire request body.
     *     Unfortunately, most HTTP/1 clients will not read the response before it
     *     finishes writing the entire request.
     *     Therefore the server must drain the request before writing the response to avoid deadlock.
     *     This timeout is for the draining step.
     * </p>
     * @return `this`
     */
    public HttpServerConf drainRequestTimeout(Duration drainRequestTimeout)
    {
        assertCanChange();
        require(drainRequestTimeout != null, "drainRequestTimeout!=null");
        this.drainRequestTimeout = drainRequestTimeout;
        return this;
    }

    // before server can close a connection, it needs to drain inbound data, to avoid RST problem.
    // usually the last response is marked by "Connection:close" and client will close immediately.
    // if not, we spend sometime to drain, with a timeout. the timeout can be just long enough
    // to reasonably assure that the response should have been read by the client.
    // ok to be null or negative, meaning no drain. see NbConnection.close(drainTimeout)
    //    but we don't advertise that.
    Duration closeTimeout = Duration.ofSeconds(5);
    /**
     * Timeout for closing a connection.
     * <p><code>
     *     default: 5 seconds
     * </code></p>
     * <p>
     *     Before the server closes a connection,
     *     it sends a TCP FIN to the client, then waits for a FIN from the client,
     *     to make sure the client receives all data of the last response.
     *     This timeout is for that waiting period.
     *     The last response is usually marked with a <code>Connection: close</code> header,
     *     so a decent client should send a FIN as soon as it finishes reading the response.
     * </p>
     * @return `this`
     */
    public HttpServerConf closeTimeout(Duration closeTimeout)
    {
        assertCanChange();
        // null is ok
        this.closeTimeout = closeTimeout;
        return this;
    }


    String requestEncodingPolicy = "reject";
    boolean _requestEncodingReject = true;
    /**
     * How to handle request entity Content-Encoding.
     * <p><code>
     *     default: "reject"
     * </code></p>
     * <p>
     *     Available options are:
     * </p>
     * <dl>
     *     <dt>"reject"</dt>
     *     <dd>
     *         Reject any request with Content-Encoding, with a "415 Unsupported Media Type" response. <br>
     *         Most clients do not encode requests, and most server applications do not expect encoded requests. <br>
     *         With the default "reject" policy, the server application is assured that any request passed to it
     *         has no {@link HttpEntity#contentEncoding() entity contentEncoding}.
     *     </dd>
     *     <dt>"accept"</dt>
     *     <dd>
     *         Accept requests with Content-Encoding. <br>
     *         The server application must be prepared to handle non-null
     *         {@link HttpEntity#contentEncoding() entity contentEncoding} in any request,
     *         and may need to decode the {@link HttpEntity#body() entity body} accordingly, e.g. with
     *         {@link bayou.gzip.GunzipByteSource}.
     *     </dd>
     * </dl>
     * @return `this`
     */
    public HttpServerConf requestEncodingPolicy(String requestEncodingPolicy)
    {
        assertCanChange();

        boolean _requestEncodingReject;
        if(requestEncodingPolicy.equals("reject"))
            _requestEncodingReject = true;
        else if(requestEncodingPolicy.equals("accept"))
            _requestEncodingReject = false;
        else
            throw new IllegalArgumentException("invalid requestEncodingPolicy: "+requestEncodingPolicy);
        // later we may add more policies, e.g. auto-decode

        this.requestEncodingPolicy = requestEncodingPolicy;
        this._requestEncodingReject = _requestEncodingReject;

        return this;
    }

    int xForwardLevel = 0;
    /**
     * Expected level of <code>"X-Forwarded-For"</code>.
     * <p><code>
     *     default: 0
     * </code></p>
     * <p>
     *     If the server is directly connected to clients, set this value to <code>0</code>.
     *     If there is one reverse proxy (e.g. a load balancer) in between, set this value to <code>1</code>.
     *     Higher values are also possible.
     * </p>
     * <p>
     *     The <code>"X-Forwarded-For"</code> header is added by a proxy to reflect
     *     the IP address of the client. The server is usually more interested in that IP,
     *     instead of the IP of the proxy. If there are multiple proxies, the header may
     *     contain a list of IPs.
     * </p>
     * <p>
     *     However, the <code>"X-Forwarded-For"</code> header can be easily spoofed by clients,
     *     therefore the server must be careful with it.
     *     If there is one reverse proxy in front of the server that is under our control and trustworthy,
     *     <code>xForwardLevel=1</code>, the server can trust the last IP in <code>"X-Forwarded-For"</code>,
     *     and {@link HttpRequest#ip()} returns that IP.
     *     Note that clients must not be able to bypass the reverse proxy, or spoofing still works.
     * </p>
     * <p>
     *     If there is no trusted reverse proxy in front of the server,  <code>xForwardLevel=0</code>
     *     <code>"X-Forwarded-For"</code> will be ignored,
     *     {@link HttpRequest#ip()} returns the remote IP of the HTTP connection.
     * </p>
     * <p>
     *     The <code>"X-Forwarded-Proto"</code> header is treated the same way,
     *     influencing the value of {@link HttpRequest#isHttps()}.
     * </p>
     */
    public HttpServerConf xForwardLevel(int xForwardLevel)
    {
        assertCanChange();
        require(xForwardLevel>=0, "xForwardLevel>=0");
        this.xForwardLevel = xForwardLevel;
        return this;
    }


    // auto gzip is off by default. gzip has big impact on latency and throughput, which we care about very much.
    // if bandwidth consumption becomes an issue to an app, it can turn on auto gzip as a quick fix.
    // a more sophisticated app probably would keep auto gzip off, manually handle gzip per response.
    // e.g. StaticHandler handles gzip in its own ways.
    boolean autoGzip = false;
    /**
     * Whether to compress responses with "gzip" automatically.
     * <p><code>
     *     default: false
     * </code></p>
     * <p>
     *     When <code>autoGzip</code> is enabled, if  request/response satisfies the following conditions
     * </p>
     * <ul>
     *     <li>
     *         request <code>"TE"</code> or <code>"Accept-Encoding"</code> header allows "gzip"
     *     </li>
     *     <li>
     *         response {@link HttpEntity#contentType() Content-Type} matches
     *         {@link #autoGzipContentTypes(String...) autoGzipContentTypes}
     *     </li>
     *     <li>
     *         response {@link HttpEntity#contentLength() Content-Length}
     *         &gt;= {@link #autoGzipMinContentLength(long) autoGzipMinContentLength}, or is null(unknown)
     *     </li>
     *     <li>
     *         response {@link HttpEntity#contentEncoding() Content-Encoding} is null
     *         (i.e. the body is not already encoded)
     *     </li>
     * </ul>
     * <p>
     *     the response body will be automatically compressed with
     *     <code>"Transfer-Encoding: gzip,chunked"</code> or
     *     <code>"Content-Encoding: gzip"</code>.
     * </p>
     * <p>
     *     <code>"TE/Transfer-Encoding"</code> is preferred over <code>"Accept-Encoding/Content-Encoding"</code>.
     * </p>
     * <p>
     *     If <code>"Content-Encoding: gzip"</code> is applied, <code>".gzip"</code> will be appended to
     *     <code>"ETag"</code> of the original response.
     * </p>
     * <p>
     *     <i>For manual gzip by app, see</i>
     * </p>
     * <ul>
     *     <li>
     *         {@link HttpRequest#acceptsGzip()}
     *     </li>
     *     <li>
     *         {@link HttpResponse#gzip(HttpResponse)}
     *     </li>
     *     <li>
     *         {@link bayou.file.StaticFileConf#gzip(boolean) StaticHandler.FileConf.gzip}
     *     </li>
     * </ul>
     * @return `this`
     */
    public HttpServerConf autoGzip(boolean autoGzip)
    {
        assertCanChange();
        this.autoGzip = autoGzip;
        return this;
    }

    // list of content types
    // for each content type, subtype can be *, but (main) type cannot be.
    HashSet<String> _autoGzipContentTypes; // exact values set by user
    HashSet<String> autoGzipContentTypeSet; // lower case type/subtype or type alone
    { // default
        autoGzipContentTypes("text/*", "image/svg+xml", "application/javascript", "application/json");
    }
    /**
     * Content types of responses to allow auto gzip.
     * <p><code>
     *     default: { "text/*", "image/svg+xml", "application/javascript", "application/json" }
     * </code></p>
     * <p>
     *     Each argument can be a specific content type, e.g. "text/plain",
     *     or a main type with wildcard subtype, e.g. "text/*".
     * </p>
     * <p>
     *     See {@link #autoGzip(boolean) autoGzip}.
     * </p>
     * @return `this`
     */
    public HttpServerConf autoGzipContentTypes(String... contentTypes)
    {
        assertCanChange();

        // unlikely to fail. autoGzipContentTypes() means the read method
        require(contentTypes.length > 0, "contentTypes.length>0");

        for(String ct : contentTypes)
        {
            if(ct.startsWith("*/"))
                throw new IllegalArgumentException("content type: "+ct);
        }

        _autoGzipContentTypes = new HashSet<>();
        autoGzipContentTypeSet = new HashSet<>();
        for(String ct : contentTypes)
        {
            _autoGzipContentTypes.add(ct);

            if(ct.endsWith("/*"))
                ct = ct.substring(0, ct.length()-2); // just keep the main type
            autoGzipContentTypeSet.add(ct.toLowerCase());
        }

        return this;
    }

    // min response body length to enable auto gzip. if response body length is unknown, it's presumed to be larger.
    long autoGzipMinContentLength = 1024;  // set to max won't disable auto-gzip (when body length is unknown)
    /**
     * Min content length of responses to allow auto gzip.
     * <p><code>
     *     default: 1024 (1KB)
     * </code></p>
     * <p>
     *     See {@link #autoGzip(boolean) autoGzip}.
     * </p>
     * @return `this`
     */
    public HttpServerConf autoGzipMinContentLength(long autoGzipMinContentLength)
    {
        assertCanChange();
        require(autoGzipMinContentLength >= 0, "autoGzipMinContentLength>=0"); // 0 is ok - any body length is subject to gzip
        this.autoGzipMinContentLength = autoGzipMinContentLength;
        return this;
    }




    boolean autoConditional = true;
    /**
     * Whether to handle conditional requests automatically.
     * <p><code>
     *     default: true
     * </code></p>
     * <p>
     *     When enabled, if the request is GET/HEAD and the response is 200,
     *     the server will handle <code>If-Modified-Since/If-None-Match/If-Unmodified-Since/If-Match</code>
     *     conditional requests, and may respond with 304/412 instead.
     *     <!-- we don't explain the logic in detail here since it's too complicated -->
     * </p>
     * <p>
     *     Response entity's {@link HttpEntity#etag() ETag}
     *     and {@link HttpEntity#lastModified() Last-Modified}
     *     headers are consulted for this feature.
     * </p>
     * <p>
     *     For <code>If-Range</code> requests see {@link #autoRange(boolean) autoRange}.
     * </p>
     * @return `this`
     */
    public HttpServerConf autoConditional(boolean autoConditional)
    {
        assertCanChange();
        this.autoConditional = autoConditional;
        return this;
    }

    // individual response can disable auto-range by setting Accept-Ranges: none
    boolean autoRange = true;
    /**
     * Whether to handle Range requests automatically.
     * <p><code>
     *     default: true
     * </code></p>
     * <p>
     *     When enabled, if the request is GET/HEAD with the "Range" header,
     *     and the response is 200 without the "Accept_Ranges" header,
     *     the server will handle the partial request, and may serve a partial response body.
     *     The "If-Range" request header is also handled.
     *     <!-- we don't explain the logic in detail here since it's too complicated -->
     * </p>
     * <p>
     *     App can bypass the default behavior by setting the "Accept_Ranges" header in a response.
     * </p>
     * <p>
     *     See also {@link RangedByteSource}.
     * </p>
     * @return `this`
     */
    public HttpServerConf autoRange(boolean autoRange)
    {
        assertCanChange();
        this.autoRange = autoRange;
        return this;
    }

    boolean autoCacheControl = true;
    /**
     * Whether to set response header "Cache-Control" automatically.
     * <p><code>
     *     default: true
     * </code></p>
     * <p>
     *     When enabled, if the request is GET/HEAD,
     *     and the response does not already contain the "Cache-Control" header,
     * </p>
     * <ul>
     *     <li>
     *         if response {@link HttpEntity#expires() Expires}<code>!=null</code>,
     *         set response header <code>"Cache-Control: private"</code>
     *     </li>
     *     <li>
     *         if response {@link HttpEntity#expires() Expires}<code>==null</code>,
     *         set response header <code>"Cache-Control: private, no-cache"</code>
     *     </li>
     * </ul>
     * <p>
     *     We add <code>"private"</code> by default to avoid accidentally leaking private responses.
     * </p>
     * <p>
     *     App can bypass the default behavior by setting the "Cache-Control" header in a response.
     * </p>
     * @return `this`
     */
    public HttpServerConf autoCacheControl(boolean autoCacheControl)
    {
        assertCanChange();
        this.autoCacheControl = autoCacheControl;
        return this;
    }

    // null to disable access log.
    // logger can be blocking?
    // will not be called concurrently.
    // if app actually wants to print concurrently, dispatch tasks inside accept()
    // if throws, printer is broken, all future log entries are ignored
    Consumer<HttpAccess> _accessLogger = null;
    HttpAccessLoggerWrapper accessLoggerWrapper; // see freeze()
    /**
     * Http Access Logger.
     * <p><code>
     *     default: null
     * </code></p>
     * <p>
     *     If non-null, {@link HttpAccess} entries will be passed to the logger to be logged.
     * </p>
     * <p>
     *     Example Usage:
     * </p>
     * <pre>
     *     server.conf().accessLogger( entry -&gt;
     *         System.out.println(entry.toCombinedLogFormat()) );
     * </pre>
     * <p>
     *     The server allocates a dedicated thread for the logger; entries will be passed to it
     *     in a serialized order. The logger can invoke blocking IO actions.
     *     If the logger throws, it is abandoned, and future accesses will not be logged.
     * </p>
     * <p>
     *     See also {@link #accessLogTo(java.util.function.Consumer) accessLogTo(Consumer&lt;CharSequence&gt;)}
     * </p>
     * @return `this`
     */
    public HttpServerConf accessLogger(Consumer<HttpAccess> accessLogger)
    {
        assertCanChange();
        // null is ok
        this._accessLogger = accessLogger;
        return this;
    }
    /**
     * Set the {@link #accessLogger(java.util.function.Consumer) accessLogger} as one that writes entries to `out`.
     * <p>
     *     Each HttpAccess entry is converted to
     *     {@link HttpAccess#toCombinedLogFormat() Combined Log Format}
     *     and written to `out`.
     * </p>
     * <p>
     *     Example Usage:
     * </p>
     * <pre>
     *     server.conf().accessLogTo( System.out::print );
     *
     *     // to a file. also, consider 'logrotate' utility on linux.
     *     PrintStream accessLog = new PrintStream(new FileOutputStream("access.log", true), true);
     *     server.conf().accessLogTo( accessLog::print );
     * </pre>
     * <p>
     *     See {@link #accessLogger(java.util.function.Consumer) accessLogger(Consumer&lt;HttpAccess&gt;)}.
     * </p>
     * @return `this`
     */
    public HttpServerConf accessLogTo(Consumer<CharSequence> out)
    {
        require(out != null, "out!=null");
        Consumer<HttpAccess> printer = entry ->
        {
            out.accept(entry.toCombinedLogFormat());
            out.accept(System.lineSeparator());
        };
        return accessLogger(printer);
    }

    // The server allocates a dedicated thread for `trafficDump`; entries will be passed to it
    // in a serialized order. it can invoke blocking IO actions.
    Consumer<CharSequence> _trafficDump = null;    // don't use it directly. use the wrapper instead
    _TrafficDumpWrapper trafficDumpWrapper; // see freeze()

    /**
     * Where to dump http traffic, for debugging purpose.
     * <p><code>
     *     default: null
     * </code></p>
     * <p>
     *     If non-null, request/response heads will be dumped to it.
     *     This feature is mostly enabled during development time.
     * </p>
     * <p>
     *     Example Usage:
     * </p>
     * <pre>
     *     server.conf().trafficDump( System.out::print );
     * </pre>
     *
     * @return `this`
     */
    public HttpServerConf trafficDump(Consumer<CharSequence> trafficDump)
    {
        assertCanChange();
        // null is ok
        this._trafficDump = trafficDump;
        return this;
    }


    // getters. not important to apps ==================================================================
    // do not give them javadoc. leave them blank on the method summary table

    public InetAddress get_ip()
    {
        return ip;  // non null
    }
    public List<Integer> get_ports()
    {
        ArrayList<Integer> list = new ArrayList<>(plainPorts);
        Collections.sort(list);
        return list;
    }
    public ConsumerX<ServerSocketChannel> get_serverSocketConf()
    {
        return tcpConf.serverSocketConf;
    }
    public ConsumerX<SocketChannel> get_socketConf()
    {
        return tcpConf.socketConf;
    }
    public int get_serverSocketBacklog()
    {
        return tcpConf.serverSocketBacklog;
    }
    public int get_maxConnections()
    {
        return tcpConf.maxConnections;
    }
    public int get_maxConnectionsPerIp()
    {
        return tcpConf.maxConnectionsPerIp;
    }
    public int[] get_selectorIds()
    {
        return tcpConf.selectorIds;
    }
    public List<Integer> get_sslPorts()
    {
        ArrayList<Integer> list = new ArrayList<>(sslPorts);
        Collections.sort(list);
        return list;
    }
    public SSLContext get_sslContext()
    {
        return this.sslContext;
    }
    public ConsumerX<SSLEngine> get_sslEngineConf()
    {
        return this.sslEngineConf;
    }
    public Duration get_sslHandshakeTimeout()
    {
        return this.sslHandshakeTimeout;
    }

    public Set<String> get_supportedMethods()
    {
        return new HashSet<>(supportedMethods.values());
    }

    public Duration get_readTimeout()
    {
        return readTimeout;
    }
    public long get_readMinThroughput()
    {
        return readMinThroughput;
    }
    public Duration get_writeTimeout()
    {
        return writeTimeout;
    }
    public long get_writeMinThroughput()
    {
        return writeMinThroughput;
    }
    public long get_outboundBufferSize()
    {
        return outboundBufferSize;
    }
    public Duration get_keepAliveTimeout()
    {
        return keepAliveTimeout;
    }
    public Duration get_requestHeadTimeout()
    {
        return requestHeadTimeout;
    }
    public int get_requestHeadFieldMaxLength()
    {
        return requestHeadFieldMaxLength;
    }
    public int get_requestHeadTotalMaxLength()
    {
        return requestHeadTotalMaxLength;
    }
    public long get_requestBodyMaxLength()
    {
        return requestBodyMaxLength;
    }
    public Duration get_drainRequestTimeout()
    {
        return drainRequestTimeout;
    }
    public Duration get_closeTimeout()
    {
        return closeTimeout;
    }
    public String get_requestEncodingPolicy()
    {
        return requestEncodingPolicy;
    }
    public int get_xForwardLevel()
    {
        return xForwardLevel;
    }
    public boolean get_autoGzip()
    {
        return autoGzip;
    }

    public Set<String> get_autoGzipContentTypes()
    {
        return Collections.unmodifiableSet(_autoGzipContentTypes);
    }

    public long get_autoGzipMinContentLength()
    {
        return autoGzipMinContentLength;
    }
    public boolean get_autoConditional()
    {
        return autoConditional;
    }
    public boolean get_autoRange()
    {
        return autoRange;
    }
    public boolean get_autoCacheControl()
    {
        return autoCacheControl;
    }
    public Consumer<HttpAccess> get_accessLogger()
    {
        return _accessLogger;
    }
    public Consumer<CharSequence> get_trafficDump()
    {
        return _trafficDump;
    }
}
