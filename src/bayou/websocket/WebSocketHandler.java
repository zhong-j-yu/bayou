package bayou.websocket;

import bayou.async.Async;

/**
 * Handles WebSocket handshakes.
 * <p>
 *     For each handshake request, the {@link #handle(WebSocketRequest)} action
 *     generates a handshake response, either Reject or Accept.
 * </p>
 * <p>
 *     For many applications, the handler can simply accept any request
 * </p>
 * <pre>
 *     WebSocketHandler handler = request -&gt; WebSocketResponse.accept(this::handleChannel);
 *
 *     Async&lt;Void&gt; handleChannel(WebSocketChannel channel){ ... }
 * </pre>
 * <p>
 *     Note that by default, same-origin policy is enforced before the handler is invoked,
 *     see {@link WebSocketServerConf#enforceSameOrigin(boolean)}.
 * </p>
 */
public interface WebSocketHandler
{
    /**
     * Handle a WebSocket handshake request.
     * <p>
     *     This action should generate either a {@link WebSocketResponse.Reject}
     *     or a {@link WebSocketResponse.Accept}.
     * </p>
     * <p>
     *     This method will be invoked by the server on a Fiber created for the underlying HTTP connection.
     * </p>
     * <p>
     *     {@link bayou.http.CookieJar} can be used during this action to get/set cookies.
     *     <!-- an Accept response has no other way to set cookies -->
     * </p>
     */
    public abstract Async<WebSocketResponse> handle(WebSocketRequest request);
}
