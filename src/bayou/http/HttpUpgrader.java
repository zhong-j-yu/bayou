package bayou.http;

import bayou.async.Async;
import bayou.async.Fiber;
import bayou.tcp.TcpConnection;

/**
 * For handling HTTP Upgrade mechanism.
 * <p>
 *     See
 *     <a href="http://tools.ietf.org/html/rfc2616#section-14.42">RFC2616 &sect;14.42 Upgrade</a>.
 * </p>
 * <p>
 *     An HttpUpgrader can be registered to an HttpServer by
 *     {@link HttpServer#addUpgrader(String, HttpUpgrader) HttpServer.addUpgrader(protocol, httpUpgrader)}.
 *     If an HttpRequest requests to upgrade the connection to that protocol,
 *     the {@link #tryUpgrade(HttpRequest, bayou.tcp.TcpConnection)} method is invoked on the upgrader.
 *     If the upgrade is successful, the upgrader takes over the connection and
 *     is responsible to handle the connection by the new protocol.
 * </p>
 */
public interface HttpUpgrader
{
    // why not have a method returning supported protocols?
    // not sure about the return type. String seems fine. but some impl may need Set<String>

    // invoked just before http server is started

    /**
     * Initialize this upgrader.
     * <p>
     *     This method is invoked before the HttpServer is started.
     * </p>
     */
    public void init(HttpServerConf httpServerConf) throws Exception;

    /**
     * Try to upgrade the http connection.
     * <p>
     *     This is an async action. If the action completes with {@code (HttpResponse)null},
     *     upgrade is successful, this HttpUpgrader takes over the TcpConnection.
     *     If the action completes with a {@code non-null HttpResponse},
     *     the connection remains HTTP not-upgraded, and the HttpResponse
     *     will be written to the client. If the action fails with an Exception, it's an internal error
     *     and the connection will be killed.
     * </p>
     * <p>
     *     If the `httpRequest` contains a body, the body should be drained first by `read()` till EOF,
     *     before using the `tcpConnection` for new protocol.
     * </p>
     * <p>
     *     This method is usually invoked in a {@link Fiber} created by the http server.
     *     If upgrade is successful, that Fiber will end; a new Fiber or Fibers can be
     *     created for the continued handling of the tcp connection.
     * </p>
     */
    public Async<HttpResponse> tryUpgrade(HttpRequest httpRequest, TcpConnection tcpConnection);
    // if the return value is null, we'll treat it as Async.success(null),
    //   i.e. success takeover. however, don't advertise that.

}
