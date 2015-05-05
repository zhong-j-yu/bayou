package bayou.tcp;

import bayou.async.Async;

/**
 * A tunnel forwarding data between TCP clients and servers.
 * <p>
 *     A tunnel sits between a client and a server, forwarding data in both directions.
 *     For example a <a href="http://en.wikipedia.org/wiki/SOCKS">SOCKS proxy</a> is a TCP tunnel.
 * </p>
 * <p>
 *     This interface is from the client's perspective.
 *     The client first establishes a `connection` to the tunnel's {@link #address()},
 *     then calls {@link #tunnelTo(TcpConnection, String, int) tunnelTo(connection, host, port)}
 *     to instruct it to tunnel to the server.
 * </p>
 */
public interface TcpTunnel
{

    /**
     * The address of the tunnel service.
     * <p>
     *     If {@link TcpAddress#ssl() address.ssl}==true,
     *     traffic between the client and the tunnel must be encrypted in SSL.
     * </p>
     */
    TcpAddress address();

    /**
     * Tunnel to the remote host:port.
     * <p>
     *     The `connection` is established between the client and the tunnel service.
     *     The implementation of this method does necessary protocol dance to
     *     instruct the tunnel service to tunnel to the server (at host:port).
     * </p>
     * <p>
     *     When this action succeeds, the resulting TcpConnection is used by the client
     *     to communicate to the server, as if it's a direct connection.
     *     The resulting TcpConnection could be the same object as the `connection` argument.
     * </p>
     */
    Async<TcpConnection> tunnelTo(TcpConnection connection, String host, int port);

}
