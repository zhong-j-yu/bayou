package bayou.websocket;

import _bayou._tmp._TrafficDumpWrapper;
import bayou.http.HttpServerConf;

import java.time.Duration;
import java.util.function.Consumer;

import static _bayou._tmp._Util.require;

/**
 * Configuration of WebSocketServer.
 * <p>
 *     Each config property can be set by the setter method, for example
 * </p>
 * <pre>
 *     wsServer.conf().enforceSameOrigin( false );
 * </pre>
 * <p>
 *     The setters return `this` for method chaining, for example
 * </p>
 * <pre>
 *     wsServer.conf()
 *         .enforceSameOrigin( false )
 *         .trafficDump( null )
 *         ;
 * </pre>
 * <p>
 *     The config properties must be set before server start.
 * </p>
 */
public class WebSocketServerConf
{
    WebSocketServerConf()
    {

    }

    boolean frozen;
    void freeze() throws Exception
    {
        frozen = true;

        // may do extra validations here

        // calculate derived fields
        trafficDumpWrapper = _trafficDump ==null ? null : new _TrafficDumpWrapper("WebSocket", _trafficDump);
    }
    void assertCanChange()
    {
        if(frozen)
            throw new IllegalStateException("cannot change conf after server start");
    }


    // if true, Origin header must exist and match Host header
    // if false, app code should check Origin header
    boolean enforceSameOrigin = true;
    /**
     * Whether to enforce same-origin policy on websocket requests.
     * <p><code>
     *     default: true
     * </code></p>
     * <p>
     *     If enabled, a websocket handshake request must contain
     *     the "Origin" header that matches the "Host" header,
     *     otherwise the handshake automatically fails with 403
     *     (and {@link bayou.websocket.WebSocketHandler} will not be invoked).
     * </p>
     * <p>
     *     To support cross-origin requests,
     *     disable enforceSameOrigin, and check "Origin" manually
     *     in {@link bayou.websocket.WebSocketHandler#handle(WebSocketRequest)}.
     * </p>
     * @return `this`
     */
    public WebSocketServerConf enforceSameOrigin(boolean enforceSameOrigin)
    {
        assertCanChange();
        this.enforceSameOrigin =enforceSameOrigin;
        return this;
    }

    // if the connection is idle for some time, we'll send Ping to client to test the connection.
    Duration pingInterval = Duration.ofSeconds(60);
    /**
     * Interval between server PING requests.
     * <p><code>
     *     default: 60 seconds
     * </code></p>
     * <p>
     *     If a websocket connection has been idle (no inbound/outbound traffic)
     *     longer than this interval, the server sends a PING request to the client.
     * </p>
     * @return `this`
     */
    public WebSocketServerConf pingInterval(Duration pingInterval)
    {
        assertCanChange();
        require(pingInterval != null, "pingInterval!=null");
        this.pingInterval =pingInterval;
        return this;
    }

    // time within which we expect Pong from client for our prev Ping
    Duration pingPongTimeout = Duration.ofSeconds(15);
    /**
     * Timeout for client PONG response to server PING request.
     * <p><code>
     *     default: 15 seconds
     * </code></p>
     * <p>
     *     After the server sends a PING request to a client
     *     (see {@link #pingInterval(java.time.Duration) pingInterval}),
     *     if no data from the client is received within this timeout,
     *     the websocket connection will be dropped.
     * </p>
     * <p>
     *     Note that any data (not just PONG) from the client would indicate that the client is still responsive.
     * </p>
     * @return `this`
     */
    public WebSocketServerConf pingPongTimeout(Duration pingPongTimeout)
    {
        assertCanChange();
        require(pingPongTimeout != null, "pingPongTimeout!=null");
        this.pingPongTimeout =pingPongTimeout;
        return this;
    }

    // if app not reading, inbound message data are buffered, up to a point. if exceeded, pause reading from client.
    // usually should be quite small; can even be 0.
    int inboundBufferSize = 1024;
    /**
     * Inbound data buffer size.
     * <p><code>
     *     default: 1024 (1KB)
     * </code></p>
     * <p>
     *     Even if app is not {@link WebSocketChannel#readMessage() reading from the client},
     *     the server still buffers inbound data in background.
     *     This is the limit of that buffer.
     * </p>
     * <p>
     *     This limit can be set to a small value, even 0.
     * </p>
     * @return `this`
     */
    public WebSocketServerConf inboundBufferSize(int inboundBufferSize)
    {
        assertCanChange();
        require(inboundBufferSize >= 0, "inboundBufferSize>=0");
        this.inboundBufferSize =inboundBufferSize;
        return this;
    }


    // outbound frame payload length
    // note: also constrained by outboundBufferSize
    // big frame/outboundBufferSize may be wasting memory if client is slow
    // some client may not be able to handle big frames
    long outboundPayloadMax = 16*1024;
    /**
     * Max length of outbound frame payload.
     * <p><code>
     *     default: 16*1024 (16KB)
     * </code></p>
     * <p>
     *     Outbound frame payload length is also constrained by
     *     {@link HttpServerConf#outboundBufferSize(long) outboundBufferSize}
     *     (inherited from HttpServerConf).
     * </p>
     * @return `this`
     */
    public WebSocketServerConf outboundPayloadMax(long outboundPayloadMax)
    {
        assertCanChange();
        require(outboundPayloadMax > 0, "outboundPayloadMax>0");
        this.outboundPayloadMax =outboundPayloadMax;
        return this;
    }


    // The server allocates a dedicated thread for `trafficDump`; entries will be passed to it
    // in a serialized order. it can invoke blocking IO actions.
    Consumer<CharSequence> _trafficDump = null;    // don't use it directly. use the wrapper instead
    _TrafficDumpWrapper trafficDumpWrapper; // see freeze()

    /**
     * Where to dump websocket traffic, for debugging purpose.
     * <p><code>
     *     default: null
     * </code></p>
     * <p>
     *     If non-null, websocket inbound/outbound frame info will be dumped to it.
     *     This feature is mostly enabled during development time.
     * </p>
     * @return `this`
     */
    public WebSocketServerConf trafficDump(Consumer<CharSequence> trafficDump)
    {
        assertCanChange();
        // null is ok
        this._trafficDump = trafficDump;
        return this;
    }


    // conf copied from http server ...................................................................

    Duration readTimeout;
    long readMinThroughput;
    Duration writeTimeout;
    long writeMinThroughput;
    long outboundBufferSize;   // >0
    Duration closeTimeout;

    void copy(HttpServerConf hc)
    {
        readTimeout = hc.get_readTimeout();
        readMinThroughput = hc.get_readMinThroughput();
        writeTimeout = hc.get_writeTimeout();
        writeMinThroughput = hc.get_writeMinThroughput();
        outboundBufferSize = hc.get_outboundBufferSize();
        closeTimeout = hc.get_closeTimeout();
    }

    // getters, not important for apps ====================================================================

    public boolean get_enforceSameOrigin()
    {
        return enforceSameOrigin;
    }
    public Duration get_pingInterval()
    {
        return pingInterval;
    }
    public Duration get_pingPongTimeout()
    {
        return pingPongTimeout;
    }
    public int get_inboundBufferSize()
    {
        return inboundBufferSize;
    }
    public long get_outboundPayloadMax()
    {
        return outboundPayloadMax;
    }
    public Consumer<CharSequence> get_trafficDump()
    {
        return _trafficDump;
    }
}
