package bayou.websocket;

import _bayou._str._CharSeqSaver;
import bayou.async.Async;
import bayou.async.AutoAsync;
import bayou.http.HttpResponse;
import bayou.mime.Headers;
import bayou.util.function.FunctionX;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

/**
 * WebSocket handshake response.
 * <p>
 *     A handshake response is either a {@link Reject} or an {@link Accept}.
 * </p>
 * <p>
 *     See {@link WebSocketHandler}.
 * </p>
 */
public class WebSocketResponse
{
    // no public constructor
    WebSocketResponse()
    {

    }

    /**
     * Create a Reject response.
     * <p>
     *     This method is equivalent to
     * </p>
     * <pre>
     *     new WebSocketResponse.Reject( HttpResponse.text(statusCode, msg) )
     * </pre>
     */
    public static Reject reject(int statusCode, String msg)
    {
        return new WebSocketResponse.Reject(HttpResponse.text(statusCode, msg));
    }

    /**
     * Create an Accept response.
     * <p>
     *     This method is equivalent to
     * </p>
     * <pre>
     *     new WebSocketResponse.Accept(null, channelHandler);
     * </pre>
     */
    public static Accept accept(FunctionX<WebSocketChannel, Async<?>> channelHandler)
    {
        return new WebSocketResponse.Accept(null, channelHandler);
    }


    /**
     * To reject the handshake.
     * <p>
     *     A Reject response contains an HTTP response; the response status code indicates the reason for rejection.
     * </p>
     * <p>
     *     Note that this class is a subtype of <code>Async&lt;WebSocketResponse&gt;</code>.
     * </p>
     *
     * @see WebSocketResponse#reject(int, String)
     */
    public static class Reject extends WebSocketResponse implements AutoAsync<WebSocketResponse>
    {
        final HttpResponse httpResponse;

        /**
         * Create a Reject response.
         * <p>
         *     The status code of `httpResponse` must not be 1xx or 2xx.
         * </p>
         */
        public Reject(HttpResponse httpResponse)
        {
            int statusCode = httpResponse.statusCode();
            if(statusCode<300) // 1xx and 2xx
                throw new IllegalArgumentException("invalid Reject response status code: "+statusCode);

            this.httpResponse = httpResponse;
        }

        /**
         * The HTTP response.
         */
        public HttpResponse getHttpResponse()
        {
            return httpResponse;
        }
    }


    /**
     * To accept the handshake.
     * <p>
     *     The Accept response contains a {@link #getChannelHandler() channelHandler}
     *     that will operate on the WebSocket channel created after handshake.
     * </p>
     * <p>
     *     Note that this class is a subtype of <code>Async&lt;WebSocketResponse&gt;</code>.
     * </p>

     * @see WebSocketResponse#accept(bayou.util.function.FunctionX)
     */

    // this interface is for server only; server app gives Accept response, with a Consumer<WsConnection>.
    //     (for client side, we need another interface, "Accepted"? it should produce a WsConnection)

    public static class Accept extends WebSocketResponse implements AutoAsync<WebSocketResponse>
    {
        final FunctionX<WebSocketChannel, Async<?>> channelHandler; // not null

        final String subprotocol;  // can be null

        // in Accept response, app may also want to add headers/cookies to the http response.
        // cookies can be added through CookieJar
        // other headers are not supported for now.

        /**
         * The channelHandler.
         * <p>
         *     After a WebSocket handshake is accepted, the server creates a
         *     WebSocketChannel and feed it to this channelHandler.
         * </p>
         * <p>
         *     The channelHandler is invoked in a Fiber created for the channel.
         *     The Fiber completes when the Async&lt;?&gt; returned by the channelHandler completes.
         * </p>
         */
        // app may want to spawn a new fiber for full-duplex (if for nothing but separate fiber traces)
        // it's unlikely but possible that app doesn't need a fiber for the ws chann.
        //     chann handler can complete immediately to end the fiber; continue to use chann somewhere else.
        public FunctionX<WebSocketChannel, Async<?>> getChannelHandler()
        {
            return channelHandler;
        }

        /**
         * The subprotocol selected.
         * <p>
         *     If non-null, it must be one of the subprotocols presented in the
         *     handshake request.
         * </p>
         */
        public String getSubprotocol()
        {
            return subprotocol;
        }

        /**
         * Create an Accept response.
         */
        public Accept(String subprotocol, FunctionX<WebSocketChannel, Async<?>> channelHandler)
        {
            this.subprotocol = subprotocol;

            if(channelHandler ==null)
                throw new NullPointerException("connectionHandler cannot be null");
            this.channelHandler = channelHandler;
        }
    }

    // =========

    static String challengeResponse(String challengeKey)
    {
        challengeKey += "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        byte[] bytes = challengeKey.getBytes(StandardCharsets.ISO_8859_1);

        MessageDigest md;
        try
        {
            md = MessageDigest.getInstance("SHA-1");
        }
        catch (NoSuchAlgorithmException e) // should not happen
        {
            throw new AssertionError(e);
        }
        md.update(bytes);
        bytes = md.digest();
        return Base64.getEncoder().encodeToString(bytes);
    }

    static ByteBuffer genHandshakeResponse(String accept, String protocol, List<String> headersSetCookie)
    {
        _CharSeqSaver out = new _CharSeqSaver( 8 + 4 * headersSetCookie.size() );

        out.append("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n");

        out.append("Sec-WebSocket-Accept: ").append(accept).append("\r\n");

        if(protocol!=null)
            out.append("Sec-WebSocket-Protocol: ").append(protocol).append("\r\n");

        for(String setCookie : headersSetCookie)
            out.append(Headers.Set_Cookie).append(": ").append(setCookie).append("\r\n");

        out.append("\r\n");

        return ByteBuffer.wrap(out.toLatin1Bytes());
    }

}
