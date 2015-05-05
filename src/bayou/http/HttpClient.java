package bayou.http;

import _bayou._async._Asyncs;
import _bayou._http._HttpHostPort;
import _bayou._http._HttpUtil;
import bayou.async.Async;
import bayou.async.AsyncIterator;
import bayou.async.Promise;
import bayou.bytes.ByteSource;
import bayou.gzip.GunzipByteSource;
import bayou.mime.HeaderMap;
import bayou.mime.Headers;
import bayou.tcp.TcpAddress;
import bayou.tcp.TcpClient;
import bayou.util.Result;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static bayou.http.HttpClientConnMan.ConnHolder;

/**
 * Http client.
 * <p>
 *     HttpClient is used to send requests to servers and receive responses. For example
 * </p>
 * <pre>
 *     HttpClient client = new HttpClient();
 *
 *     HttpRequest request = HttpRequest.toGet("https://example.com");
 *     Async&lt;HttpResponse&gt; asyncRes = client.send( request );
 *
 *     // or more conveniently
 *     Async&lt;HttpResponse&gt; asyncRes = client.doGet( "https://example.com" );
 * </pre>
 * <p>
 *     Configuration is done by {@link bayou.http.HttpClientConf}, for example
 * </p>
 * <pre>
 *     HttpClient client = new HttpClientConf()
 *         .proxy("127.0.0.1", 8080)
 *         .trafficDump(System.err::print)
 *         ...
 *         .newClient();
 * </pre>
 * <p>
 *     An HttpClient should be {@link #close() closed} eventually.
 * </p>
 */
public class HttpClient
{
    HttpClientConf conf;

    HttpProxy proxy;

    ArrayList<TcpClient> tcpClients;
    AtomicInteger tcpClientX = new AtomicInteger(0);
    HttpClientConnMan connMan;

    HttpHandler handler;

    /**
     * Create an HttpClient with default configurations.
     * <p>
     *     Equivalent to {@link #HttpClient(HttpClientConf) new HttpClient( new HttpClientConf() ) }.
     * </p>
     */
    public HttpClient()
    {
        this(new HttpClientConf(), false);
    }

    /**
     * Create an HttpClient with the configuration.
     * <p>
     *     A copy of `conf` is created and used by this client;
     *     further changes to `conf` will not affect this client.
     * </p>
     * @see bayou.http.HttpClientConf#newClient() conf.newClient()
     */
    public HttpClient(HttpClientConf conf)
    {
        this(conf, true);
    }
    HttpClient(HttpClientConf conf, boolean cloneConf)
    {
        if(cloneConf)
            conf = conf.clone();
        this.conf = conf;

        this.proxy = conf.proxy;

        try
        {
            tcpClients = new ArrayList<>(conf.selectorIds.length);
            for(int selectorId : conf.selectorIds)
            {
                TcpClient.Conf tConf = new TcpClient.Conf();
                tConf.selectorId = selectorId;
                tConf.socketConf = conf.socketConf;
                TcpClient tcpClient = new TcpClient(tConf); // throws, unlikely
                tcpClients.add(tcpClient);
            }

            connMan = new HttpClientConnMan(this);
        }
        catch (Exception e) // unlikely
        {
            throw new RuntimeException(e);
        }


        handler = request->send0(request, null);
        {
            // interceptors

            if(conf.cookieStorage!=null)
                handler = new CookieHandler(handler, conf.cookieStorage);

            if(proxy!=null && proxy.userPassSupplier()!=null)
                handler = new AuthHandler(handler, proxy.address(), proxy.userPassSupplier());

            if(conf.userPassSupplier!=null)
                handler = new AuthHandler(handler, conf.userPassSupplier);

            if (conf.autoRedirectMax > 0)
                handler = new RedirectHandler(handler, conf.autoRedirectMax);

            // send(req) may involve multiple requests/responses
            // we don't make best effort to arrange them on the same connection
            // that is possible, but it makes code less compose-able.
            // ideally, that should not matter anyway.
            // however, some users may expect it, some apps may even require it.
            // in the case, create a dedicated HttpClient (with single selector).
            // HttpClient should be light weight.

            // see also drain(response)
        }
        handler = new ModHandler(handler, conf);

    }


