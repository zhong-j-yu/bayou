package bayou.websocket;

import _bayou._tmp._HttpHostPort;
import _bayou._tmp._HttpUtil;
import _bayou._tmp._Array2ReadOnlyList;
import _bayou._tmp._StrUtil;
import bayou.http.HttpRequest;
import bayou.http.HttpRequestImpl;
import bayou.mime.Headers;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * WebSocket handshake request.
 * <p>
 *     A handshake request contains data like host, port, resource name.
 *     See <a href="http://tools.ietf.org/html/rfc6455#section-4.1">RFC6455 &sect;4.1</a>.
 * </p>
 * <p>
 *     A WebSocket request is embedded in an HTTP request,
 *     therefore a <code>WebSocketRequest</code> is associated with an {@link HttpRequest}.
 * </p>
 */

// a simple read only class, not an interface
//    compared to HttpRequest: a read only HttpRequest interface + a builder class HttpRequestImpl

public class WebSocketRequest
{
    final InetAddress ip;
    final boolean secure;
    final String hostPort; // host[:port]
    final String resourceName; // in the form abs-path[?query]. validated.

    final String origin; // can be null
    final List<String> subprotocols;  // readonly. 0 or a few. no big deal to call List.contains()

    volatile HttpRequest httpRequest_volatile;

    /**
     * The IP address of the client.
     */
    public InetAddress ip()
    {
        return ip;
    }

    /**
     * The host[:port] of the server.
     * See {@link bayou.http.HttpRequest#host()}.
     * <p>
     *     Example values: <code>"localhost:8080", "example.com"</code>.
     * </p>
     */
    public String hostPort()
    {
        return hostPort;
    }

    /**
     * The resource name.
     * <p>
     *     Example values: <code>"/", "/chat"</code>.
     * </p>
     */
    public String resourceName()
    {
        return resourceName;
    }

    /**
     * Whether the connection is secure.
     */
    public boolean secure()
    {
        return secure;
    }

    /**
     * The subprotocols.
     */
    public List<String> subprotocols()
    {
        return subprotocols;
    }

    /**
     * The origin.
     * <p>
     *     Example values: <code>null, "http://localhost:8080", "https://example.com"</code>.
     * </p>
     */
    public String origin()
    {
        return origin;
    }

    /**
     * Create a WebSocketRequest from a WebSocket URI.
     * <p>
     *     The created WebSocketRequest will have the same origin, and no subprotocols.
     * </p>
     * <p>
     *     Example Usage:
     * </p>
     * <pre>
     *     new WebSocketRequest("ws://localhost:8080");
     *     new WebSocketRequest("wss://example.com/chat");
     * </pre>
     */
    public WebSocketRequest(String uri)
    {
        this.ip = InetAddress.getLoopbackAddress();

        String uriLo = uri.toLowerCase();
        if(uriLo.startsWith("ws://"))
            secure = false;
        else if(uriLo.startsWith("wss://"))
            secure = true;
        else
            throw new IllegalArgumentException("Invalid WebSocket URI: "+uri);

        int iHost = secure ? 6 : 5;
        String hostPort;
        int iSlash = uri.indexOf('/', iHost);
        int iQuest = uri.indexOf('?', iHost);
        if(iSlash==-1&&iQuest==-1) //  ws://abc.com
        {
            hostPort = uriLo.substring(iHost);
            resourceName = "/";
        }
        else if(iSlash!=-1 &&(iQuest==-1 || iQuest>iSlash))  // ws://abc.com/foo,  ws://abc.com/foo?bar
        {
            hostPort = uriLo.substring(iHost, iSlash);
            resourceName = uri.substring(iSlash);
        }
        else   //  ws://abc.com?foo ,  ws://abc.com?foo/bar   they are valid according to RFC6455
        {
            hostPort = uriLo.substring(iHost, iQuest);
            resourceName = "/" + uri.substring(iQuest);
        }

        _HttpHostPort hp = _HttpHostPort.parse(hostPort);
        if(hp==null)
            throw new IllegalArgumentException("Invalid WebSocket URI: "+uri);
        this.hostPort = hp.toString(secure? 443: 80);

        if(!_HttpUtil.isOriginFormUri(resourceName))
            throw new IllegalArgumentException("Invalid WebSocket URI: "+uri);

        // same origin
        this.origin = (secure? "https://": "http://") + this.hostPort;
        this.subprotocols = Collections.emptyList();

        // origin and subprotocols are fixed. we may need another constructor that accept them as arguments
    }


