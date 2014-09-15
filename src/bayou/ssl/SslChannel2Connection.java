package bayou.ssl;

import _bayou._tmp._ByteBufferPool;
import bayou.async.Async;
import bayou.async.Promise;
import bayou.tcp.TcpChannel;
import bayou.tcp.TcpChannel2Connection;
import bayou.tcp.TcpConnection;
import bayou.util.function.ConsumerX;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Convert a TcpChannel to SslConnection.
 * <p>
 *     Data through the TcpChannel are encrypted SSL records,
 *     while data through SslConnection interface are decrypted/plain text.
 * </p>
 */
public class SslChannel2Connection
{

    final boolean clientMode;
    final Supplier<Long> idGenerator;

    final SSLContext sslContext;
    final ConsumerX<SSLEngine> sslEngineConf;
    final _ByteBufferPool sslBufferPool;

    /**
     * Create a TcpChannel to SslConnection converter
     * <p>
     *     Examples of `sslEngineConf`:
     * </p>
     * <pre>
     *   sslEngineConf = engine-&gt;
     *   {
     *       engine.setWantClientAuth(true);  // server wants client certificates
     *   };
     * </pre>
     * <pre>
     *  sslEngineConf = engine-&gt;
     *  {
     *      SSLParameters sslParameters = engine.getSSLParameters();
     *      sslParameters.setEndpointIdentificationAlgorithm("HTTPS"); // client verifies server host name
     *      engine.setSSLParameters(sslParameters);
     *  };
     * </pre>
     *
     * @param clientMode
     *        whether in client mode or server mode
     * @param sslContext
     *        the SSLContext for connections; null means the {@link javax.net.ssl.SSLContext#getDefault() default}
     * @param sslEngineConf
     *        Action to configure each SSLEngine
     * @param idGenerator
     *        generator for {@link bayou.tcp.TcpConnection#getId() TcpConnection id}.
     */
    public SslChannel2Connection(boolean clientMode, SSLContext sslContext, ConsumerX<SSLEngine> sslEngineConf,
                                 Supplier<Long> idGenerator) throws Exception
    {
        if(sslContext==null) // default context. need system properties, javax.net.ssl.keyStore etc
            sslContext = SSLContext.getDefault();

        if(sslEngineConf==null)
            sslEngineConf = engine->{}; // do nothing

        if(idGenerator==null)
            idGenerator = new AtomicLong(1)::getAndIncrement;

        this.clientMode = clientMode;
        this.sslContext = sslContext;
        this.sslEngineConf = sslEngineConf;
        this.idGenerator = idGenerator;

        // test run.
        SSLEngine engine = sslContext.createSSLEngine();
        engine.setUseClientMode(clientMode);
        sslEngineConf.accept(engine); // test
        SSLSession session = engine.getSession();
        int initNetBufSize = session.getPacketBufferSize();       // 16921
        int initAppBufSize = session.getApplicationBufferSize();  // 16916
        // the two numbers are pretty close, a little bigger than 16K. use same buffer pool
        int maxBufCap = 39 + Math.max(initNetBufSize, initAppBufSize); // 16960
        // we don't really need the code above; just hard-code maxBufCap=16960

        this.sslBufferPool = _ByteBufferPool.forCapacity(maxBufCap); // multiple servers can share one ssl pool
        // one pool for both net/app buffers.
        // why extra capacity: we use the heuristic that if channel.read(buf) fills the buffer full,
        // it's likely that more imm bytes are available.
        // however if client sends one max record that fits exactly in the buf, the heuristic fails.
        // this is not uncommon. so we make the buf a little bigger to avoid the problem.
        // also to make it 64*n bytes. 16960 = 16K + 512 + 64
        // also room for tiny records of 1 or 0 bytes of plain text inserted to counter BEAST SSL attack,
        //    client may send 1 such tiny record followed by a big record, in one write()
    }

    /**
     * Convert a TcpChannel to an SslConnection.
     * <p>
     *     This async action completes when the SSL handshake succeeds or fails.
     * </p>
     */
    public Async<SslConnection> convert(TcpChannel channel)
    {
        Promise<SslConnection> promise = new Promise<>();
        handshake(channel, promise, null);
        return promise;
    }

    /**
     * Convert a server-side TcpChannel to SslConnection <i>or</i> plain TcpConnection.
     * <p>
     *     This is for server side only, `clientMode` must be false.
     * </p>
     * <p>
     *     Initial bytes from the client is used to detect the type of the connection.
     *     The app protocol must
     *     have the client write some data first; and the first byte of the app data
     *     must not be <code>22 (0x16)</code>. For example, HTTP protocol satisfies these requirements.
     * </p>
     */
    public Async<TcpConnection> convert(TcpChannel channel, TcpChannel2Connection plainConverter)
    {
        assert !clientMode;
        // we should be in the server selector thread of chann.
        Promise<TcpConnection> promise = new Promise<>();
        new SslDetector(this, plainConverter, channel, promise).read();
        return promise;
    }

    void handshake(TcpChannel channel, Promise<SslConnection> promise, ByteBuffer initBuffer)
    {
        SSLEngine engine = sslContext.createSSLEngine(channel.getPeerHost(), channel.getPeerPort());
        engine.setUseClientMode(clientMode); // must be called first; it affects internal states
        try
        {
            sslEngineConf.accept(engine);
        }
        catch (Exception e)
        {
            channel.close();
            promise.fail(e);
            return;
        }

        long id = idGenerator.get().longValue(); // gen id early. if handshake fails, app sees gap in conn ids.
        SslHandshaker x = new SslHandshaker(channel, promise, id, sslBufferPool, engine);

        int jNext;
        if(clientMode)
            jNext = SslHandshaker.TO_WRAP;
        else if(initBuffer==null)
            jNext = SslHandshaker.TO_READ;
        else // we already have some bytes from client, skip read, go unwrap
        {
            x.readBuffer = initBuffer;
            jNext = SslHandshaker.TO_UNWRAP;
        }

        // the entire handshake process is done in channel's selector thread (usually the current thread)
        channel.getExecutor().execute(() -> x.jump(jNext));
    }

}
