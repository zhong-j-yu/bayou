package bayou.tcp;

import _bayou._tmp._ByteBufferPool;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Non-blocking TCP server (extended).
 * <p>
 *     A subclass overrides {@link #onConnect(TcpConnection)} to handle each new connection.
 * </p>
 * <p>
 *     This class extends {@link TcpServer} to provide SSL support, and a new abstraction {@link TcpConnection}.
 * </p>
 * <h4>SSL</h4>
 * <p>
 *     The server can be in 3 modes:
 * </p>
 * <ul>
 *     <li>plain only - all connections are plain connections. This is the default.</li>
 *     <li>ssl only - all connections are SSL connections.</li>
 *     <li>
 *         mixed mode - support both plain and SSL connections on the same port. Initial bytes from the client
 *         is used to detect the type of the connection.
 *     </li>
 * </ul>
 * <p>
 *     For the mixed mode to work, the app protocol must
 *     have the client to write some data first; and the first byte of the data (in plain form)
 *     must not be <code>22 (0x16)</code>. For example, HTTP protocol satisfies these requirements.
 * </p>
 * <p>
 *     SSL is enabled by turning on {@link #confSslEnabled}. An {@link SSLContext} is needed for SSL connections
 * </p>
 * <ul>
 *     <li>
 *         If {@link #confSslContext} is non-null, it is used.
 *     </li>
 *     <li>
 *         Otherwise if {@link #confSslKeyStoreFile} is non-null,
 *         the file is used (with {@link #confSslKeyStorePassword}) to create an SSLContext.
 *     </li>
 *     <li>
 *         Otherwise, {@link SSLContext#getDefault()} is used. Typically it requires
 *         system properties <code>javax.net.ssl.keyStore/keyStorePassword</code>.
 *     </li>
 * </ul>
 */
abstract public class TcpServerX extends TcpServer
{
    /**
     * Whether to enable plain connections.
     * <p><code>
     *     default: true
     * </code></p>
     */
    public boolean confPlainEnabled = true;

    /**
     * Whether to enable SSL connections.
     * <p><code>
     *     default: false
     * </code></p>
     */
    public boolean confSslEnabled = false;

    /**
     * SSLContext for SSL connections.
     * <p><code>
     *     default: null
     * </code></p>
     */
    public SSLContext confSslContext = null;

    /**
     * SSL key store file.
     * <p><code>
     *     default: null
     * </code></p>
     */
    public String confSslKeyStoreFile = null;

    /**
     * SSL key store file password.
     * <p><code>
     *     default: null
     * </code></p>
     */
    public String confSslKeyStorePassword = null;

    /**
     * Timeout for completing the SSL handshake on an SSL connection.
     * <p><code>
     *     default: 10 seconds
     * </code></p>
     */
    public Duration confSslHandshakeTimeout = Duration.ofSeconds(10); // max handshake time

    // preferred buffer size for calling NbChannel.read()/write().
    // not to be confused with socket receive/send buffer size.
    // not published for now. not sure if users need it.
    int confReadSize = 16*1024;
    int confWriteSize = 16*1024;
    // currently, ssl conn doesn't respect these 2 values.
    // the max size for NbChannel.read()/write() is 16921, max size of one ssl record.
    // if r/w buffer size is must larger, we may consider to r/w multiple ssl records at a time.

    final AtomicLong idSeq = new AtomicLong(0);

    /**
     * Create a TcpServerX. The server is in <code>init</code> state.
     */
    protected TcpServerX()
    {
        super();


    }

    /**
     * Start the server. See <a href="TcpServer.html#life-cycle">Life Cycle</a>.
     */
    public void start() throws Exception
    {
        init();

        super.start();
    }

    // use default expiration for buffer pools

    _ByteBufferPool plainReadBufferPool;
    _ByteBufferPool plainWriteBufferPool;

    SSLContext sslContext;
    _ByteBufferPool sslBufferPool;


    // to test without real network: init() without super.start(), then feed onAccept() with mock channel
    void init() throws Exception
    {
        if(!confPlainEnabled && !confSslEnabled)
            throw new IllegalStateException("Either confPlainConnection or confSslConnection must be true (or both).");

        if(confPlainEnabled)
        {
            plainReadBufferPool  = _ByteBufferPool.forCapacity(confReadSize);
            plainWriteBufferPool = _ByteBufferPool.forCapacity(confWriteSize);
        }

        if(confSslEnabled)
        {
            if(confSslContext!=null)
                sslContext = confSslContext; // use it
            else if(confSslKeyStoreFile !=null) // use the key store file to init the context
                sslContext = initSslContext(confSslKeyStoreFile, confSslKeyStorePassword);
            else // default context. need system properties, javax.net.ssl.keyStore etc
                sslContext = SSLContext.getDefault();

            SSLEngine engine = sslContext.createSSLEngine(); // as a test/trial
            engine.setUseClientMode(false);
            SSLSession session = engine.getSession();

            int initNetBufSize = session.getPacketBufferSize();       // 16921
            int initAppBufSize = session.getApplicationBufferSize();  // 16916
            // the two numbers are pretty close, a little bigger than 16K. use same buffer pool
            int maxBufCap = 39 + Math.max(initNetBufSize, initAppBufSize); // 16960
            sslBufferPool = _ByteBufferPool.forCapacity(maxBufCap); // multiple servers can share one ssl pool
            // one pool for both net/app buffers.
            // why extra capacity: we use the heuristic that if channel.read(buf) fills the buffer full,
            // it's likely that more imm bytes are available.
            // however if client sends one max record that fits exactly in the buf, the heuristic fails.
            // this is not uncommon. so we make the buf a little bigger to avoid the problem.
            // also to make it 64*n bytes. 16960 = 16K + 512 + 64
            // also room for tiny records of 1 or 0 bytes of plain text inserted to counter BEAST SSL attack,
            //    client may send 1 such tiny record followed by a big record, in one write()
        }

    }

    /**
     * Implements super's onAccept(TcpChannel) method.
     * <p>
     *     This implementation creates a plain or ssl {@link TcpConnection}
     *     and invokes {@link #onConnect(TcpConnection)}.
     * </p>
     *
     */
    @Override
    final // can't see why subclass would need to override it
    protected void onAccept(TcpChannel channel)
    {
        if(confPlainEnabled && confSslEnabled)
        {
            SslDetector.start(this, channel);
        }
        else if(confSslEnabled)
        {
            SslHandshaker.start(this, channel);
        }
        else // plain only
        {
            PlainTcpConnection.start(this, channel);
        }
    }

    /**
     * Handle a new connection.
     * <p>
     *     This method is invoked when a new incoming connection is established;
     *     subclass overrides this method to handle it.
     * </p>
     * <p>
     *     This method must not block; must not throw Exception.
     * </p>
     */
    abstract protected void onConnect(TcpConnection connection);


    // note some hardcoded values: JKS, SunX509, TLS. they probably don't need conf.
    static SSLContext initSslContext(String confKeyStoreFile, String confKeyStorePassword) throws Exception
    {
        char[] password = confKeyStorePassword==null? null : confKeyStorePassword.toCharArray();

        KeyStore ks = KeyStore.getInstance("JKS"); // java key store
        try(FileInputStream ksInput = new FileInputStream(confKeyStoreFile))
        {   ks.load(ksInput, password);   }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, password);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        return sslContext;
    }

}