    /**
     * Create a WebSocketRequest.
     * <p>
     *     Example usage:
     * </p>
     * <pre>
     *     new WebSocketRequest(false, "localhost:8080", "/", null);
     *     new WebSocketRequest(true, "example.com", "/chat", "https://example.com");
     * </pre>
     */
    public WebSocketRequest(boolean secure, String hostPort, String resourceName, String origin, String... subprotocols)
    {
        this.ip = InetAddress.getLoopbackAddress();
        this.secure = secure;

        _HttpHostPort hp = _HttpHostPort.parse(hostPort);
        if(hp==null)
            throw new IllegalArgumentException("Invalid hostPort: "+hostPort);
        this.hostPort = hp.toString(secure? 443: 80);

        if(resourceName==null || !_HttpUtil.isOriginFormUri(resourceName))
            throw new IllegalArgumentException("illegal resourceName: "+resourceName);
        this.resourceName = resourceName;

        this.origin = origin;
        this.subprotocols = new _Array2ReadOnlyList<>(subprotocols);
    }

    // not public; probably not needed by users

    /**
     * Created a WebSocketRequest based on an HttpRequest.
     * <p>
     *     The HttpRequest must form a valid WebSocket handshake request.
     * </p>
     * @throws java.lang.IllegalArgumentException
     *         if the http request is not a valid WebSocket handshake request
     */
    // it's unlikely that apps need this constructor. maybe to add cookies, or proprietary headers.
    // user can create a WebSocketRequest(url) first, get its httpRequest,
    // modify it through HttpRequestImpl, then new WebSocketRequest(newReq)
    public WebSocketRequest(HttpRequest httpRequest) throws IllegalArgumentException
    {
        this.httpRequest_volatile = httpRequest;

        // HTTP version should be 1.1 or higher. we don't check it here.
        // if other checks pass, it's impossible that the request is 1.0 or lower.

        this.ip = httpRequest.ip();
        this.secure = httpRequest.isHttps();

        if(!"GET".equals(httpRequest.method()))
            throw new IllegalArgumentException("WebSocket handshake request must be GET");

        this.resourceName = httpRequest.uri();
        // in the form abs-path[?query]. it's validated.

        if(httpRequest.entity()!=null)
            throw new IllegalArgumentException("webSocket handshake request must not contain a body");
        // if request has a body we'll have to drain it first before reading inbound websocket frames.

        Map<String,String> headers = httpRequest.headers();

        this.hostPort = headers.get(Headers.Host);
        assert hostPort !=null && !hostPort.isEmpty();

        String hvUpgrade = headers.get(Headers.Upgrade);
        if(!_StrUtil.equalIgnoreCase(hvUpgrade, "websocket"))
            throw new IllegalArgumentException("WebSocket handshake request must contain header Upgrade: websocket");

        String hvConnection = headers.get(Headers.Connection);
        if(!_HttpUtil.containsToken(hvConnection, "Upgrade"))
            throw new IllegalArgumentException("WebSocket handshake request must contain header Connection: Upgrade");

        String hvSecWebSocketKey = headers.get("Sec-WebSocket-Key");
        if(hvSecWebSocketKey==null || hvSecWebSocketKey.length()!=24)
            throw new IllegalArgumentException("WebSocket handshake request contains invalid Sec-WebSocket-Key");
        // we could validate whether it's really a base64 encoded string. probably not necessary.

        this.origin = headers.get(Headers.Origin); // can be null
        // not validated

        String hvSecWebSocketProtocol = headers.get("Sec-WebSocket-Protocol");
        if(hvSecWebSocketProtocol==null)
            this.subprotocols = Collections.emptyList();
        else
            this.subprotocols = _StrUtil.splitCommaRO(hvSecWebSocketProtocol); // read only

        // Sec-WebSocket-Extensions - don't care
    }

    /**
     * The HTTP request associated with this WebSocket handshake request.
     */
    // not much extra info in the HttpRequest. maybe app needs it to read some proprietary headers.
    public HttpRequest httpRequest()
    {
        if(httpRequest_volatile==null)
        {
            HttpRequestImpl req = new HttpRequestImpl("GET", resourceName, null);
            req.ip(ip);
            req.isHttps(secure);
            req.header(Headers.Host, hostPort);
            req.header(Headers.Upgrade, "websocket");
            req.header(Headers.Connection, "Upgrade");

            byte[] keyBytes = new byte[16];
            ThreadLocalRandom.current().nextBytes(keyBytes);
            req.header("Sec-WebSocket-Key", Base64.getEncoder().encodeToString(keyBytes));

            if(origin!=null)
                req.header(Headers.Origin, origin);

            req.header("Sec-WebSocket-Version", "13");

            if(!subprotocols.isEmpty())
            {
                StringBuilder sb = new StringBuilder();
                for(int i=0; i<subprotocols.size(); i++)
                {
                    if(i>0)
                        sb.append(", ");
                    sb.append(subprotocols.get(i));
                }
                req.header("Sec-WebSocket-Protocol", sb.toString());
            }

            // no cookies or other headers. if they are desired, use the constructor WsOpenRequest(HttpRequest)

            httpRequest_volatile = req;
        }
        return httpRequest_volatile;
    }

}
