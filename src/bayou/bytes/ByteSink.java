package bayou.bytes;

import bayou.async.Async;

import java.nio.ByteBuffer;

/**
 * Async sink of bytes.
 * <p>
 *     Bytes are written to the sink through method {@link #write(ByteBuffer)}.
 *     The sink must be closed after use.
 * </p>
 * <p>
 *     The writer should also call {@link #error(Exception)} to indicate that the byte sequence is corrupt.
 * </p>
 * <p>
 *     In general, a ByteSink is <em>not</em> thread-safe.
 * </p>
 * <p>
 *     In general, concurrent pending actions are not allowed.
 *     Particularly, close() cannot be invoked while a write() is pending.
 * </p>
 */
public interface ByteSink
{
    /**
     * Write the bytes to this sink.
     * <p>
     *     If this action fails, the sink should be considered in an error state,
     *     and it should be closed immediately.
     * </p>
     * <p>
     *     The ownership of `bb` is transferred to the sink.
     *     The sink should treat the content of `bb` as read-only.
     * </p>
     * <p>
     *     CAUTION: since ByteBuffer is stateful (even for methods like {@link ByteBuffer#get()}),
     *     a new ByteBuffer must be created for each write() action.
     *     The caller may create a view of a shared ByteBuffer through
     *     {@link java.nio.ByteBuffer#asReadOnlyBuffer()}.
     * </p>
     * <p>
     *     The app should wait for this write() action to complete
     *     before it calls another method on this sink.
     * </p>
     */
    Async<Void> write(ByteBuffer bb);

    /**
     * Set this sink to an error state.
     * <p>
     *     If the data producer encounters an error, it should set the sink to an error state,
     *     so that the situation is not confused with a graceful termination of writing.
     * </p>
     * <p>
     *     For example, a server app calculates data and writes them to an http response sink.
     *     If an exception occurs in the calculation, the app should call <code>sink.error()</code>,
     *     so that the client can know that the response body is corrupt.
     * </p>
     * <p>
     *     If the sink is in an error state, it should be closed immediately.
     * </p>
     * <p>
     *     This method can be called multiple times; only the first call is effective.
     * </p>
     */
    Async<Void> error(Exception error);
    // usually this action should not fail
    // this method is useful, e.g. in HTTP/1.1 chunked response. if app encounters an error while
    // producing response body, sink.error() will cause the connection to terminate, client
    // will detect that the response body is incomplete (because there's no last-chunk)
    // without sink.error(), server/client do not know that the body is incomplete.

    /**
     * Close this sink.
     * <p>
     *     If the sink is not in an error state,
     *     close() should flush all previously written data, and wait for the flushing to complete.
     * </p>
     * <p>
     *     The close() action is important to finalize the writing process.
     *     If the close() action fails, it may indicate that
     *     the data are not reliably delivered to the destination.
     * </p>
     * <p>
     *     This method can be called multiple times; only the first call is effective.
     * </p>
     */
    Async<Void> close();
    // unlike ByteSource.close(),
    // this close() has important semantics. it may be pending. may fail.
    // caller cares about the result. may want to cancel a pending close.

}