    /**
     * Send a GET request.
     * <p>
     *     This is a convenience method, equivalent to
     * </p>
     * <pre>
     *     {@link #send(HttpRequest) send}( HttpRequest.{@link HttpRequest#toGet(String) toGet}(absoluteUri) )
     * </pre>
     * <p>
     *     Example usage:
     * </p>
     * <pre>
     *     Async&lt;HttpResponse&gt; asyncRes = client.doGet("https://example.com");
     * </pre>
     */
    public Async<HttpResponse> doGet(String absoluteUri)
    {
        return send(HttpRequest.toGet(absoluteUri));
    }


    // chunked: many servers do not support chunked request body.
    // rfc: client must not do chunked unless it knows the server supports HTTP/1.1
    // request entity contentLength should be known. otherwise we will send chunked body,
    // which will likely be rejected by the server, and we'll receive an error response.


    /**
     * Send a request and wait for the final response.
     * <p>
     *     Requests/responses may be modified/transformed to handle cookies, decompression, etc.
     * </p>
     * <p>
     *     Multiple request-response trips may occur during <code>send(request)</code>
     *     to handle redirection, authentication, etc.
     * </p>
     * <p>
     *     If the request could be re-sent in this process, e.g. due to authentication challenge,
     *     its entity should be <a href="HttpEntity.html#sharable">sharable</a>.
     * </p>
     * <p>
     *     See also {@link #send0(HttpRequest, bayou.tcp.TcpAddress) send0()}.
     *     This method eventually calls <code>send0()</code>,
     *     possibly multiple times.
     * </p>
     * <p id=send-notes>
     *     <b>Notes:</b> (for both send() and send0() methods)
     * </p>
     * <ul>
     *     <li><p>
     *         The response will tie up an underlying connection
     *         if response.{@link HttpResponse#entity() entity}!=null;
     *         the caller <b>MUST</b> eventually close() the entity body to free the connection.
     *         Note that convenience methods
     *         {@link HttpResponse#bodyBytes(int) response.bodyBytes()} and
     *         {@link HttpResponse#bodyString(int) response.bodyString()}
     *         will close the response entity body.
     *     </p></li>
     *     <li><p>
     *         The response entity is not <a href="HttpEntity.html#sharable">sharable</a>;
     *         its body can be read only once.
     *     </p></li>
     *     <li><p>
     *         Intermediary 1xx responses will be handled internally;
     *         the response returned by send() or send0() has a non-1xx status.
     *     </p></li>
     *     <li><p>
     *         If the request contains a body (e.g. a POST request),
     *         the request entity should has a known {@link bayou.http.HttpEntity#contentLength() contentLength}.
     *         Otherwise, the request body will be sent in "Transfer-Encoding: chunked"
     *         which is not supported by many servers.
     *     </p></li>
     *     <li><p>
     *         "CONNECT" requests are not supported; the behavior would be undefined.
     *         <!-- it's not forbidden; if it's certain that the response is non-2xx, send(req) works fine. -->
     *     </p></li>
     * </ul>
     */
    public Async<HttpResponse> send(HttpRequest request)
    {
        return handler.handle(request);
    }



    /**
     * Send a request to `dest`.
     * <p>
     *     If `dest` is null
     * </p>
     * <ul>
     *     <li>
     *         If {@link HttpClientConf#proxy(HttpProxy) proxy} is configured,
     *         the request will be sent to the proxy
     *     </li>
     *     <li>
     *         Otherwise, the request will be sent to request.{@link bayou.http.HttpRequest#host() host()}
     *     </li>
     * </ul>
     * <p>
     *     This is a lower level API that sends the request and receives the response
     *     <i>as-is</i>, without any automatic handling of redirection, decompression, etc.
     * </p>
     * <p>
     *     The connection will go through {@link HttpClientConf#tunnels(bayou.tcp.TcpTunnel...) tunnles}
     *     if they are configured for this client.
     * </p>
     * <p>
     *     See also <a href="#send-notes"><b>Notes</b></a> in {@link #send(HttpRequest) send()};
     *     they apply to this method too.
     * </p>
     */
    public Async<HttpResponse> send0(HttpRequest request, TcpAddress dest)
    {
        if(dest!=null)
            return send1(request, dest, false);

        if(proxy!=null)
            return send1(request, proxy.address(), /*absUri*/true);

        // request.host -> dest
        dest = getDest(request);
        if(dest==null)
            return Async.failure(new Exception("invalid request Host: "+request.host()));
        return send1(request, dest, false);
    }


