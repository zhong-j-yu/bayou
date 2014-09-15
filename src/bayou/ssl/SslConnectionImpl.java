package bayou.ssl;

import _bayou._tmp._ByteBufferPool;
import _bayou._tmp._ByteBufferUtil;
import _bayou._tmp._Tcp;
import bayou.async.Async;
import bayou.tcp.TcpChannel;
import bayou.tcp.TcpConnection;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;

class SslConnectionImpl implements SslConnection
{
    _ByteBufferPool bufferPool;
    TcpChannel channel;
    long id;
    SSLEngine engine;

    SslConnectionImpl(TcpChannel channel, SSLEngine engine, _ByteBufferPool bufferPool, long id)
    {
        this.bufferPool = bufferPool;
        this.channel = channel;
        this.id = id;
        this.engine = engine;
        // SSLHandshaker may set peerNetBuffer after construction
    }

    @Override
    public long getId()
    {
        return id;
    }

    @Override public InetAddress getPeerIp(){ return channel.getPeerIp(); }

    @Override
    public SSLSession getSslSession()
    {
        return engine.getSession();
    }

    // note on ssl close
    // in tcp, two sides can close independently, each closes only its outbound direction.
    // in ssl, close semantics is rather odd. receiver of close_notify must immediately close its outbound.
    // SSLEngine follows that requirement. Suppose A sends B a close_notify alert.
    // If engine B receives a close_notify during unwrap(), result is CLOSED/NEED_WRAP, closeOutbound()
    // is triggered internally, no more outbound app data, the next wrap will produce the responding close_notify.
    // After engine A sends the close_notify, unwrap() can still accept app data from B, until B's
    // close_notify is received. But it'd be odd if A wants any app data from B after telling B to close.

    // `A` needs to wait for the responding close_notify from B, only if the connection is to be reused for plain text
    // after SSL session is closed. That's not our case, a connection is either ssl or not, throughout its lifetime.
    // if our side initiates close_notify on write flow, the read flow can wait for responding close_notify
    // (by read() returning close-notify), but that's hardly necessary.
    // if our side receives close_notify from peer, it affects engine immediately, the change will be detected
    // at next engine.wrap(), we'll throw exception then. no need to respond the close_notify.
    // for half-duplex server/client, it's simple: either our side initiates close_notify at end of write flow,
    // or expects peer close_notify during read flow (read() returning close_notify).
    // graceful close: queue close_notify and FIN, then flush write. then call close().
    // the close() method itself does not do these graceful steps.

    Async<Void> closeAction; // updated only in close(), which is on both flows; so both flows can read it safely.
    @Override public Async<Void> close(Duration drainTimeout) // no throw
    {
        if(trace)trace("close");
        // repeated close, allowed. it's bad though - usually user has confusion about the app flows.
        if(closeAction !=null)
            return closeAction;

        // clean up. user may keep a reference to this object for a while. dereference some contained objects.

        engine = null;

        // we can access read/write states in close()

        freeReadBuffers();

        freeWriteBuffers();

        closeAction = _Tcp.close(channel, drainTimeout, bufferPool);
        channel = null;
        return closeAction;
    }

    boolean readError;  // unrecoverable
    boolean needMorePeerBytes =true;
    ByteBuffer peerNetBuffer; // for get
    // SSLHandshaker may set peerNetBuffer after construction

    void freeReadBuffers()
    {
        if(trace)trace("freeReadBuffers");
        if(peerNetBuffer !=null)
        {
            if(trace)trace("free peerNetBuffer");
            bufferPool.checkIn(peerNetBuffer);
            peerNetBuffer = null;
        }

        if(unread!=null)
            unread = null;
    }

    ByteBuffer unread;

    @Override
    public void unread(ByteBuffer bb) throws IllegalStateException
    {
        if(closeAction !=null)
            throw new IllegalStateException("closed");

        if(unread!=null)
            throw new IllegalStateException("consecutive unread not supported");

        // readState should be 0/normal. we don't check. next read() will get `bb` regardless of readState.

        unread = bb;
    }

    ByteBuffer readDelayed;  // similar to unread, used internally.

