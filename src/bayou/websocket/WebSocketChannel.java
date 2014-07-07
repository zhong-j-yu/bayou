package bayou.websocket;

import bayou.async.Async;
import bayou.util.End;

import java.nio.ByteBuffer;

/**
 * WebSocket channel for reading and writing messages.
 * <p>
 *     To read the next {@link WebSocketMessage} from the the peer,
 *     call {@link #readMessage()}, or convenience methods
 *     {@link #readText(int)}, {@link #readBinary(int)}.
 *     These actions will fail with {@link WebSocketClose} if the inbound is gracefully closed by the peer.
 * </p>
 * <p>
 *     To write a {@link WebSocketMessage} to the peer,
 *     call {@link #writeMessage(WebSocketMessage)}, or convenience methods
 *     {@link #writeText(CharSequence)}, {@link #writeBinary(byte[])}.
 * </p>
 * <p>
 *     To gracefully close the outbound, call {@link #writeClose()}.
 * </p>
 * <p>
 *     The channel must be eventually closed by calling {@link #close()}.
 * </p>
 */

// may represent logical channels in a multiplexed connection

// out impl is thread-safe. we don't mention that.
// but it should be without-saying that the channel is full-duplex,
// inbound/outbound can be operated on concurrently.

public interface WebSocketChannel
{
    // no meta info, like remote IP, handshake data.
    // if they are needed by app, capture them from request during handshake


    /**
     * Read the next message from the peer.
     * <p>
     *     This action will succeed when the next message arrives.
     *     The application must {@link bayou.bytes.ByteSource#read() read} the message
     *     till EOF, or {@link bayou.bytes.ByteSource#close() close} the message
     *     (regardless of EOF), before it can call <code>readMessage()</code> again.
     * </p>
     * <p>
     *     This action will fail with {@link WebSocketClose} (a subtype of {@link End})
     *     if the inbound is gracefully closed by the peer.
     * </p>
     */
    // Cancelling this action won't corrupt inbound stream; readMessage() can be called again.
    abstract public Async<WebSocketMessage> readMessage();
    // todo: if an incoming msg is Text, we don't internally enforce that the bytes are strict UTF-8.
    // this is bad because app may rely on that. we need to enforce UTF-8 in WebSocketInbound.
    // probably not urgent, since app is likely using readText() which does enforce UTF-8.

    /**
     * Read the next text message as one String.
     * <p>
     *     The next message must be a <a href="WebSocketMessage.html#text">Text message</a>.
     *     If it's a Binary message instead,
     *     this action fails with {@link bayou.websocket.WebSocketException}.
     * </p>
     * @param maxChars
     *        maximum chars expected in the message. If exceeded,
     *        this action fails with {@link bayou.util.OverLimitException}.
     */
    // if app wants to allow binary msg as well, do readMessage().then(msg->msg.asString(maxChars))
    public default Async<String> readText(int maxChars)
    {
        return readMessage().then(msg -> {
            if(!msg.isText())
                throw new WebSocketException("received a Binary message; expecting a Text message");
            return msg.asString(maxChars);
        });
    }

    /**
     * Read the next binary message into one ByteBuffer.
     * <p>
     *     The next message must be a <a href="WebSocketMessage.html#binary">Binary message</a>.
     *     If it's a Text message instead,
     *     this action fails with {@link bayou.websocket.WebSocketException}.
     * </p>
     * @param maxBytes
     *        maximum bytes expected in the message. If exceeded,
     *        this action fails with {@link bayou.util.OverLimitException}.
     */
    // if app wants to allow text msg as well, do readMessage().then(msg->msg.allBytes(maxBytes))
    public default Async<ByteBuffer> readBinary(int maxBytes)
    {
        return readMessage().then(msg -> {
            if(msg.isText())
                throw new WebSocketException("received a Text message; expecting a Binary message");
            return msg.readAll(maxBytes);
        });
    }

    // ------------------------------------------------------------------------------------

    /**
     * Write a message to the peer.
     * <p>
     *     This action succeeds after the message is written;
     *     the result is the total bytes in the message (not including framing bytes).
     * </p>
     * <p>
     *     This method should not be called after {@link #writeClose()} is called.
     * </p>
     * <p>
     *     It's not necessary to wait for a writeMessage() action to complete
     *     before writing the next message. The outbound messages will be queued.
     *     However, the application should wait for the last writeMessage() action
     *     (or the writeClose() action if it's called) to complete before calling {@link #close()}
     *     to ensure outbound data are reliably delivered.
     * </p>
     * <p>
     *     If this action fails (e.g. because the action is cancelled, or message.read() fails),
     *     the outbound is likely corrupt, the channel should be by closed by {@link #close()}.
     * </p>
     */
    abstract public Async<Long> writeMessage(WebSocketMessage message);

    /**
     * Write a text message to the peer.
     * <p>
     *     This method is equivalent to
     *     <code>writeMessage(WebSocketMessage.text(chars))</code>.
     * </p>
     */
    public default Async<Long> writeText(CharSequence chars)
    {
        return writeMessage(WebSocketMessage.text(chars));
    }

    /**
     * Write a binary message to the peer.
     * <p>
     *     This method is equivalent to
     *     <code>writeMessage(WebSocketMessage.binary(bytes))</code>.
     * </p>
     */
    public default Async<Long> writeBinary(byte[] bytes)
    {
        return writeMessage(WebSocketMessage.binary(bytes));
    }


    /**
     * Close the outbound gracefully.
     * <p>
     *     A <a href="WebSocketClose.html#close-frame">Close-Frame</a> will be sent to the peer
     *     (after queued outbound messages are sent).
     * </p>
     * <p>
     *     This method can be called at most once.
     * </p>
     */
    // if cancelled before completion, outbound stream is corrupt
    // currently we don't support close reason code and string; app probably don't care. may add them in future
    abstract public Async<Void> writeClose();

    /**
     * Close the channel.
     * <p>
     *     This method frees resources associated with this channel, e.g. the underlying TCP connection.
     * </p>
     * <p>
     *     This method does *not* perform <a href="WebSocketClose.html#close-frame">Close-Frame</a> exchange;
     *     <!-- because it's not needed by all apps. -->
     *     if the application wants to, it can do that through
     *     {@link #readMessage()} and {@link #writeClose()}.
     * </p>
     * <p>
     *     This method can be called multiple times; only the first call is effective.
     * </p>
     */
    abstract public Async<Void> close();
}
