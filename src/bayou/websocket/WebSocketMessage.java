package bayou.websocket;

import _bayou._bytes._ErrorByteSource;
import bayou.bytes.ByteSource;
import bayou.bytes.SimpleByteSource;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;

/**
 * WebSocket message.
 * <p>
 *     A WebSocket message contains a sequence of bytes.
 *     Note that <code>WebSocketMessage</code> is a subtype of {@link ByteSource}.
 * </p>
 * <p>
 *     A WebSocket message is either
 *     <i id=text>Text</i> or <i id=binary>Binary</i>,
 *     identified by the message header.
 *     A <i>Text</i> message contains texts encoded in UTF-8 bytes.
 *     A <i>Binary</i> message can contain arbitrary bytes.
 * </p>
 * <p>
 *     See {@link ByteSource} methods for reading bytes/chars from the message.
 * </p>
 * <p>
 *     See static methods in this interface for creating messages,
 *     e.g. {@link #text(CharSequence)}, {@link #binary(byte[])}.
 * </p>
 */
public interface WebSocketMessage extends ByteSource
{
    /**
     * Whether this is a Text message. If false, this is a Binary message.
     */
    abstract public boolean isText();



    // --------------------------------------------------------------------------------------------

    /**
     * Create a text message containing the chars.
     */
    public static WebSocketMessage text(CharSequence chars)
    {
        try
        {
            CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
            ByteBuffer bb = encoder.encode(CharBuffer.wrap(chars));
            return text(new SimpleByteSource(bb));
        }
        catch (Exception e)
        {
            return text(new _ErrorByteSource(e));
        }
    }

    /**
     * Wrap a ByteSource as a text message.
     * <p>
     *     The bytes in `byteSource` must form a valid UTF-8 byte sequence.
     * </p>
     */
    public static WebSocketMessage text(ByteSource byteSource)
    {
        return new ByteSource2WebSocketMessage(true, byteSource);
    }


    /**
     * Create a binary message containing the bytes.
     */
    public static WebSocketMessage binary(byte[] bytes)
    {
        return binary(new SimpleByteSource(bytes));
    }

    /**
     * Wrap a ByteSource as a binary message.
     */
    public static WebSocketMessage binary(ByteSource byteSource)
    {
        return new ByteSource2WebSocketMessage(false, byteSource);
    }

}