    // CLOSE_NOTIFY, FIN, STALL, or data.
    // if result is data, we guarantee that it's non empty (unless it is from prev unread() which may be empty)
    @Override public ByteBuffer read() throws Exception
    {
        if(trace)trace("read");
        if(closeAction !=null)
            throw new IllegalStateException("closed");

        if(unread!=null)
        {
            ByteBuffer bb = unread;
            unread = null;
            return bb;
        }

        if(readDelayed!=null)
        {
            ByteBuffer bb = readDelayed;
            readDelayed = null;
            return bb;
        }

        if(readError)
            throw new IllegalStateException("prev read() error"); // no detail; see prev msg

        ByteBuffer peerAppBuffer = ByteBuffer.allocate( bufferPool.getBufferCapacity() );
        // an ordinary heap buffer
        try
        {
            ByteBuffer result = read_unwrap(peerAppBuffer); // CLOSE_NOTIFY, FIND, STALL, DATA
            if(trace)trace("read_unwrap ", result);
            if(result!=DATA)
                return result;

            // DATA 0<data.length<=16K
            if(peerAppBuffer.remaining()==1 && peerNetBuffer.hasRemaining())
            {
                // a record of 1 byte plain text. this is very likely done by sender
                // to counter chosen plain text issues on CBC mode cipher suites in SSLv3/TLS1.0.
                // and it's very likely there's a following record imm available.
                // let's try to obtain the 2nd record and combine the 2.
                // tested with SSLSocket, and IE/Firefox HTTPS post

                assert peerAppBuffer.position()==0 && peerAppBuffer.limit()==1; // capacity: 16960
                peerAppBuffer.position(1).limit(peerAppBuffer.capacity());
                ByteBuffer peerAppBuffer_2 = peerAppBuffer.slice(); // capacity: 16959
                peerAppBuffer.position(0).limit(1);
                // peerAppBuffer2 is big enough as app buffer, which requires 16916 bytes.

                // likely next record is already read, and we won't call channel.read()
                result = read_unwrap(peerAppBuffer_2); // CLOSE_NOTIFY, FIN, STALL, DATA
                if(trace)trace("read_unwrap again ", result);

                if(result==DATA) // cool, we got another non-empty data record
                    peerAppBuffer.limit( 1+peerAppBuffer_2.remaining() );  // could contain 16K+1 bytes
                else if(result!=STALL) // CLOSE_NOTIFY or FIN
                    readDelayed = result;  // return 1 byte now. next read() sees CLOSE_NOTIFY or FIN.
                // else if STALL
                //     no more record imm available. return the 1 byte.
            }

            return _ByteBufferUtil.shrink(peerAppBuffer, 0.75);
            // often peerAppBuffer is near full with 16KB data, so no shrink is done.
        }
        catch(Exception t)
        {
            readError = true;
            freeReadBuffers();
            throw t;
        }
        finally
        {
            // even if no error, we want to free buffers if possible.
            // we can't keep empty buffers for imm reuse; it's unknown when they'll be needed again.
            // e.g. after read(), user may do write stuff, and stuck in write select.
            // maybe we can add a method so user can prompt us to do clean up at proper time.
            if(peerNetBuffer !=null && !peerNetBuffer.hasRemaining())
            {
                if(trace)trace("free peerNetBuffer");
                bufferPool.checkIn(peerNetBuffer);
                peerNetBuffer = null;
                needMorePeerBytes =true;
            }
        }
    }

    static private final ByteBuffer R_U_AGAIN = ByteBuffer.wrap(new byte[0]); // flag: try read_unwrap() again
    static private final ByteBuffer APP0      = ByteBuffer.wrap(new byte[0]); // flag: received a record of 0 plain text
    static private final ByteBuffer DATA      = ByteBuffer.wrap(new byte[0]); // flag: received a record, non empty


    ByteBuffer read_unwrap(ByteBuffer peerAppBuffer) throws Exception
    {
        while(true)
        {
            ByteBuffer result = read_unwrap_2(peerAppBuffer);
            if(result!=R_U_AGAIN)
                return result;  // CLOSE_NOTIFY, FIN, STALL, DATA

            // R_U_AGAIN: peerNetBuffer contains an incomplete record; channel is likely readable.
            // try read again then unwrap. if there's no APP0, the retry would not return R_U_AGAIN,
            // because either the record is completed, or channel is not likely readable.
        }
    }

