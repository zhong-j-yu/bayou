package bayou.util;

import bayou.async.Async;
import bayou.bytes.ByteSource;
import bayou.websocket.WebSocketClose;

/**
 * A control exception for signaling that something has reached its end.
 * <p>
 *     For example, {@link bayou.async.AsyncIterator#next()} either succeeds with the next item,
 *     or fails with an <code>End</code> if there are no more items.
 * </p>
 * <p>
 *     Other APIs, for example {@link ByteSource#read()}, also use <code>End</code>
 *     as the control exception, to be compatible with {@link bayou.async.AsyncIterator#next()}.
 * </p>
 * <p>
 *     Some APIs create subtypes of <code>End</code> to carry more information.
 *     For example, {@link WebSocketClose}, a subtype of <code>End</code>,
 *     contains code/reason for the closure of a WebSocket channel inbound/outbound.
 * </p>
 * <p>
 *     Being a control exception, an <code>End</code> does not really represent an exceptional condition;
 *     it is more of a sentinel value for an API.
 *     An <code>End</code> contains no stacktrace and is cheap to create.
 *     It is immutable,
 *     with <code>enableSuppression=writableStackTrace=false</code>,
 *     see explanation in
 *     {@link Throwable#Throwable(String, Throwable, boolean, boolean)
 *                      Throwable(message, cause, enableSuppression, writableStackTrace)}.
 *     It's safe to share an <code>End</code> object due to immutability.
 * </p>
 */

public class End extends Exception
{
    /**
     * Create an <code>End</code> exception.
     */
    public End()
    {
        super(null, null, false, false);
    }

    /**
     * Create an <code>End</code> exception.
     */
    public End(String message)
    {
        super(message, null, false, false);
    }

    /**
     * Create an <code>End</code> exception.
     */
    public End(String message, Throwable cause)
    {
        super(message, cause, false, false);
    }


    static final End INSTANCE = new End();
    static final Async ASYNC_END = Result.<Void>failure(INSTANCE);

    /**
     * Return an <code>End</code> instance.
     * <p>
     *     Semantically equivalent to <code>`new End()`</code>,
     *     but this method may return a cached object.
     * </p>
     * <p>
     *     Example Usage:
     * </p>
     * <pre>
     *     action
     *         .then( v-&gt;
     *         {
     *             if(condition)
     *                 throw End.instance();
     *             ...
     *         }
     * </pre>
     */
    public static End instance()
    {
        return INSTANCE;
    }

    /**
     * Return an `Async&lt;T&gt;` that immediately fails with an <code>End</code> exception.
     * <p>
     *     Semantically equivalent to
     *     <code>`Async.failure(new End())`</code>,
     *     but this method may return a cached object.
     * </p>
     * <p>
     *     Often used as a return value in an Async method, for example:
     * </p>
     * <pre>
     *     public Async&lt;ByteBuffer&gt; read()
     *     {
     *         if( eof_reached )
     *             return End.async();
     *         ...
     *     }
     * </pre>
     */
    public static <T> Async<T> async()
    {
        @SuppressWarnings({"unchecked", "UnnecessaryLocalVariable"})
        Async<T> async = ASYNC_END; // erasure
        return async;
    }

}
