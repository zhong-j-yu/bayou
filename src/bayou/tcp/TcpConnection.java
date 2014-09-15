package bayou.tcp;

import bayou.async.Async;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * Non-blocking TCP connection.
 * <p>
 *     This interface is a higher abstraction over {@link bayou.tcp.TcpChannel}.
 *     See {@link bayou.tcp.TcpChannel2Connection} for converting a TcpChannel to TcpConnection.
 * </p>
 * <p>
 *     This interface may also represent an SSL connection, see subtype {@link bayou.ssl.SslConnection}.
 * </p>
 * <h4 id=write-queue>Write Queue</h4>
 * <p>
 *     This connection maintains a queue of ByteBuffers to be written.
 *     Append a ByteBuffer to the queue by {@link #queueWrite(ByteBuffer)}.
 *     Queued data are not automatically written, you have to call {@link #write()}.
 *     After write(), if {@link #getWriteQueueSize()} does not reach 0,
 *     the connection is temporarily un-writable, and you may call {@link #awaitWritable()} before write() again.
 * </p>
 * <p>
 *     Sentinel values {@link #TCP_FIN} and {@link bayou.ssl.SslConnection#SSL_CLOSE_NOTIFY} can also be queued,
 *     each counted as 1 byte in the queue size.
 * </p>
 * <h4>Thread Safety</h4>
 * <p>
 *     This class is generally not thread-safe. For example, if two threads call read() concurrently,
 *     the behavior is undefined, and probably disastrous.
 *     Usually, you'll use {@link #getExecutor() the connection executor}
 *     for all connection-related tasks, and they will be executed in serialized order.
 *     <!--
 *         actually read actions can occur concurrently with write actions,
 *         there can be two concurrent r/w flows. but that's probably not interesting to app,
 *         which will serialize all actions any way on the executor.
 *     -->
 * </p>
 */

public interface TcpConnection
{
    // another design is AsyncConnection, currently hidden
    //
    // we keep and use this non-blocking interface though, because it's more primitive, closer to selector API.
    // it's more flexible. user may speculatively call awaitReadable() without a prev read() stall.
    // user may not call read() after awaitReadable(), instead user can do something else.
    // user can insert actions between read() and awaitReadable(), or between awaitReadable() and read().
    // same for write methods.
    //
    // it's ok that tcp package is a little low level, and API is uglier. it's rare that user needs to do tcp directly.


    /**
     * Get the ID of this connection.
     * <p>
     *     The ID is for diagnosis only. It is unique within each server/client.
     * </p>
     */
    long getId();

    /**
     * Get the IP address of the peer.
     */
    InetAddress getPeerIp();

    /**
     * Sentinel value for read(), meaning nothing is available for read at the moment.
     */
    ByteBuffer STALL = ByteBuffer.wrap(new byte[0]);

    /**
     * Sentinel value for read() and queueWrite(), representing TCP FIN.
     */
    ByteBuffer TCP_FIN = ByteBuffer.wrap(new byte[0]);

    // in a previous design, there's only one abstraction "EOF". it's FIN for plain conn, CLOSE_NOTIFY for ssl conn.
    // now we have two separated, for more precise control. this is leaky abstraction, but necessary sometimes.
    // it's ugly but this is low level API anyway.
    // on an ssl conn, if FIN comes before CLOSE_NOTIFY is received, is it an error? not necessarily.
    // CLOSE_NOTIFY is for truncation attack, if app message has no end-of-message marker, e.g. http/1.0 response.
    // but in most app protocols messages have end marker, close-notify not needed, it's perfectly legit
    // to send TCP_FIN without close-notify. so we leave it to app to decide; it's a good thing that we
    // expose both TCP_FIN and SSL_CLOSE_NOTIFY events so app can make more precise decisions.


    /**
     * Read data from the peer.
     * <p>
     *     This method returns sentinel values or some real data.
     * </p>
     * <ul>
     *     <li>
     *         {@link #STALL} - nothing is available for read at the moment.
     *         You may {@link #awaitReadable(boolean)} before read again.
     *     </li>
     *     <li>
     *         {@link #TCP_FIN} or {@link bayou.ssl.SslConnection#SSL_CLOSE_NOTIFY} - TCP FIN or SSL close-notify record is received.
     *         You should not call read() again.
     *     </li>
     *     <li>
     *         A ByteBuffer containing application data.
     *     </li>
     * </ul>
     * <p>
     *     The caller of read() must check for the sentinel values first (using ==).
     * </p>
     */
    ByteBuffer read() throws Exception;
    // Guaranteed by our impls: bb.length()>0 if bb is data. (unless bb was given to us in unread())

    /**
     * Unread data.
     * <p>
     *     Next read() will see the ByteBuffer bb.
     * </p>
     * <p>
     *     Only one unread is supported at a time. Two consecutive unread() calls is illegal.
     * </p>
     */
    void unread(ByteBuffer bb) throws IllegalStateException;
    // we provide this method because it is very useful to apps
    // next read() will return `bb` as is (unless close() called before read())
    // bb can be empty, we don't check
    // next awaitReadable() will succeed immediately
    // only 1 unread() is supported. cannot be called after close()

    /**
     * Wait till this connection becomes readable.
     * <p>
     *     This method is typically called after a previous read() returns STALL.
     * </p>
     * <p>
     *     There can be only one pending awaitReadable action at any time.
     * </p>
     * <p>
     *     Spurious wakeup is possible - this action may succeed yet the next read() sees STALL again.
     * </p>
     * <p>
     *     See {@link bayou.tcp.TcpChannel#awaitReadable(boolean)} for more details.
     * </p>
     */
    Async<Void> awaitReadable(boolean accepting);
    // usually called if a prev read() returns STALL;
    //   can be called if not (speculating that source is not readable)




    // the connection maintains a queue of ByteBuffer to be written. write() will attempt to write them.
    // (this mechanism is useful/helpful to user. and we need it for ssl multi-stage buffering.)
    // write-remaining (wr) is the number of bytes not written yet. (now renamed to write-queue-size)

    /**
     * Append the ByteBuffer to the <a href="#write-queue">write queue</a>.
     * <p>
     *     The ByteBuffer is now owned by this connection. If it's from a shared/cached source,
     *     pass a {@link java.nio.ByteBuffer#asReadOnlyBuffer() duplicate} to this method.
     * </p>
     * <p>
     *     The ByteBuffer can be very small, e.g. "\r\n", or very large, e.g. a cached huge buffer.
     * </p>
     * <p>
     *     Sentinel values {@link #TCP_FIN} and {@link bayou.ssl.SslConnection#SSL_CLOSE_NOTIFY} can also be queued,
     *     each counted as 1 byte in the queue size. If this connection is not SSL,
     *     <code>queueWrite(SSL_CLOSE_NOTIFY)</code> is a no-op.
     * </p>
     * <p>
     *     Any app data must be queued before SSL_CLOSE_NOTIFY, which must be queued before TCP_FIN.
     * </p>
     * @return the write queue size (including the newly queued ByteBuffer)
     */
    long queueWrite(ByteBuffer bb);
    // usually it should be the server that initiates TCP FIN.

    /**
     * Get the total remaining bytes of the write queue ByteBuffers.
     */
    long getWriteQueueSize();

    /**
     * Write bytes from the head of the write queue.
     * <p>
     *     This method returns after write queue is cleared, or send buffer is full.
     * </p>
     * <p>
     *     If {@link #getWriteQueueSize()} does not decrease to 0 after write(),
     *     this connection is temporarily un-writable (because send-buffer is full).
     *     You may {@link #awaitWritable()} then try write() again.
     * </p>
     *
     * @return number of byte actually written
     */
    long write() throws Exception;
    // may return 0 even tho progress has been made under the hood.
    // remember FIN/CLOSE_NOTIFY counts as 1 byte.

    /**
     * Wait till this connection becomes writable.
     * <p>
     *     This method is typically called after a previous write() didn't clear the write queue.
     *     This action will wait for more rooms in the send buffer.
     * </p>
     * <p>
     *     There can be only one pending awaitWritable action at any time.
     * </p>
     * <p>
     *     This action succeeds when this connection become writable (next write() should be able to write more bytes),
     *     or fails due to cancellation, channel being closed, server shutdown etc.
     * </p>
     * <p>
     *     Spurious wakeup is possible - this action may succeed yet the next write() returns 0 (no byte is written).
     * </p>
     */
    Async<Void> awaitWritable();
    // it is ok to call queueWrite() while waiting for writable. make sure happens-before relationships
    //     begin waiting --> queueWrite() --> end waiting (async callback starts)


    /**
     * Close this connection.
     * <p>
     *     Data in the write queue are abandoned. The TCP connection will be closed.
     * </p>
     * <p>
     *     If `drainTimeout` is non-null and positive, we will attempt to drain the inbound
     *     TCP bytes (till FIN is received) before we actually close the TCP connection.
     *     This is to avoid the infamous TCP RST problem - if the connection is closed without
     *     draining the inbound, the peer may not received the data we have sent.
     * </p>
     * <p>
     *     The draining step is unnecessary if the app protocol is properly designed,
     *     which knows that there's no inbound data at the time of close.
     * </p>
     * <p>
     *     The draining step can also be skipped if FIN was seen in a previous read(),
     *     or app wants to kill the connection immediately.
     * </p>
     */
    Async<Void> close(Duration drainTimeout); // can be called repeatedly
    // though draining can also be done by read(), it might be a little expensive (e.g. decoding SSL records)
    //
    // TCP_FIN will be sent before draining. TCP_FIN is sent even without draining.
    // may fail during draining; we then cannot be sure all outbound data is received by peer.
    //
    // thread-safety:
    // close() must be called on both read and write flow. for half-duplex server, this is no problem.
    // for full-duplex server, it should arrange locks and flags,
    // so that both read and write flow are ended, before close() is invoked. e.g.
    //     read_func(){  sync(r_lock){ if(r_ended) throw error; ... } }   // similar for write
    //     close(){  sync(r_lock){ r_ended=true; ... }  sync(w_lock){..}  conn.close(); }
    // we don't require that in this interface because it'll be unnecessary for most apps which are half-duplex.
    //   for full-duplex apps, they probably have their own locks to coordinate r/w flows anyway.
    //
    // but we don't mention this publicly, to bother users. we just say that this class is not thread-safe,
    // all actions should be serialized (by using the executor). so there is no two r/w flows.


    /**
     * Get an executor associated with this connection.
     * <p>
     *     The executor can be used to execute non-blocking tasks related to this connection.
     *     Tasks will be executed sequentially in the order they are submitted.
     * </p>
     */
    Executor getExecutor();

}
