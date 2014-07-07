package bayou.tcp;

import bayou.async.Async;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

/**
 * Non-blocking TCP channel.
 * <p>
 *     This interface is used by {@link TcpServer} to abstract a TCP channel.
 * </p>
 * <p>
 *     (You may want to use {@link TcpServerX} with {@link TcpConnection} instead which have more features.)
 * </p>
 * <p>
 *     This class is thread-safe, all methods can be called on any thread at any time.
 *     However it's best to have only one flow, using {@link #getExecutor() the channel executor}.
 * </p>
 */
public interface TcpChannel
{
    /**
     * Get the IP address of the peer.
     */
    InetAddress getRemoteIp();

    /**
     * Read some bytes into the ByteBuffer.
     * <p>
     *     The ByteBuffer should have {@link ByteBuffer#remaining() remaining()}&gt;0.
     * </p>
     * <p>
     *     This method returns the number of bytes read;
     *     or -1 if EOF is reached (i.e. a TCP FIN is received from the peer).
     * </p>
     * <p>
     *     If this method returns 0, caller usually should call {@link #awaitReadable(boolean)}
     *     to wait for more bytes from the peer.
     * </p>
     */
    int read(ByteBuffer bb) throws Exception;

    /**
     * Wait till this channel becomes readable.
     * <p>
     *     This method is typically called after a previous read() returns 0.
     * </p>
     * <p>
     *     There can be only one pending awaitReadable action at any time.
     * </p>
     * <p>
     *     How will this action complete:
     * </p>
     * <ul>
     *     <li>
     *         this action succeeds when this channel becomes readable. Next read() should see some bytes.
     *     </li>
     *     <li>
     *         if parameter <code>`accepting==true`</code>, and the server is/becomes in the state
     *         of <a href="TcpServer.html#life-cycle"><code>acceptingPaused/Stopped</code></a>,
     *         this action fails with a message that the server is not accepting new connections.
     *         This is useful, for example, for an HTTP server to await for a new http request on a persistent connection;
     *         if pause/stopAccepting() is called, the pending awaitReadable actions fail, achieving the effect
     *         of not only pause/stop accepting new connections, but new requests as well.
     *     </li>
     *     <li>
     *         this action fails due to cancellation, channel being closed, server shutdown, etc.
     *     </li>
     * </ul>
     */
    Async<Void> awaitReadable(boolean accepting);
    // probably no spurious wakeup - if readable, next read() should return something.
    //    it's better for app to prepare for the possibility of spurious wakeup
    // can be called without a prev read()==0 (speculating that source is not readable)


    /**
     * Write bytes to this channel.
     * <p>
     *     Return number of bytes actually written.
     *     Note that not all bytes in `srcs` are guaranteed to be written, because the send buffer may be full.
     * </p>
     * <p>
     *     If not all bytes are written, caller should usually call {@link #awaitWritable()}.
     * </p>
     */
    long write(ByteBuffer... srcs) throws Exception;
    // unclear what happens to srcs positions if throws. caller usually treats exception as unrecoverable.

    /**
     * Wait till this channel becomes writable.
     * <p>
     *     This method is typically called after a previous write() didn't write all bytes.
     *     This action will wait for more rooms in the send buffer.
     * </p>
     * <p>
     *     There can be only one pending awaitWritable action at any time.
     * </p>
     * <p>
     *     This action succeeds when this channel become writable (next write() should be able to write more bytes),
     *     or fails due to cancellation, channel being closed, server shutdown etc.
     * </p>
     */
    Async<Void> awaitWritable();

    /**
     * Shutdown the output direction of this channel.
     * <p>
     *     A TCP FIN will be sent to the peer.
     * </p>
     */
    void shutdownOutput() throws Exception;
    // some app protocol relies on EOF, e.g. HTTP/1.0. It is possibly a mistake.


    /**
     * Close this channel.
     */
    void close();
    // will call SocketChannel.close().
    // CAUTION: see tcp.rst.txt
    // does not throw. if there's a problem, it should be logged.
    // awaitR/W(), outstanding or after close(), will fail
    // close() is async in nature. (an event is scheduled to carry out real closing on selector flow)
    // it's possible a read/write() succeeds even if close() happens-before it.
    // user needs self policing to not depend on seeing error from read/write() after close().
    // usually if a flow calls close(), the flow ends, no more r/w on that flow.
    // if there's another flow doing w/r, it'll encounter exception soon on w/r.

    /**
     * Get an executor associated with this channel.
     * <p>
     *     The executor can be used to execute non-blocking tasks related to this channel.
     *     Tasks will be executed sequentially in the order they are submitted.
     * </p>
     */
    Executor getExecutor();
    // server does not provide fiber. app may want to create one or two(r/w) fibers, using the executor.


}