    ByteBuffer read_unwrap_2(ByteBuffer peerAppBuffer) throws Exception
    {
        if(trace)trace("read_unwrap", "needMorePeerBytes", needMorePeerBytes, "peerNetBuffer!=null ", peerNetBuffer !=null);
        if(needMorePeerBytes) // we know for sure net buffer contains incomplete record. must read more.
        {
            if(peerNetBuffer ==null)
                peerNetBuffer = bufferPool.checkOut(); // for put
            else // briefly make peerNetBuffer for put; change it back to for get asap.
                peerNetBuffer.compact();
            // compact() may cost copying. we can't avoid it, since engine.unwrap() requires a single src buffer.
            // not a big deal, since most ssl records are big (~16K) or tiny (~30, for 1/0 plain text)
            // observed: upload big file, curl/Firefox/IE: most copies are ~500 bytes.

            if(trace)trace("before channel.read", peerNetBuffer);
            int r = channel.read(peerNetBuffer); // throws.
            if(trace)trace("after channel.read", peerNetBuffer);

            peerNetBuffer.flip(); // for get
            // if channel.read() throws, flip() not done. that's ok. the buffer is to be imm freed.

            // no imm bytes from peer. user should awaitReadable(), entering read select.
            // note even after read select, we may still read 0 bytes (spurious wakeup)
            if(r==0)
                return STALL;

            if(r==-1)
                return TCP_FIN;

            // r>0. we got more bytes, do try unwrap().
            needMorePeerBytes = false;
        }
        else // not sure net buffer contains incomplete record. try unwrap() to find out
        {
            assert peerNetBuffer !=null;
        }

        while(true)
        {
            ByteBuffer unwrapResult = unwrap(peerAppBuffer);
            if(unwrapResult!=APP0)
                return unwrapResult; // R_U_AGAIN, CLOSE_NOTIFY, STALL, DATA
            // otherwise, unwrap() returns APP0, it's a record of 0 plain text. allowed by SSL.
            // particularly, OpenSSL inserts such empty records against BEAST SSL attack.
            // this case is tested by curl which uses OpenSSL
            // we don't want to return an empty result to user. skip it, go ahead unwrap next record.
        }
    }

    // return:
    //    R_U_AGAIN: read_unwrap() again
    //    APP0: record of 0 plain text
    //    CLOSE_NOTIFY:
    //    STALL: incomplete record
    //    DATA
    ByteBuffer unwrap(ByteBuffer peerAppBuffer) throws Exception
    {
        if(trace)trace("unwrap");
        peerAppBuffer.clear(); // for put. unwrap() may be called again with peerAppBuffer flipped for get.

        // possible that peerNetBuffer is empty here; then unwrap result is UNDERFLOW.
        SSLEngineResult result = engine.unwrap(peerNetBuffer, peerAppBuffer); // throws
        if(trace)trace("engine.unwrap", result);
        // possible that some bytes are consumed, but 0 bytes are produced
        // observed: curl(OpenSSL) sends empty records (APP0), 29 bytes consumed, 0 produced.

        peerAppBuffer.flip(); // for get
        // if engine.unwrap() throws, flip() not done. that's ok. the buffer is to be imm freed.

        switch(result.getStatus())
        {
            case CLOSED: // got a close_notify record from peer.
                return SSL_CLOSE_NOTIFY;

            case BUFFER_OVERFLOW: // don't accept larger record. treat it as protocol error
                throw new SSLException("peer ssl record too large");

            case BUFFER_UNDERFLOW: // not a complete record. need more bytes from peer.
                // if we already have max bytes for a record, peer is sending large record.
                if(peerNetBuffer.remaining()== peerNetBuffer.capacity())
                    throw new SSLException("peer ssl record too large");
                // it's possible remain=0

                // need more bytes from peer. either read imm, or await readable. both ok.
                // choose the more likely case; it's ok if guess is wrong.
                needMorePeerBytes = true;
                if(peerNetBuffer.limit()== peerNetBuffer.capacity()) // very common
                {
                    // last channel.read() filled up the buffer. it's likely that more bytes are imm available.
                    if(trace)trace("R_U_AGAIN");
                    return R_U_AGAIN; // try read_unwrap() again.
                    // next read_unwrap() should not reach here (unless there are APP0 records),
                    // because after peerNetBuffer.compact(), channel.read(), peerNetBuffer.flip(),
                    // position=0, limit=remaining. if limit=capacity, remaining=capacity, throw "record too large"
                }
                else // last channel.read() didn't fill up the buffer. likely no more imm bytes.
                {
                    if(trace)trace("STALL");
                    // user should do conn.awaitReadable(). possible that conn is actually imm readable.
                    return STALL;
                }

            case OK: break;
            default: throw new AssertionError();
        }
        // status=OK
        if(result.getHandshakeStatus()!= SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)
        {
            // peer tries to renegotiate. if we respond, we need to barge into write flow, a little difficult.
            // for now, don't support renegotiation. as protocol error. user should close connection immediately.
            throw new SSLException("peer SSL renegotiation; not supported");
            // no flag for write flow. engine will reveal renegotiation attempt when write flow tries to wrap()
        }

        // ok, not handshaking.
        // we get a record of app data

        if(!peerAppBuffer.hasRemaining()) // empty record
        {
            if(trace)trace("APP0");
            return APP0;  // we'll skip it
        }

        // finally, we get some app data that we can return to user.
        if(trace)trace("app data");
        return DATA; // guarantee: not empty
    }

