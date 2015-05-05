package bayou.http;

import _bayou._http._HttpUtil;
import _bayou._tmp._TrafficDumpWrapper;
import bayou.async.Async;
import bayou.async.Fiber;
import bayou.async.Promise;
import bayou.ssl.SslConnection;
import bayou.tcp.TcpAddress;
import bayou.tcp.TcpConnection;

import java.time.Duration;
import java.util.concurrent.Executor;


/**
 * Http connection from client's perspective.
 * <p>
 *     This is a low level API that may be needed in rare use cases.
 * </p>
 */
//why expose the connection abstraction
//    app can send multiple requests on a connection. can do pipeline requests.
//    app may need connection-aware features, e.g. upgrade to WebSocket
//
//    requests on the same conn are explicitly ordered.
//    on different connections, app can order requests by sending a request
//    after waiting for response to the previous request.
public class HttpClientConnection
{
    final Object lock(){ return this; }
    // we don't really worry about lock contention. usually all actions are done on the selector flow.

    HttpClientConf conf;
    TcpAddress dest;

    TcpConnection tcpConn;
    HttpClientOutbound outbound;
    HttpClientInbound inbound;

    final Promise<Void> closePromise;

    /**
     * Create an HTTP connection, on top of the TCP connection.
     * <p>
     *     See also {@link bayou.http.HttpClient#newConnection(bayou.tcp.TcpAddress)}
     * </p>
     */
    public HttpClientConnection(TcpConnection tcpConnection)
    {
        this(toDest(tcpConnection), tcpConnection, false, new HttpClientConf(), false);
    }

    static TcpAddress toDest(TcpConnection tcpConn)
    {
        boolean ssl = (tcpConn instanceof SslConnection);
        String host = tcpConn.getPeerHost();
        if(host==null) host = tcpConn.getPeerIp().getHostAddress();
        return new TcpAddress(ssl, host, tcpConn.getPeerPort());
    }

    HttpClientConnection(TcpAddress dest, TcpConnection tcpConnection, boolean sendAbsoluteUri,
                         HttpClientConf conf, boolean cloneConf)
    {
        if(cloneConf)
            conf = conf.clone();
        this.conf = conf;

        this.dest = dest;
        this.tcpConn = tcpConnection;

        this.outbound = new HttpClientOutbound(this, sendAbsoluteUri);
        this.inbound = new HttpClientInbound(this);

        closePromise = new Promise<>();
        closePromise.fiberTracePop();

        _TrafficDumpWrapper dump = conf.trafficDumpWrapper;
        if(dump!=null)
            dump.print(connId(), " open [",
                dest.host(), ":", ""+dest.port(), dest.ssl()?" SSL":"",
                "] ==\r\n");

    }

    String connId()
    {
        return "== connection #"+ tcpConn.getId();
    }

    /**
     * Send a request over this connection.
     * <p>
     *     This action completes when the request (including the body, if any) is completely written to the
     *     underlying TCP connection.
     * </p>
     * <p>
     *     If this action fails (e.g. due to {@link Async#cancel(Exception) cancel}),
     *     the connection outbound is corrupt, and further requests will fail too.
     * </p>
     * <p>
     *     It's not necessary to wait for the completion of this action,
     *     or the receipt of the corresponding response, before sending another request.
     *     This method can be used for
     *     <a href="http://tools.ietf.org/html/rfc7230#section-6.3.2">request pipelining</a>.
     * </p>
     * <p>
     *     It's not necessary to wait for the completion of this action
     *     before calling receive()/receive0();
     *     request and response can be transmitted concurrently over this connection.
     *     Although, most servers will read the entirety of the request before sending the response.
     * </p>
     * <p>
     *     If the request contains "Expect: 100-continue",
     *     and the server responds that the request body should not be sent,
     *     this action may fail (because the request is not completely written);
     *     nevertheless the corresponding receive()/receive0() could still succeed.
     * </p>
     */
    public Async<Void> send(HttpRequest request)
    {
        return outbound.stageMessage(request);
        // if request is CONNECT yet contains a body, we send the body normally, not knowing what it means.
    }