    /**
     * Create a new HTTP connection to `dest` address.
     * <p>
     *     If `dest` is null, a {@link HttpClientConf#proxy(HttpProxy) proxy} must be configured,
     *     and the connection will connect to the proxy address.
     * </p>
     * <p>
     *     The connection will go through {@link HttpClientConf#tunnels(bayou.tcp.TcpTunnel...) tunnles}
     *     if they are configured for this client.
     * </p>
     * <p>
     *     This is a very low level API, see {@link bayou.http.HttpClientConnection}.
     *     The caller is responsible for the life cycle of the connection.
     * </p>
     */
    public Async<HttpClientConnection> newConnection(TcpAddress dest)
    {
        if(dest!=null)
            return newConnection(dest, false);

        if(proxy!=null)
            return newConnection(proxy.address(), true);

        throw new IllegalArgumentException("dest==null && proxy==null");
    }


    /**
     * Get executors associated with selector threads.
     * <p>
     *     Return one executor for every selector thread; see {@link bayou.http.HttpClientConf#selectorIds(int...)}.
     * </p>
     * <p>
     *     The executor can be used to execute non-blocking tasks on the selector thread;
     *     tasks will be executed sequentially in the order they are submitted.
     * </p>
     */
    public List<Executor> getExecutors()
    {
        ArrayList<Executor> list = new ArrayList<>();
        for(TcpClient tcpClient : tcpClients)
            list.add(tcpClient.getExecutor());
        return list;
    }


    /**
     * Close this client and free resources.
     * <p>
     *     All connections will be forcefully closed,
     *     including those created by {@link #newConnection(bayou.tcp.TcpAddress) newConnection()}.
     * </p>
     * <p>
     *     All outstanding requests and responses will be aborted.
     * </p>
     */
    public Async<Void> close()
    {
        // we simply brutely close all tcp connections. this may cause problems.
        // ideally we should call close() on each http connection, which is more graceful.
        // at this point we don't think it's a big problem; revisit it later.

        for(TcpClient tc : tcpClients)
            tc.close();

        return Async.VOID;
    }






    TcpClient getCurrTcpClient()
    {
        for(TcpClient tc : tcpClients)
            if (tc.getExecutor() == Thread.currentThread())
                return tc;
        return null;
    }
    TcpClient getRandomTcpClient()
    {
        // it would be nice to select the client with the least connections.
        // however, the connection count is not real-time enough, due to async.
        // do round robin now.
        int x = tcpClientX.getAndIncrement();
        return tcpClients.get(x%tcpClients.size());
    }
    static <T> Async<T> execOn(Executor executor, Supplier<Async<T>> action)
    {
        Promise<T> promise = new Promise<>();
        executor.execute(() ->
        {
            Async<T> asyncT = action.get();
            asyncT.onCompletion(promise::complete);
            promise.onCancel(asyncT::cancel);
        });
        return promise;
    }


    Async<HttpClientConnection> newConnection(TcpAddress dest, boolean sendAbsUri)
    {
        TcpClient tcpClient = getCurrTcpClient();
        if(tcpClient!=null)
            return connMan.newConn(dest, tcpClient, sendAbsUri);

        TcpClient tcpClientR = getRandomTcpClient();
        return execOn(tcpClientR.getExecutor(), () ->
                connMan.newConn(dest, tcpClientR, sendAbsUri)
        );
    }