    @Override public Async<Void> awaitReadable(boolean accepting)
    {
        if(trace)trace("requestRead");
        if(closeAction !=null)
            throw new IllegalStateException("closed");

        if(unread!=null) // readable now
        {
            return Async.VOID;
        }

        if(!needMorePeerBytes)
        {
            // we have some data in net buffer, that *may* contain the complete next record.
            // so let's say we are readable now. even if it's not, spurious wakeup is ok,
            // next read will see incomplete record and turn on needMorePeerBytes.
            return Async.VOID;
        }

        // we know for sure net buffer contains incomplete record. must read more.
        if(trace)trace("enter read select");
        return channel.awaitReadable(accepting);
        // spurious wakeup is possible: next channel.read() still doesn't bring a complete record.
    }







    // write ======
    // queueWrite(appData); queueWrite(close_notify/fin); write();
    // we have app data, ssl buffer and tcp buffer.
    // we don't do app->ssl in queueWrite(). app data can be small, we don't want one ssl record for each.
    // so we want to accumulate app data, till write() which will try to do app -> ssl -> tcp.
    //
    //     bsQueue -> bbQueue --engine.wrap()--> localNetBuffer --channel.write()--> tcp
    //
    // r1: total remaining bytes in app data (not consumed by ssl engine yet)
    // rx: bytes of app data encoded in ssl buffer, and the ssl buffer hasn't been flushed yet.
    //     the ssl record is opaque/atomic to us; if it's not flushed, none of plain text is considered written.
    // wr: wr=r1+rx. total remaining bytes of app data that *we are not sure* have ended up in tcp buffer.
    // wr>=r1. app data may be consumed and converted into ssl buffer, but not flushed in tcp buffer yet.
    // for write(), the limiting factor is tcp buffer; if it overflows, wr>0.
    // user need awaitWritable() and try write() again in callback.
    // if wr=0, all app data is flushed to tcp buffer. no further write() needed, until more app data queued.
    // close_notify or FIN is counted as 1 byte of app data.

    boolean writeError; // unrecoverable

    ArrayDeque<ByteBuffer> bbQueue = new ArrayDeque<>(16);
    long bbTotal;  // // remaining app data

    byte stateCloseNotify; // [0] none [1] queued [2] wrap()-ed [3] written
    byte stateFin;         // [0] none [1] queued [2] sent
    ByteBuffer localNetBuffer; // for one ssl record. for get.
    int rx; // bytes of plain text in the current ssl record. (1 for close_notify record)

