package bayou.ssl;

import bayou.tcp.TcpConnection;

import javax.net.ssl.SSLSession;
import java.nio.ByteBuffer;

/**
 * Non-blocking SSL connection.
 * <p>
 *     Note that this interface is a subtype of {@link bayou.tcp.TcpConnection}.
 * </p>
 * <p>
 *     See {@link bayou.ssl.SslChannel2Connection} for converting a TcpChannel to an SslConnection.
 * </p>
 */
public interface SslConnection extends TcpConnection
{

    /**
     * Sentinel value for
     * {@link bayou.tcp.TcpConnection#read()} and
     * {@link bayou.tcp.TcpConnection#queueWrite(java.nio.ByteBuffer)},
     * representing SSL close-notify record.
     */
    ByteBuffer SSL_CLOSE_NOTIFY = ByteBuffer.wrap(new byte[0]);


    /**
     * Get the SSL session.
     */
    SSLSession getSslSession();
    // we only expose the SSLSession, instead of the SSLEngine.
    // other methods in SSLEngine are probably uninteresting to app.

}
