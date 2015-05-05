package bayou.http;

import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.tcp.TcpAddress;
import bayou.util.Result;
import bayou.util.UserPass;

import java.util.function.Supplier;

/**
 * Proxy information for HttpClient. See {@link HttpClientConf#proxy(HttpProxy)}.
 * <p>
 *     This class is a simple data structure for proxy address and authentication.
 * </p>
 *
 */
public class HttpProxy
{
    private TcpAddress address;

    private Supplier<Async<UserPass>> userPassSupplier;

    /**
     * Create an HttpProxy.
     * <p>
     *     If userPassSupplier!=null, <a href="http://tools.ietf.org/html/rfc2617">Basic/Digest authentications</a>
     *     are supported by HttpClient for proxy authentication.
     * </p>
     */
    public HttpProxy(TcpAddress address, Supplier<Async<UserPass>> userPassSupplier)
    {
        this.address = address;
        this.userPassSupplier = userPassSupplier;
    }

    /**
     * Create an HttpProxy.
     * <p>
     *     This is a convenience method for
     *     {@link #HttpProxy(bayou.tcp.TcpAddress, java.util.function.Supplier) HttpProxy(address, userPassSupplier)}
     *     with address.ssl=false.
     * </p>
     */
    public HttpProxy(String host, int port, String username, String password)
    {
        this(
            new TcpAddress(false, host, port),
            ()->Async.success(new UserPass(username, password))
        );
    }

    /**
     * Create an HttpProxy.
     * <p>
     *     This is a convenience method for
     *     {@link #HttpProxy(bayou.tcp.TcpAddress, java.util.function.Supplier) HttpProxy(address, userPassSupplier)}
     *     with address.ssl=false, userPassSupplier=null.
     * </p>
     */
    public HttpProxy(String host, int port)
    {
        this( new TcpAddress(false, host, port), null);
    }


    /**
     * The proxy address.
     */
    public TcpAddress address()
    {
        return address;
    }

    /**
     * The username/password supplier for proxy authentication.
     */
    public Supplier<Async<UserPass>> userPassSupplier()
    {
        return userPassSupplier;
    }
}