    long r1(){ return bbTotal + (stateCloseNotify==1 ? 1 : 0 ) + (stateFin==1 ? 1 : 0 ); }
    long wr(){ return r1() + rx; }

    void freeWriteBuffers()
    {
        if(trace)trace("freeWriteBuffers");

        if(localNetBuffer !=null)
        {
            if(trace)trace("free localNetBuffer");
            bufferPool.checkIn(localNetBuffer);
            localNetBuffer =null;
        }

        bbQueue.clear();
    }

    @Override public long getWriteQueueSize()
    {
        if(closeAction !=null)
            return 0L; //uh?
        return wr();
    }

    @Override public long queueWrite(ByteBuffer bb)
    {
        if(trace)trace("queueWrite", bb);
        if(closeAction !=null)
            throw new IllegalStateException("closed");
        if(writeError)
            throw new IllegalStateException("prev write() error");

        if(stateFin!=0)
            throw new IllegalStateException("TCP_FIN was queued before");

        if(bb== TcpConnection.TCP_FIN)
        {
            stateFin = 1;
            return wr();
        }

        if(stateCloseNotify!=0)
            throw new IllegalStateException("SSL_CLOSE_NOTIFY was queued before");

        if(bb== SSL_CLOSE_NOTIFY)
        {
            stateCloseNotify = 1;
            return wr();
        }

        bbQueue.addLast(bb);
        bbTotal += bb.remaining();
        return wr();
    }

    // return difference in wr
    @Override public long write() throws Exception
    {
        if(trace)trace("write");
        if(closeAction !=null)
            throw new IllegalStateException("closed");
        if(writeError)
            throw new IllegalStateException("prev write() error");

        long wr_before = wr();
        while(true)
        {
            if(localNetBuffer !=null) // curr ssl record, not all bytes were written. for get
            {
                if(trace)trace("before channel.write", localNetBuffer);
                channel.write(localNetBuffer); // throws, recoverable
                if(trace)trace("after channel.write", localNetBuffer);

                if(localNetBuffer.hasRemaining()) // fail to flush ssl record. tcp buffer full
                    return wr_before - wr(); // may return 0, but progress may have been made.

                // flushed
                if(trace)trace("flushed");
                rx=0;
                // keep the empty localNetBuffer, we may reuse it immediately. if not we will free it promptly.
            }

            if(bbTotal==0 && stateCloseNotify!=1) // nothing more to wrap and write
            {
                if(trace)trace("nothing more to wrap and write");
                if(localNetBuffer !=null)
                {
                    if(trace)trace("free localNetBuffer");
                    bufferPool.checkIn(localNetBuffer);
                    localNetBuffer =null;
                }

                if(stateCloseNotify ==2) // wrapped, then flushed
                {
                    if(trace)trace("close_notify sent");
                    stateCloseNotify =3;
                }

                if(stateFin==1)
                {
                    channel.shutdownOutput(); // throws. unlikely. doesn't matter.
                    if(trace)trace("FIN sent");
                    stateFin=2;
                }

                return wr_before - wr(); // wr()==0
            }

            // wrap app data or close_notify
            if(trace)trace("localNetBuffer", localNetBuffer);
            if(localNetBuffer !=null)
                localNetBuffer.clear(); // reuse it.
            else
                localNetBuffer = bufferPool.checkOut(); // throws.
            // localNetBuffer for put
            try
            {
                // it would be nice for last app data and close_notify to be in the same localNetBuffer
                // so one channel.write() may flush both. unfortunately engine.wrap() requires a
                // max buffer (remaining=maxRecordSize). so we must do two separately.
                //
                if(bbTotal>0)
                    wrapAppData();
                else // ra==0 && stateCloseNotify==1
                    wrapCloseNotify();  // stateCloseNotify -> 2
            }
            catch(Exception t)
            {
                // it's possible net buffer has some bytes; but no reason to write them.
                writeError=true;  // unrecoverable. trash all.
                freeWriteBuffers();
                throw t;
            }

            // wrap succeeded, net buffer has some bytes, go write them.
            localNetBuffer.flip(); // for get
            assert localNetBuffer.hasRemaining();
            if(trace)trace("continue to channel.write()");

            // observation of localNetBuffer content after wrap():
            // if there are plenty of app data, wrap() produces 15914 bytes, containing 2 ssl records,
            //   for plain text of 1 byte and 15846 bytes.
            // close_notify record ~ 37 bytes.
        }
    }

