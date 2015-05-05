package bayou.http;

import bayou.async.Async;
import bayou.tcp.TcpAddress;
import bayou.tcp.TcpConnection;
import bayou.tcp.TcpTunnel;
import bayou.util.UserPass;

import java.util.HashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A TcpTunnel using HTTP CONNECT method.
 * See <a href="http://tools.ietf.org/html/rfc7231#section-4.3.6">RFC7231 &sect;4.3.6</a>
 * <p>
 *     Such tunnel is often used for "HTTPS proxy"; but it can be used for tunneling any TCP traffic too.
 * </p>
 * <p>
 *     <a href="http://tools.ietf.org/html/rfc2617">Basic/Digest authentications</a> are supported.
 * </p>
 * <p>
 *     This class is stateful for caching authentication state.
 * </p>
 */
public class ConnectTunnel implements TcpTunnel
{
    TcpAddress address; // can be ssl

    // null if auth not supported
    Function<TcpAddress, Async<UserPass>> userPassFunc;
    HashMap<TcpAddress, AuthHandler.AuthInfo> auth_cache;

    /**
     * Create an HTTP CONNECT tunnel.
     * <p>
     *     If userPassSupplier!=null, BASIC and DIGEST authentications are supported.
     * </p>
     */
    public ConnectTunnel(TcpAddress address, Supplier<Async<UserPass>> userPassSupplier)
    {
        this.address = address;

        if(userPassSupplier!=null)
        {
            userPassFunc = addr->userPassSupplier.get();
            auth_cache = new HashMap<>();
        }
    }

    /**
     * Create an HTTP CONNECT tunnel.
     * <p>
     *     This is a convenience method for
     *     {@link #ConnectTunnel(bayou.tcp.TcpAddress, java.util.function.Supplier)
     *     ConnectTunnel(address, userPassSupplier)}
     *     with address.ssl=false.
     * </p>
     */
    public ConnectTunnel(String host, int port, String username, String password)
    {
        this(
            new TcpAddress(false, host, port),
            ()->Async.success(new UserPass(username, password))
        );
    }

    /**
     * Create an HTTP CONNECT tunnel.
     * <p>
     *     This is a convenience method for
     *     {@link #ConnectTunnel(bayou.tcp.TcpAddress, java.util.function.Supplier)
     *     ConnectTunnel(address, userPassSupplier)}
     *     with address.ssl=false, userPassSupplier=null.
     * </p>
     */
    public ConnectTunnel(String host, int port)
    {
        this( new TcpAddress(false, host, port), null);
    }

    @Override
    public TcpAddress address()
    {
        return address;
    }





    @Override
    public Async<TcpConnection> tunnelTo(TcpConnection tcpConn, String host, int port)
    {
        // send CONNECT request to next hop
        // may handle one round of authentication

        HttpClientConnection hConn = new HttpClientConnection(tcpConn);
        // [close on exception]

        HttpHandler handler = request->hConn.send(request).then(v->hConn.receive());

        if(userPassFunc!=null)
            handler = new AuthHandler(address, handler, userPassFunc, auth_cache);

        // request target. if host is ipv6 literal, it must be enclosed with []
        // target = host:port
        // host = domain / ipv4 / "[" ipv6 "]"
        String nextHost_Port = (host.indexOf(':')==-1) ? host+":"+port
            : "[" + host + "]:" + port;
        HttpRequestImpl req = new HttpRequestImpl("CONNECT", nextHost_Port, null);

        return handler.handle(req)
            .then(response->
            {
                if(response.statusCode()/100==2) // 2xx means success. there's no response body.
                    return Async.success(tcpConn);
                return Async.failure(new Exception("failed to tunnel to "+nextHost_Port+", " +
                    "response.status="+response.status()));
            })
            .catch__(Exception.class, ex -> {
                hConn.close();
                throw ex;
            });
    }

}