    /**
     * Receive the next non-1xx response.
     * <p>
     *     This action calls {@link #receive0()} repeatedly
     *     until a non-1xx response is received.
     *     Intermediary 1xx responses are skipped.
     * </p>
     */
    public Async<HttpResponse> receive()
    {
        return receive0()
            .then(res ->
            {
                if (res.statusCode() / 100 == 1) // ignore
                    return receive(); // tail recursion
                return _HttpUtil.toAsync(res);
            });
    }
    /**
     * Receive the next response, which could be 1xx.
     * <p>
     *     This action completes as soon as the head of the response is completely received.
     * </p>
     * <p>
     *     This method can only be called if the corresponding request has been
     *     queued previously by {@link #send(HttpRequest) send(request)},
     *     because, unfortunately, response framing is dependent on the request.
     *     One request is corresponded to zero or more 1xx responses, and then one non-1xx response.
     * </p>
     * <p>
     *     Concurrent pending receive0() is not allowed.
     *     This method can only be called
     *     after the previous response is received, and its body is drained.
     * </p>
     * <p>
     *     If this action fails (e.g. due to {@link Async#cancel(Exception) cancel}),
     *     the connection inbound is corrupt, and further receive0() will fail too.
     * </p>
     * @see #isLastResponse()
     */
    public Async<HttpResponse> receive0()
    {
        return inbound.receiveNextResponse();
    }


    /**
     * Whether the "last" response is received.
     * <p>
     *     A response could be the last one on the connection for various reasons,
     *     e.g. it contains a "Connection: close" header.
     * </p>
     * <p>
     *     A 1xx response cannot be the "last" response.
     * </p>
     * <p>
     *     Once the "last" response is received, the connection cannot be used for further requests/responses.
     * </p>
     * <p>
     *     After receive()/receive0() succeeds, application should check isLastResponse();
     *     if it's true, the connection should be closed;
     *     a new connection is needed for further requests/responses.
     * </p>
     */
    public boolean isLastResponse()
    {
        return inbound.isLastResponse();
    }

    /**
     * Get the underlying TCP connection.
     * <p>
     *     It's not safe to read from and write to the TCP connection,
     *     unless it's certain that there's no outstanding requests/responses
     *     on the HTTP connection.
     * </p>
     */
    public TcpConnection getTcpConnection()
    {
        return tcpConn;
    }

    /**
     * Close the connection and free resources.
     * <p>
     *     All outstanding send/receive actions will be aborted.
     * </p>
     */
    public Async<Void> close()
    {
        // request inbound&outbound to close. they'll handle duplicate close() calls.
        inbound.close();
        outbound.close();

        if(!Fiber.enableTrace || closePromise.isCompleted())
            return closePromise;

        // fiber trace enabled, and close is pending. do not return `closePromise`
        // create a new Promise here, so that caller fiber trace appear to be stuck at close()
        Promise<Void> promise = new Promise<>();
        closePromise.onCompletion(promise::complete);
        // no cancel propagation?
        return promise;
    }


    byte tcpConnCloseCount;
    void tcpConn_close(boolean graceful)
    {
        // this method is called once by inbound and once by outbound
        // we'll close for real on the 2nd call. `graceful` is AND-ed.
        final int T=3, F=4;
        byte _tcpConnCloseCount;
        synchronized (lock())
        {
            tcpConnCloseCount += ( graceful ? T : F );
            _tcpConnCloseCount = tcpConnCloseCount;
        }
        boolean grace;
        switch (_tcpConnCloseCount)
        {
            case T:
            case F:
                return; // do not close yet

            case T+T:
                grace = true;
                break;

            case T+F:
            case F+F:
                grace = false; // not graceful close; no draining. FIN is sent.
                break;

            default:
                throw new AssertionError();
        }

        // for client, RST is not usually a concern. typically, close() is called after response is received,
        // and typically that's after server received all request data.
        // we are being pedantic here, for best-effort delivery of outbound data if send(req) reports completion.
        Duration closeTimeout = Duration.ofSeconds(1);
        // timeout is only 1 second, because often it's the client that initiates FIN.
        Async<Void> tcpConnClosing = tcpConn.close( grace ? closeTimeout : null );

        _TrafficDumpWrapper dump = conf.trafficDumpWrapper;
        if(dump!=null)
            dump.print(connId(), " closed ==\r\n");

        if(tcpConnClosing.isCompleted()) // not rare
            closePromise.complete(tcpConnClosing.pollResult());
        else
            tcpConnClosing.onCompletion(closePromise::complete); // propagate cancel? promise->tcpConn
    }

}