    void wrapAppData() throws Exception
    {
        assert bbTotal>0;
        if(trace)trace("wrap app data");

        ByteBuffer[] bbs = bbQueue.toArray( new ByteBuffer[bbQueue.size()] );

        // note: engine.wrap() may produce 2 records, with 1st record containing 1 byte of plain text,
        // to counter chosen plain text issues on CBC mode cipher suites in SSLv3/TLS1.0.
        // therefore we may consume less than 16KB from source buffer even if there are more bytes.

        SSLEngineResult result = engine.wrap(bbs, localNetBuffer); // throws
        if(trace)trace("engine.wrap", result);
        switch(result.getStatus())
        {
            case BUFFER_UNDERFLOW: // impossible
                throw new AssertionError();
            case BUFFER_OVERFLOW: // also impossible; net buffer was empty with sufficient room.
                throw new AssertionError();

            // read flow just received peer close_notify. we can't write any more data to peer
            case CLOSED:
                throw new SSLException("connection is closed by peer");

            case OK: break;
            default: throw new AssertionError();
        }
        // status=OK
        if(result.getHandshakeStatus()!= SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)
        {
            // read flow just got a renegotiation request from peer. see unwrap(). not supported.
            // most likely close() has already been called after read flow got the re-neg error,
            // so it's rare that we can reach here. write flow should end imm.
            throw new SSLException("peer SSL renegotiation; not supported");
        }
        assert result.bytesConsumed()>0 && result.bytesProduced()>0;
        rx = result.bytesConsumed();
        bbTotal -= rx;
        if(trace)trace("rx, bbTotal", rx, bbTotal);
        while(!bbQueue.isEmpty())
        {
            ByteBuffer bb = bbQueue.peekFirst();
            if(bb.hasRemaining())
                break;

            bbQueue.removeFirst();
        }
    }

    void wrapCloseNotify() throws Exception
    {
        assert bbTotal==0 && stateCloseNotify ==1;
        if(trace)trace("wrap close_notify");

        engine.closeOutbound(); // no throw?

        SSLEngineResult result = engine.wrap(SslHandshaker.BB0, localNetBuffer); // throws
        if(trace)trace("engine.wrap", result);
        switch(result.getStatus())
        {
            case BUFFER_UNDERFLOW: // impossible
                throw new AssertionError();
            case BUFFER_OVERFLOW: // also impossible; net buffer was empty with sufficient room.
                throw new AssertionError();

            case OK: // not sure why. maybe peer sent re-neg, and our side is responding? not supported.
                throw new SSLException("unexpected wrap() result after closeOutbound(): "+result);

            case CLOSED: break; // this is the expected result

            default: throw new AssertionError();
        }
        // don't care about handshake status. it could be NEED_UNWRAP, expecting peer response,
        // or it could be NOT_HANDSHAKING (by chance just responded a prev peer close_notify)
        stateCloseNotify =2;
        rx = 1;
    }



    @Override public Async<Void> awaitWritable()
    {
        if(trace)trace("requestWrite");
        if(closeAction !=null)
            throw new IllegalStateException("closed");

        // this method should be called after prev write() returns wr>0.
        // tcp send buffer was full. wait for writable. then callback try write() again.
        if(trace)trace("enter write select");
        return channel.awaitWritable();
    }

    @Override
    public Executor getExecutor()
    {
        return channel.getExecutor();
    }

    static final boolean trace = false;
    int connId;
    void trace(Object... args)
    {
        trace0(connId, args);
    }
    static int traceId = 0;
    synchronized static void trace0(int connId, Object... args)
    {
        System.out.print("Ssl Conn   " +connId+ "   " +(traceId++)+"   ");
        for(Object arg : args)
        {
            System.out.print(arg);
            System.out.print(" ");
        }
        System.out.println();
    }
}