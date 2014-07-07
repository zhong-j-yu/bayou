package bayou.websocket;

/**
 * Exception in WebSocket communication.
 * <p>
 *     This exception usually indicates protocol violation by the peer.
 * </p>
 */
// we only use it for protocol error from peer
public class WebSocketException extends Exception
{
    public WebSocketException(String msg)
    {
        super(msg);
    }

    public WebSocketException(String msg, Throwable cause)
    {
        super(msg, cause);
    }
}
