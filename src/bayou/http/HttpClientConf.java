package bayou.http;

import _bayou._http._HttpUtil;
import _bayou._tmp._Dns;
import _bayou._tmp._Tcp;
import _bayou._tmp._TrafficDumpWrapper;
import bayou.async.Async;
import bayou.mime.HeaderMap;
import bayou.mime.Headers;
import bayou.tcp.TcpAddress;
import bayou.tcp.TcpTunnel;
import bayou.util.UserPass;
import bayou.util.function.ConsumerX;

import javax.net.ssl.*;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static _bayou._tmp._Util.require;

/**
 * Configuration for HttpClient.
 * <p>
 *     Each config property can be set by the setter method, for example
 * </p>
 * <pre>
 *     conf.proxy("127.0.0.1", 8080);
 * </pre>
 * <p>
 *     The setters return `this` for method chaining; this class is best used in the builder pattern, for example
 * </p>
 * <pre>
 *     HttpClient client = new HttpClientConf()
 *         .proxy("127.0.0.1", 8080)
 *         .trafficDump(System.err::print)
 *         ...
 *         .newClient();
 * </pre>
 *
 * <h4>
 *     Categories of Config Properties
 * </h4>
 * <dl>
 *     <dt>TCP</dt>
 *     <dd>
 *         {@link #tunnels(bayou.tcp.TcpTunnel...) tunnels}
 *     </dd>
 *     <dt>SSL</dt>
 *     <dd>
 *         {@link #sslContext(javax.net.ssl.SSLContext) sslContext} ,
 *         {@link #sslEngineConf(bayou.util.function.ConsumerX) sslEngineConf}
 *     </dd>
 *     <dt>HTTP</dt>
 *     <dd>
 *         {@link #proxy(HttpProxy) proxy} ,
 *         {@link #requestHeader(String, String) requestHeader} ,
 *         {@link #autoDecompress(boolean) autoDecompress} ,
 *         {@link #autoRedirectMax(int) autoRedirectMax} ,
 *         {@link #cookieStorage(CookieStorage) cookieStorage} ,
 *         etc.
 *     </dd>
 *     <dt>Logging</dt>
 *     <dd>
 *         {@link #trafficDump(java.util.function.Consumer) trafficDump}
 *     </dd>
 *     <dt>Timeout</dt>
 *     <dd>
 *         {@link #keepAliveTimeout(java.time.Duration) keepAliveTimeout} , etc.
 *     </dd>
 *     <dt>Others</dt>
 *     <dd>
 *         misc properties, not commonly used
 *     </dd>
 * </dl> */
public final class HttpClientConf implements Cloneable
{

    /**
     * Create an HttpClientConf with default values.
     */
    public HttpClientConf()
    {

    }

    /**
     * Create an HttpClient with this configuration.
     * <p>
     *     Calling this method
     *     <code>`conf.newClient()`</code>
     *     is equivalent to calling
     *     {@link HttpClient#HttpClient(HttpClientConf) `new HttpClient(conf)`}.
     * </p>
     */
    public HttpClient newClient()
    {
        return new HttpClient(this);
    }


    /**
     * Return an independent copy of this object.
     */
    @Override
    public HttpClientConf clone()
    {
        HttpClientConf copy;
        try
        {
            copy = (HttpClientConf)super.clone();
        }
        catch (Exception e)
        {
            throw new AssertionError(e);
        }

        copy.requestHeaders = new HeaderMap(this.requestHeaders);

        return copy;
    }




    // for TcpChannel2Connection. not public yet; apps probably don't care
    final int readBufferSize = 16*1024;
    final int writeBufferSize = 16*1024;




    int[] selectorIds = _Tcp.defaultSelectorIds();
    /**
     * Ids of selectors for HttpClient.
     * <p><code>
     *     default: [0, 1, ... N-1] where N is the number of processors
     * </code></p>
     * <p>
     *     Conceptually there are infinite number of selectors, each associated with a dedicated thread.
     *     A client may choose to use any one or several selectors.
     *     Different servers/clients can share selectors or use different selectors.
     * </p>
     * @return `this`
     */
    public HttpClientConf selectorIds(int... selectorIds)
    {
        _Tcp.validate_confSelectorIds(selectorIds);
        this.selectorIds = selectorIds.clone();
        return this;
    }