    static TcpAddress getDest(HttpRequest request)
    {
        _HttpHostPort hp = _HttpHostPort.parse(request.host());
        if(hp==null)
            return null;
        boolean ssl = request.isHttps();
        String host = hp.hostString();
        int port = hp.port!=-1? hp.port : (ssl?443:80);
        return new TcpAddress(ssl, host, port);
    }
    Async<HttpResponse> send1(HttpRequest request, TcpAddress dest, boolean sendAbsUri)
    {
        // get a conn for dest
        // do the rest of work (send2) on conn's selector thread

        ConnHolder connHolder = connMan.checkOut(dest); // get a cached conn, prefer one of the current thread
        if(connHolder!=null)
        {
            assert connHolder.conn.outbound.sendAbsoluteUri == sendAbsUri;

            if(connHolder.executor()==Thread.currentThread())
                return connHolder.checkOutPromise.then(conn->send2(conn, request));
            else // steal conn from another selector thread (curr thread may not be a selector thread)
                return execOn(connHolder.executor(), () ->
                    connHolder.checkOutPromise.then(conn->send2(conn, request)));
        }

        // no cached conn for dest
        TcpClient tcpClient = getCurrTcpClient();
        if(tcpClient!=null)
            return connMan.newConn(dest, tcpClient, sendAbsUri).then(conn->send2(conn, request));

        // curr thread is not a selector thread
        TcpClient tcpClientR = getRandomTcpClient();
        return execOn(tcpClientR.getExecutor(), () ->
            connMan.newConn(dest, tcpClientR, sendAbsUri).then(conn -> send2(conn, request)));
    }
    // on conn's selector thread
    Async<HttpResponse> send2(HttpClientConnection conn, HttpRequest request)
    {
        // send req and receive res, concurrently.
        // note: it would be wrong to do `send(req).then(::receive)`
        //       receive() can succeed even if send() fails, e.g. negative case of 100-continue
        // status of sendReq is not visible to caller

        Async<Void> sendReq = conn.send(request);

        return sendR(conn, sendReq);
        // cancellation will be forwarded to receive(), leading to conn.close()
    }
    Async<HttpResponse> sendR(HttpClientConnection conn, Async<Void> sendReq)
    {
        Async<HttpResponse> receiveResponse = conn.receive0();
        return receiveResponse.transform(receiveRes -> sendR2(conn, sendReq, receiveRes));
    }
    Async<HttpResponse> sendR2(HttpClientConnection conn, Async<Void> sendReq, Result<HttpResponse> receiveRes)
    {
        if(receiveRes.isFailure())
        {
            conn.close();
            return receiveRes;
        }

        HttpResponse response = receiveRes.getValue();

        if (response.statusCode() / 100 == 1) // intermediary 1xx response, ignore
            return sendR(conn, sendReq);  // tail recursive

        // give the response to the user.
        // the connection is occupied by the response, till user reads the body to EOF or close the body.
        // upon observing that, we'll free the connection (close it or cache it)
        HttpResponseImpl res = (HttpResponseImpl) response;
        ImplHttpEntity entity = (ImplHttpEntity) (res.entity);
        Async<Void> awaitResponseEof = entity != null ? entity.body.awaitEof : Async.VOID;
        _Asyncs.onCompletion(
            awaitResponseEof,
            Runnable::run,  // freeConn is invoked asap, so that it's visible to next checkOut
            r -> freeConn(conn, sendReq, r)
        );

        return receiveRes;
    }
    void freeConn(HttpClientConnection conn, Async<Void> sendReq, Result<Void> drainRes)
    {
        if(reuseConn(conn, sendReq, drainRes))
        {
            connMan.checkIn(conn);
            // usually, we observe response eof and checkIn conn before user observes response eof,
            // a subsequent send() has a good chance to see the same conn from checkOut(), which is nice.
        }
        else
        {
            conn.close();
        }
    }
    // check: request is sent, response is drained. i.e. conn is clear for reuse.
    boolean reuseConn(HttpClientConnection conn, Async<Void> sendReq, Result<Void> drainRes)
    {
        if(!connMan.canReuse(conn))
            return false;

        if(drainRes.isFailure())
            return false;

        Result<?> sendReqR = sendReq.pollResult();
        return sendReqR!=null && sendReqR.isSuccess();
        // if request is not completely sent by the time of response body EOF,
        // the server has sent us the whole response without reading the full request body.
        // it cannot rely on receiving the rest of the request thru unreliable network,
        // therefore, we can argue that it is not too interested in the request body,
        // therefore, we are justified to abandon the request, instead of trying to complete it.
        //
        // this should be a very rare case anyway; it probably doesn't occur in practice.
        // (actually it's almost certain that request is complete before we even see response *head*)
        // if a user raises a legit reason requiring us to complete the request in this case,
        // we can add a step that awaits sendReq completion. not doing that now, mainly for being lazy/cheap.
    }



