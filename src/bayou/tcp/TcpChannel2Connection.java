package bayou.tcp;

import _bayou._tmp._ByteBufferPool;
import bayou.util.function.FunctionX;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Convert TcpChannel to TcpConnection.
 * <p>
 *     This class is for "plain" connections.
 *     See {@link bayou.ssl.SslChannel2Connection} instead for SSL connections.
 * </p>
 */
public class TcpChannel2Connection
{

    final Supplier<Long> idGenerator;

    final _ByteBufferPool plainReadBufferPool;
    final _ByteBufferPool plainWriteBufferPool;

    /** Create a TcpChannel to TcpConnection converter.
     *
     * @param readBufferSize
     *        preferred buffer size for calling {@link bayou.tcp.TcpChannel#read(java.nio.ByteBuffer)}.
     * @param writeBufferSize
     *        preferred buffer size for calling {@link bayou.tcp.TcpChannel#write(java.nio.ByteBuffer...)}.
     * @param idGenerator
     *        generator for {@link bayou.tcp.TcpConnection#getId() TcpConnection id}.
     */
    public TcpChannel2Connection(int readBufferSize, int writeBufferSize, Supplier<Long> idGenerator)
    {
        if(idGenerator==null)
            idGenerator = new AtomicLong(1)::getAndIncrement;

        this.idGenerator = idGenerator;

        // read/writeBufferSize - not to be confused with socket receive/send buffer size.
        plainReadBufferPool  = _ByteBufferPool.forCapacity(readBufferSize);
        plainWriteBufferPool = _ByteBufferPool.forCapacity(writeBufferSize);
        // use default expiration for buffer pools
    }

    /**
     * Convert a TcpChannel to a TcpConnection.
     */
    public TcpConnection convert(TcpChannel channel)
    {
        long id = idGenerator.get().longValue();
        return new PlainTcpConnection(channel, id, plainReadBufferPool, plainWriteBufferPool);
    }

}