    ConsumerX<SocketChannel> socketConf = socketChannel ->
    {
        socketChannel.socket().setTcpNoDelay(true);
    };
    /**
     * Action to configure each TCP socket.
     * <p><code>
     *     default action:
     *     enable {@link java.net.Socket#setTcpNoDelay(boolean) TCP_NODELAY}
     * </code></p>
     * <p>
     *     App may want to configure more options on each socket, for example
     * </p>
     * <pre>
     *     conf.socketConf( socketChannel -&gt;
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
    public HttpClientConf socketConf(ConsumerX<SocketChannel> action)
    {
        require(action != null, "action!=null");
        this.socketConf = action;
        return this;
    }





    SSLContext sslContext = null;
    /**
     * SSLContext for SSL connections.
     * <p><code>
     *     default: null
     * </code></p>
     * <p>
     *     If <code>null</code>, {@link SSLContext#getDefault()} is used.
     *     See <a href=
     *     "http://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html#CustomizingStores"
     *     >JSSE Guide</a>.
     * </p>
     * <p>
     *     See also {@link bayou.ssl.SslConf} for creating SSLContext.
     *     For example, to trust all server certificates (including self-signed ones)
     * </p>
     * <pre>
     *     conf.sslContext( new SslConf().trustAll().createContext() )
     * </pre>
     * @return `this`
     */
    public HttpClientConf sslContext(SSLContext sslContext)
    {
        this.sslContext = sslContext;
        return this;
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
     *   conf.sslEngineConf( engine-&gt;
     *   {
     *       engine.setEnabledCipherSuites(...);
     *   });
     * </pre>
     * @return `this`
     */
    public HttpClientConf sslEngineConf(ConsumerX<SSLEngine> action)
    {
        require(action != null, "action!=null");
        this.sslEngineConf = action;
        return this;
    }
    ConsumerX<SSLEngine> x_sslEngineConf()
    {
        // some default actions need to be done before user actions
        ConsumerX<SSLEngine> userActions = this.sslEngineConf;
        return engine->
        {
            SSLParameters sslParameters = engine.getSSLParameters();
            {
                // host name verification. see HostnameChecker
                // this should always be enabled. if user wants to disable it, set it to null in userActions
                sslParameters.setEndpointIdentificationAlgorithm("HTTPS");

                // SNI
                // in sun's impl, a name like "localhost" won't be used as SNI. see SSLEngineImpl.init()
                // we want to fix that; it would be nice for local testing.
                List<SNIServerName> snList = sslParameters.getServerNames();
                if(snList==null || snList.isEmpty())
                {
                    String host = engine.getPeerHost();
                    if(host!=null && _Dns.isValidDomain(host))
                        sslParameters.setServerNames(Collections.singletonList(new SNIHostName(host)));
                }
            }
            engine.setSSLParameters(sslParameters);

            userActions.accept(engine);
        };
    }




    TcpTunnel[] tunnels = {};

    /**
     * Tcp tunnels for every HTTP connection.
     * <p><code>
     *     default: none
     * </code></p>
     * <p>
     *     A chain of tunnels can be specified;
     *     every HTTP connection will tunnel through all of them in the order specified
     * </p>
     * <p>
     *     Example:
     * </p>
     * <pre>
     *   conf.tunnels(
     *       new Socks5Tunnel("localhost", 9876),
     *       new ConnectTunnel("some-proxy.com", 8080)
     *   );
     * </pre>
     * <p>
     *     Note that {@link ConnectTunnel} is commonly known as "HTTPS proxy",
     *     but in our API it is considered a tunnel instead of a proxy.
     * </p>
     *
     * @return `this`
     */
    public HttpClientConf tunnels(TcpTunnel... tunnels)
    {
        this.tunnels = tunnels.clone();
        return this;
    }





    HttpProxy proxy;
    /**
     * Http proxy.
     * <p><code>
     *     default: null
     * </code></p>
     * <p>
     *     If non-null, {@link HttpClient#send(HttpRequest) httpClient.send(request)}
     *     will forward the request to the proxy instead of the request host.
     * </p>
     * <p>
     *     See also {@link #proxy(String, int) proxy(host, port)}
     * </p>
     * <p>
     *     Note that this has nothing to do with "HTTP<b>S</b> proxy";
     *     see {@link #tunnels(bayou.tcp.TcpTunnel...) tunnels(...)} instead.
     * </p>
     * @return `this`
     *
     */
    // note: if request is https, and proxy!=null, we'll send absolute https://uri to proxy
    //       this is not end-to-end SSL.
    // if user needs both "normal" proxy and "https" proxy (i.e. tunnel), like how browsers do,
    //    create one client with proxy for http requests, and another client with tunnel for https requests.
    // within a client, tunnels and proxy are effective for all requests.
    public HttpClientConf proxy(HttpProxy proxy)
    {
        this.proxy = proxy;
        return this;
    }
    /**
     * Http proxy.
     * <p>
     *     This is a convenience method, equivalent to
     * </p>
     * <pre>
     *     {@link #proxy(HttpProxy) proxy}( new {@link HttpProxy#HttpProxy(String, int) HttpProxy}(host,port) )
     * </pre>
     * @return `this`
     */
    public HttpClientConf proxy(String host, int port)
    {
        return proxy(new HttpProxy(host, port));
    }




    HeaderMap requestHeaders = new HeaderMap();
    {
        requestHeader(Headers.Accept_Encoding, "gzip");
        requestHeader(Headers.User_Agent, "bayou.io");
    }
    /**
     * An an additional header for every outgoing request.
     * <p>
     *     HttpClient maintains a map of additional headers.
     *     {@link HttpClient#send(HttpRequest) send(request)}
     *     will add those headers to the request before sending it out.
     *     If a header already exists in the request, it will not be affected by the additional headers.
     * </p>
     * <p>
     *     The map of additional headers initially contains
     * </p>
     * <pre>
     *     Accept-Encoding: gzip
     *     User-Agent: bayou.io
     * </pre>
     * <p>
     *     Calling this method with <code>value=null</code> will remove the header from that map.
     * </p>
     * <p>
     *     Example usage:
     * </p>
     * <pre>
     *     new HttpClientConf()
     *         .requestHeader(Headers.User_Agent, null)
     *         .requestHeader(Headers.Cache_Control, "no-cache")
     * </pre>
     *
     * @return `this`
     */
    public HttpClientConf requestHeader(String name, String value)
    {
        if(value==null)
            requestHeaders.remove(name);
        else
        {
            _HttpUtil.checkHeader(name, value);
            requestHeaders.put(name, value);
        }
        return this;
    }



    // note: "realm" is not passed to func. we think it is not important/useful.
    Function<TcpAddress, Async<UserPass>> userPassSupplier = null;

    /**
     * Username/password supplier for server authentications.
     * <p><code>
     *     default: null (none)
     * </code></p>
     * <p>
     *     During {@link HttpClient#send(HttpRequest) httpClient.send(request)},
     *     if a <a href="http://tools.ietf.org/html/rfc7235#section-3.1">401 Unauthorized</a> response
     *     is received with <a href="http://tools.ietf.org/html/rfc2617">Basic/Digest challenges</a>,
     *     the `userPassSupplier` will be invoked with the server address to get the username/password;
     *     the request will then be retried with the authentication information.
     * </p>
     * <p>
     *     The userPassSupplier should return <code>(UserPass)null</code>
     *     if no username/password is available for the server.
     * </p>
     * <p>
     *     See also {@link #userPass(String, String, String) userPass(host, username, password)}
     * </p>
     * @return `this`
     */
    public HttpClientConf userPassSupplier(Function<TcpAddress, Async<UserPass>> userPassSupplier)
    {
        this.userPassSupplier = userPassSupplier;
        return this;
    }

    /**
     * Username/password for a single host.
     * <p>
     *     This is a convenience method equivalent to
     * </p>
     * <pre>
     *    {@link #userPassSupplier(Function) userPassSupplier}(address-&gt;
     *    {
     *        if(address.host().equalsIgnoreCase(host))
     *            return Async.success( new UserPass(username, password) );
     *        else
     *            return Async.success(null);
     *    });
     * </pre>
     * <p>
     *     (This method cannot be used repeatedly to specify username/passwords for multiple servers.)
     * </p>
     * @return `this`
     */
    public HttpClientConf userPass(String host, String username, String password)
    {
        String hostLo = host.toLowerCase();
        Async<UserPass> u_p = Async.success( new UserPass(username, password) );
        Async<UserPass> nul = Async.success(null);
        Function<TcpAddress, Async<UserPass>> userPassSupplier = address->
        {
            if(address.host().equals(hostLo))
                return u_p;
            else
                return nul;
        };

        return userPassSupplier(userPassSupplier);
    }








    // gzip only for now. in future, may handle other compression algorithms
    // app may want to disable this flag to work with gzip-ed data. use GunzipByteSource on the body is needed.
    boolean autoDecompress = true;

    /**
     * Whether to automatically decompress response bodies.
     * <p><code>
     *     default: true
     * </code></p>
     * <p>
     *     During {@link HttpClient#send(HttpRequest) httpClient.send(request)},
     *     if the response received from the server is compressed by "Content-Encoding: gzip",
     *     it will be automatically decompressed; the application sees a response entity with
     *     {@link bayou.http.HttpEntity#contentEncoding() contentEncoding}=null.
     *     Note that "ETag" is not transformed by this process, which can cause problems.
     * </p>
     * <p>
     *     Applications may want to set `autoDecompress(false)`
     *     if they prefer to receive compressed response bodies.
     *     {@link bayou.gzip.GunzipByteSource} can be used for manual decompression.
     * </p>
     * @return `this`
     */
    public HttpClientConf autoDecompress(boolean autoDecompress)
    {
        this.autoDecompress = autoDecompress;
        return this;
    }


    int autoRedirectMax = 10;
    /**
     * Max number of redirects that will be followed.
     * <p><code>
     *     default: 10
     * </code></p>
     * <p>
     *     During {@link HttpClient#send(HttpRequest) httpClient.send(request)},
     *     redirect responses will be automatically followed, up to the limitation of `autoRedirectMax`.
     * </p>
     * <p>
     *     If autoRedirectMax&lt;=0,
     *     redirect responses will not be automatically followed.
     * </p>
     * @return `this`
     */
    public HttpClientConf autoRedirectMax(int autoRedirectMax)
    {
        if(autoRedirectMax<0)
            autoRedirectMax=0;
        this.autoRedirectMax = autoRedirectMax;
        return this;
    }



    CookieStorage cookieStorage = CookieStorage.newInMemoryStorage();
    /**
     * Storage for cookies.
     * <p><code>
     *     default: {@link CookieStorage#newInMemoryStorage()}
     * </code></p>
     * <p>
     *     {@link HttpClient#send(HttpRequest) httpClient.send(request)}
     *     will use this storage to load cookies for requests
     *     and save cookies from responses.
     * </p>
     * <p>
     *     If cookieStorage is `null`, HttpClient does not handle cookies;
     *     application can manage cookies in requests/responses by itself.
     * </p>
     *
     * @return `this`
     */
    public HttpClientConf cookieStorage(CookieStorage cookieStorage)
    {
        this.cookieStorage = cookieStorage;
        return this;
    }






    Duration keepAliveTimeout = Duration.ofSeconds(15); // null if no keep-alive
    /**
     * Timeout for keep-alive connections.
     * <p><code>
     *     default: 15 seconds
     * </code></p>
     * <p>
     *     HttpClient will try to cache and reuse connections,
     *     `keepAliveTimeout` specifies how long connections could be cached.
     * </p>
     * <p>
     *     If `keepAliveTimeout` is null, 0, or negative,
     *     no connections will be cached;
     *     each connection is used for one request-response trip.
     * </p>
     * @return `this`
     */
    public HttpClientConf keepAliveTimeout(Duration keepAliveTimeout)
    {
        keepAliveTimeout = positiveOrNull(keepAliveTimeout);
        this.keepAliveTimeout = keepAliveTimeout;
        return this;
    }
    static Duration positiveOrNull(Duration duration)
    {
        if(duration==null)
            return null;
        if(duration.isZero())
            return null;
        if(duration.isNegative())
            return null;

        return duration; // positive
    }


    Duration await100Timeout = Duration.ofSeconds(1); // null if don't wait for 100 response

    /**
     * Timeout for awaiting "100 Continue" responses.
     * <p><code>
     *     default: 1 second
     * </code></p>
     * <p>
     *     If a request contains header
     *     <a href="http://tools.ietf.org/html/rfc7231#section-5.1.1">"Expect: 100-continue"</a>,
     *     HttpClient will delay sending the request body until a
     *     <a href="http://tools.ietf.org/html/rfc7231#section-6.2.1">"100 Continue"</a> response
     *     is received, or `await100Timeout` is reached.
     * </p>
     * <p>
     *     If `await100Timeout` is null, 0, or negative,
     *     request body will always be sent immediately.
     * </p>
     * @return `this`
     */
    public HttpClientConf await100Timeout(Duration await100Timeout)
    {
        await100Timeout = positiveOrNull(await100Timeout);
        this.await100Timeout = await100Timeout;
        return this;
    }



    int responseHeadFieldMaxLength = 8*1024;
    /**
     * Max length for any header value in a response.
     * <p><code>
     *     default: 8*1024 (8KB)
     * </code></p>
     * <p>
     *     See also {@link #responseHeadTotalMaxLength(int) responseHeadTotalMaxLength}.
     * </p>
     * @return `this`
     */
    public HttpClientConf responseHeadFieldMaxLength(int responseHeadFieldMaxLength)
    {
        require(responseHeadFieldMaxLength > 0, "responseHeadFieldMaxLength>0");
        this.responseHeadFieldMaxLength = responseHeadFieldMaxLength;
        return this;
    }

    int responseHeadTotalMaxLength = 32*1024;
    /**
     * Max length of a response head.
     * <p><code>
     *     default: 32*1024 (32KB)
     * </code></p>
     * @return `this`
     */
    public HttpClientConf responseHeadTotalMaxLength(int responseHeadTotalMaxLength)
    {
        require(responseHeadTotalMaxLength > 0, "responseHeadTotalMaxLength>0");
        this.responseHeadTotalMaxLength = responseHeadTotalMaxLength;
        return this;
    }


    private Consumer<CharSequence> trafficDump;
    _TrafficDumpWrapper trafficDumpWrapper;
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
     *     trafficDump( System.out::print );
     * </pre>
     *
     * @return `this`
     */
    public HttpClientConf trafficDump(Consumer<CharSequence> trafficDump)
    {
        this.trafficDump = trafficDump;

        trafficDumpWrapper = trafficDump ==null? null : new _TrafficDumpWrapper("Http Client", trafficDump);

        return this;
    }


    // =========================================================================================================


    public int[] get_selectorIds()
    {
        return selectorIds.clone();
    }
    public ConsumerX<SocketChannel> get_socketConf()
    {
        return socketConf;
    }
    public SSLContext get_sslContext()
    {
        return sslContext;
    }
    public ConsumerX<SSLEngine> get_sslEngineConf()
    {
        return sslEngineConf;
    }
    public TcpTunnel[] get_tunnels()
    {
        return tunnels.clone();
    }
    public HttpProxy get_proxy()
    {
        return proxy;
    }
    public HeaderMap get_requestHeaders()
    {
        HeaderMap copy = new HeaderMap(requestHeaders);
        copy.freeze();
        return copy;
    }
    public Function<TcpAddress, Async<UserPass>> get_userPassSupplier()
    {
        return userPassSupplier;
    }
    public boolean get_autoDecompress()
    {
        return autoDecompress;
    }
    public int get_autoRedirectMax()
    {
        return autoRedirectMax;
    }
    public CookieStorage get_cookieStorage()
    {
        return cookieStorage;
    }
    public Duration get_keepAliveTimeout()
    {
        return keepAliveTimeout;
    }
    public Duration get_await100Timeout()
    {
        return await100Timeout;
    }
    public int get_responseHeadFieldMaxLength()
    {
        return responseHeadFieldMaxLength;
    }
    public int get_responseHeadTotalMaxLength()
    {
        return responseHeadTotalMaxLength;
    }
    public Consumer<CharSequence> get_trafficDump()
    {
        return trafficDump;
    }


}
