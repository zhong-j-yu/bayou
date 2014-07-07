package bayou.websocket;

import bayou.util.End;

/**
 * To indicate that a WebSocket inbound is gracefully closed.
 * <p>
 *     This is a control exception, a subtype of {@link End}.
 *     It's used by {@link WebSocketChannel#readMessage()}.
 * </p>
 * <p>
 *     <code id=close-frame>Close-Frame</code> is sent by a WebSocket endpoint to
 *     gracefully close its outbound direction,
 *     see <a href="http://tools.ietf.org/html/rfc6455#section-5.5.1">RFC6455 &sect;5.5.1</a>.
 *     The Close-Frame may contain code and reason for diagnosis.
 * </p>
 *
 */
public class WebSocketClose extends End
{
    final int code;
    final String reason;

    /**
     * Create a WebSocketClose instance.
     */
    public WebSocketClose(int code, String reason)
    {
        super(""+code+": "+reason);

        this.code = code;
        this.reason = reason;
    }

    /**
     * The code given by the Close-Frame. If the Close-Frame contains no code, 1005 is the substitute value.
     * <p>
     *     See <a href="http://tools.ietf.org/html/rfc6455#section-7.4">RFC6455 &sect;7.4</a>.
     * </p>
     */
    public int code()
    {
        return code;
    }

    /**
     * The reason given by the Close-Frame. If the Close-Frame contains no reason, "" is the substitute value.
     */
    public String reason()
    {
        return reason;
    }
}
