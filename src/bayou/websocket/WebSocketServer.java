package bayou.websocket;

import _bayou._log._Logger;
import _bayou._http._HttpUtil;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.http.*;
import bayou.tcp.TcpConnection;
import bayou.util.Result;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;

import static _bayou._tmp._Util.require;

/**
 * WebSocket server. See <a href="http://tools.ietf.org/html/rfc6455">RFC6455</a>.
 * <p>
 *     A WebSocketServer contains a {@link bayou.websocket.WebSocketHandler} to handle WebSocket handshakes.
 * </p>
 * <p>
 *     A WebSocketServer must be attached to an {@link HttpServer}; they share the same life cycle.
 * </p>
 */

// Safari 5 - supports only older/obsolete version of WebSocket.
// Safari 6 - supports the latest spec. however, the browser is not available on many OS

public class WebSocketServer
{
    final WebSocketHandler handler;

    /**
     * Create a WebSocketServer, attach it to the HttpServer.
     * <p>
     *     This constructor must be called before the HttpServer is started.
     * </p>
     */
    public WebSocketServer(HttpServer httpServer, WebSocketHandler handler)
    {
        require(httpServer != null, "httpServer!=null");
        require(handler != null, "handler!=null");

        this.handler = handler;

        assert httpServer != null; // Intellij needs it
        httpServer.addUpgrader("WebSocket", new HttpUpgrader()
        {
            @Override
            public void init(HttpServerConf httpServerConf) throws Exception
            {
                _init(httpServerConf);
            }
            @Override
            public Async<HttpResponse> tryUpgrade(HttpRequest httpRequest, TcpConnection tcpConn)
            {
                return _tryUpgrade(httpRequest, tcpConn);
            }
        });
    }

    final WebSocketServerConf conf = new WebSocketServerConf();

    /**
     * Get the {@link WebSocketServerConf} for this server.
     * <p>
     *     Changes to configuration must be done before the server is started.
     * </p>
     */
    public WebSocketServerConf conf()
    {
        return conf;
    }

    void _init(HttpServerConf httpConf) throws Exception
    {
        // we can't copy httpConf in WebSocketServer(); httpConf may change after that.
        conf.copy(httpConf);

        conf.freeze(); // throws

        initHotHandler();
    }

    void initHotHandler()
    {
        if(handler instanceof HotWebSocketHandler)
        {
            HotWebSocketHandler hot = (HotWebSocketHandler)handler;
            try
            {
                hot.reloader().getAppInstance(hot.getAppHandlerClassName());  // compile java files, etc.
            }
            catch (Exception e) // not crippling; can be recovered
            {
                _Util.printStackTrace(e, hot.reloader().getMessageOut());
            }
        }
    }


    // if result is null, upgrade is success, connection is taken over by this WebSocket server.
    //     request is a valid WebSocket handshake, and server agrees to open the WebSocket connection
    //     request scope is handled by this server
    // if result is non-null response, upgrade fails, connection is still http
    // if result is exception, treat it as internal error
    Async<HttpResponse> _tryUpgrade(HttpRequest httpRequest, final TcpConnection tcpConn)
    {
        String hvSecWebSocketVersion = httpRequest.headers().get("Sec-WebSocket-Version");
        if(!"13".equals(hvSecWebSocketVersion))
            return HttpResponse.text(400, "expect Sec-WebSocket-Version: 13")
                .header("Sec-WebSocket-Version", "13");


        final WebSocketRequest wsRequest;
        try
        {
            wsRequest = new WebSocketRequest(httpRequest);  // validate various fields
            // note: will reject if request contains body
        }
        catch (IllegalArgumentException e) // rare
        {
            return HttpResponse.text(400, e.getMessage());
        }

        if(conf.enforceSameOrigin)
        {
            String origin = wsRequest.origin();
            if(origin==null || origin.isEmpty())
            {
                return HttpResponse.text(403, "Origin header is missing");
                // we reject non-browser clients that don't send Origin header. app may not like that.
                //   solution 1: ask clients to send Origin that matches Host
                //   solution 2: disable confEnforceSameOrigin, check Origin in app
            }
            else
            {
                String host = wsRequest.hostPort();
                if(!_HttpUtil.matchHost(host, origin))
                    return HttpResponse.text(403, "Origin does not match Host");
                // else same origin
            }
        }

        Async<WebSocketResponse> asyncWsResp;
        try
        {
            asyncWsResp = handler.handle(wsRequest); // should not throw
            if(asyncWsResp==null)
                throw new NullPointerException("null returned from "+ handler);
        }
        catch (RuntimeException e)
        {
            asyncWsResp = Result.failure(e);
        }

        return asyncWsResp.map(wsResp -> {
            if (wsResp instanceof WebSocketResponse.Reject)
                return ((WebSocketResponse.Reject) wsResp).getHttpResponse(); // still http conn

            takeOver((WebSocketResponse.Accept) wsResp, tcpConn, wsRequest);
            return (HttpResponse)null;
        });
    }

    void takeOver(WebSocketResponse.Accept wsResp, TcpConnection tcpConn, WebSocketRequest wsReq)
    {
        // get cookies in CookieJar
        HttpRequest httpRequest = wsReq.httpRequest();
        Collection<Cookie> cookies = CookieJar.getAllChanges(); // handler may updated cookies
        // CookieJar.clearAll(); // don't. http server will end the current fiber soon anyway.
        ArrayList<String> setCookieStrings = new ArrayList<>(cookies.size());
        for(Cookie cookie : cookies)
            setCookieStrings.add(cookie.toSetCookieString());

        String challengeKey = httpRequest.headers().get("Sec-WebSocket-Key");
        String challengeResp = WebSocketResponse.challengeResponse(challengeKey);

        // the subprotocol selected by app should be one of those supplied in open request.
        // we don't check that. if app sends a wrong subprotocol, client will fail the connection.

        ByteBuffer handshakeResponse = WebSocketResponse.genHandshakeResponse
            (challengeResp, wsResp.getSubprotocol(), setCookieStrings);

        WebSocketChannelImpl webSocket = new WebSocketChannelImpl(this, tcpConn, handshakeResponse);
        webSocket.start(wsResp.getChannelHandler());
    }

    static final _Logger logger = _Logger.of(WebSocketServer.class);

    static void logUnexpected(Throwable t)
    {
        logger.error("Unexpected error: %s", t);
    }
    static void logErrorOrDebug(Throwable error)  // depends on if exception is checked
    {
        _Util.logErrorOrDebug(logger, error);
    }

}