    static Async<Void> drain(HttpResponse response)
    {
        // if an interceptor handler needs to send a new request, it should drain the current response
        // to free the connection, the response body should be small and ready to read.
        // we wait for draining to complete before sending the new request, a common/reasonably practice.
        // it's ok if the draining fails, the new request should still be sent.
        //  - use drain().transform() instead of drain().then()

        HttpEntity entity = response.entity();
        if(entity==null)
            return Async.VOID;

        return AsyncIterator
            .forEach(entity.body()::read, bb -> {})
            .timeout(Duration.ofSeconds(1)); // in case
    }
    static void closeRes(HttpResponse response)
    {
        HttpEntity entity = response.entity();
        if(entity==null)
            return;

        entity.body().close();
    }


    //========================================================================================================

    // mod request/response at the top layer
    static class ModHandler implements HttpHandler
    {
        HttpHandler h0;

        HeaderMap extraHeaders;
        boolean autoDecompress;
        boolean closeConn;

        ModHandler(HttpHandler h0, HttpClientConf conf)
        {
            this.h0 = h0;

            this.extraHeaders = conf.requestHeaders;
            this.autoDecompress = conf.autoDecompress;
            this.closeConn = (conf.keepAliveTimeout==null);
        }

        @Override
        public Async<HttpResponse> handle(HttpRequest request)
        {
            return h0
                .handle(modRequest(request))
                .map(this::modResponse);
        }

        HttpRequest modRequest(HttpRequest request0)
        {
            HttpRequestImpl request = new HttpRequestImpl(request0);

            for(Map.Entry<String,String> kv : extraHeaders.entrySet() )
            {
                String k = kv.getKey();
                if(request.header(k)==null)
                    request.header(k, kv.getValue());
            }

            modConnectionHeader(request);

            return request;
        }
        void modConnectionHeader(HttpRequestImpl request)
        {
            String hvConnection0 = request.header(Headers.Connection); // usually null
            if(_HttpUtil.containsToken(hvConnection0, "close")) // respect that
                return;

            // explicitly set Connection: close|keep-alive, depending on whether we do keep-alive.
            // it doesn't matter if request is 1.0 or 1.1; however, if request is 1.0 (for whatever reason)
            // the server can't keep connection alive if response Content-Length is unknown.
            String hvConnectionM = _HttpUtil.modConnectionHeader(hvConnection0, closeConn);
            // possible: user specified it as "keep-alive" and we need to change it to "close"
            request.header(Headers.Connection, hvConnectionM);
        }


        HttpResponse modResponse(HttpResponse response0)
        {
            if(autoDecompress)
            {
                HttpEntity entity = response0.entity();
                if(entity!=null && "gzip".equalsIgnoreCase(entity.contentEncoding()))
                {
                    HttpResponseImpl responseM = new HttpResponseImpl(response0);
                    responseM.entity(new GunzipEntity(entity));
                    // remove headers that may cause confusions.
                    // apps are not supposed to read them from general headers.
                    responseM.header(Headers.Content_Encoding, null);
                    responseM.header(Headers.Content_Length, null);
                    return responseM; // the only mod at this point
                }
            }

            return response0;
        }

    }

    static class GunzipEntity implements HttpEntityWrapper
    {
        HttpEntity origin;

        GunzipEntity(HttpEntity origin)
        {
            this.origin = origin;
        }

        @Override
        public ByteSource body()
        {
            return new GunzipByteSource(origin.body());
        }

        @Override
        public HttpEntity getOriginEntity()
        {
            return origin;
        }
        @Override
        public Long contentLength()
        {
            return null; // don't know the length after compression
        }
        @Override
        public String contentEncoding()
        {
            return null; // "gzip"->null
        }
        // note that ETag is not transformed, which might be a problem.
    }





}
