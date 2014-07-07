package bayou.tcp;

import _bayou._tmp._ByteBufferPool;
import _bayou._tmp._ByteBufferUtil;
import bayou.async.Async;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

class PlainTcpConnection implements TcpConnection
{
    static void start(TcpServerX server, TcpChannel channel)
    {
        start(server, channel, null);
    }
    static void start(TcpServerX server, TcpChannel channel, ByteBuffer initBuffer)
    {
        server.onConnect( new PlainTcpConnection(server, channel, initBuffer) );
    }

    _ByteBufferPool readBufferPool;
    _ByteBufferPool writeBufferPool;
    TcpChannel channel;
    long id;

    ByteBuffer unread;

    PlainTcpConnection(TcpServerX server, TcpChannel channel, ByteBuffer initBuffer)
    {
        if(trace)trace("cstor()");
        this.channel = channel;
        this.id = server.idSeq.incrementAndGet();

        this.readBufferPool = server.plainReadBufferPool;
        this.writeBufferPool = server.plainWriteBufferPool;

        this.cbM = server.confWriteSize;

        if(initBuffer!=null) // from SslDetector, from server.plainReadBufferPool
        {
            if(trace)trace("with initBuffer");
            assert initBuffer.hasRemaining();
            unread = _ByteBufferUtil.copyOf(initBuffer);
            server.plainReadBufferPool.checkIn(initBuffer);
        }
    }

    @Override
    public long getId()
    {
        return id;
    }

    @Override public InetAddress getRemoteIp(){ return channel.getRemoteIp(); }

    @Override public boolean isSsl(){ return false; }

    // close() must be called on both read and write flow.
    Async<Void> closeAction; // updated only in close(), which is on both flows; so both flows can read it safely.
    @Override public Async<Void> close(Duration drainTimeout)
    {
        if(trace)trace("close()");
        // repeated close, allowed. it's bad though - usually user has confusion about the app flows.
        if(closeAction !=null)
            return closeAction;

        if(unread!=null)
            unread = null;

        freeWriteBuffers();

        closeAction = TcpUtil.close(channel, drainTimeout, readBufferPool);
        channel = null;
        return closeAction;
    }

    @Override
    public void unread(ByteBuffer bb) throws IllegalStateException
    {
        if(closeAction !=null)
            throw new IllegalStateException("closed");

        if(unread!=null)
            throw new IllegalStateException("consecutive unread not supported");

        unread = bb;
    }

    // if result is data, we guarantees that it's non empty
    @Override public ByteBuffer read() throws Exception
    {
        if(trace)trace("read()");
        if(closeAction !=null)
            throw new IllegalStateException("closed");

        if(unread!=null)
        {
            ByteBuffer bb = unread;
            unread = null;
            if(trace)trace("return unread");
            return bb;
        }

        ByteBuffer readBuffer = readBufferPool.checkOut(); // throws
        try
        {
            int r = channel.read(readBuffer); // throws
            if(trace)trace("after channel.read()", "readBuffer", readBuffer);

            if(r==-1)
                return TCP_FIN;

            if(r==0)
                return STALL;

            // r>0
            readBuffer.flip();
            return _ByteBufferUtil.copyOf(readBuffer);
        }
        finally
        {
            readBufferPool.checkIn(readBuffer);
        }
    }

    @Override public Async<Void> awaitReadable(boolean accepting)
    {
        if(trace)trace("requestRead()");
        if(closeAction !=null)
            throw new IllegalStateException("closed");

        if(unread!=null) // readable now
        {
            return Async.VOID;
        }

        if(trace)trace("enter read select");
        return channel.awaitReadable(accepting);
    }









    // write ==================================================================

    // from user; hasn't be processed yet. some are direct, some are not
    ArrayDeque<ByteBuffer> ubbQueue = new ArrayDeque<>(16);
    long ubbTotal;

    // direct buffers. some are from user direct bb. others are copies
    ArrayDeque<ByteBuffer> dbbQueue = new ArrayDeque<>(16);
    long dbbTotal;

    // the dbb is our copy of user's non-direct bb
    ArrayDeque<Boolean> dbbIsCopy = new ArrayDeque<>(16);

    int stateFin; // [0] none [1] queued [2] sent

    long wr()
    {
        return ubbTotal + dbbTotal + (stateFin==1? 1:0 );
    }

    @Override public long getWriteQueueSize()
    {
        if(closeAction !=null)
            throw new IllegalStateException("closed");

        return wr();
    }

    @Override public long queueWrite(ByteBuffer bb)
    {
        if(bb== TcpConnection.SSL_CLOSE_NOTIFY)
            return wr();
        // just ignore CLOSE_NOTIFY. the desired effect is that
        //    queueWrite(SSL_CLOSE_NOTIFY)
        // is exactly the same as
        //    if(isSsl())
        //        queueWrite(SSL_CLOSE_NOTIFY)

        if(trace)trace("queueWrite() "+bb);
        if(closeAction !=null)
            throw new IllegalStateException("closed");

        if(stateFin!=0)
            throw new IllegalStateException("TCP_FIN was queued before");

        if(bb== TcpConnection.TCP_FIN)
        {
            stateFin = 1;
        }
        else
        {
            ubbQueue.addLast(bb);
            ubbTotal += bb.remaining();
        }

        return wr();
    }

    @Override public long write() throws Exception
    {
        if(trace)trace("write()");
        if(closeAction !=null)
            throw new IllegalStateException("closed");

        long wr_before = wr();
        if(wr_before==0)
            return 0;

        do
        {
            ubb2dbb();
            dbb2channel(); // throws
        }
        while( dbbQueue.isEmpty() && !ubbQueue.isEmpty() );
        // last channel.write() wrote all dbb, the channel is very likely still writable.
        // and we have more ubb to write. so let's try again.

        if(copyBuffer!=null && cbX==cbY)
            cbDispose();
        // cb is empty, return it to pool. don't keep it, we don't know when it'll be needed again.

        if(stateFin==1 && dbbQueue.isEmpty()) // => bsQueue.isEmpty()
        {
            // everything wrote. FIN queued. send FIN now
            channel.shutdownOutput(); // throws
            stateFin=2;
            assert wr()==0;
            if(trace)trace("FIN sent");
        }

        return wr_before - wr();
    }

    void ubb2dbb()
    {
        // we need to convert user ByteBuffer ubb to *direct* ByteBuffer dbb.
        // ubb can be red or green. ubb is green if it's a direct ByteBuffer.
        // red ubb need to be copied to direct ByteBuffer. consecutive red ubb can be merged into 1 red region.
        // reds are copied to 1 copyBuffer, which is a ring of capacity `cbM`.
        // the cb ring may contain multiple red regions (separated by green ubb)
        // example:
        //     ubbQueue: 1red  2red  3green 4green 5red 6green
        // afterwards
        //   copyBuffer: R12 R5
        //     dbbQueue: R12 3green 4green R5 6green

        // if we go on as long as there's room in copyBuffer, we may end up with huge dbbTotal due to green ubb.
        //     then next channel.write() likely stalls, likely cb is not free and we need to keep it around.
        // instead, we quit early when dbbTotal exceeds cbM due to green ubb.
        // when next channel.write() stalls (note we loop until it stalls),
        // suppose data in cb is smaller than green ubb, it's likely that cb becomes free and disposable.
        // use case: http chunked: red green red green ... where reds are small, greens are big.
        // also user prefers to write only cbM each time for whatever reason. we should respect that.

        int cbY0 = cbY;  // [cbY0, cbY) - current red region, copied to cb, but not yet in dbbQueue
        while( !ubbQueue.isEmpty() && (cbY-cbY0)+ dbbTotal < cbM )
        {
            ByteBuffer ubb = ubbQueue.removeFirst();
            int ubb_len = ubb.remaining();

            if(ubb.isDirect()) // green
            {
                if(cbY>cbY0) // red region before me
                {
                    cb2dbb(cbY0);
                    cbY0 = cbY;
                }

                ubbTotal -= ubb_len;
                dbbQueue.addLast(ubb);
                dbbIsCopy.addLast(Boolean.FALSE);
                dbbTotal += ubb_len;
            }
            else
            {
                // ubb is red. copy to copyBuffer. there may not be enough room for entire ubb.
                int copied = bb2cb(ubb);   // cbY increased
                ubbTotal -= copied;
                if(copied<ubb_len)  // cb full. loop will end.
                    ubbQueue.addFirst( ubb );  // ubb has more remaining; for the next time
            }
        }

        if(cbY>cbY0)
            cb2dbb(cbY0);
    }

    void dbb2channel() throws Exception
    {
        ByteBuffer[] bbs = dbbQueue.toArray( new ByteBuffer[dbbQueue.size()] );
        // bbs can be huge, well above cbM. we write them at once.
        // if that's a problem for some tcp stack, (?) and smaller portion is preferred per write,
        // user will have to cut huge ubb into small ones, and keep wr small.

        long w = channel.write(bbs); // throws
        if(trace)trace("channel.write", w);

        dbbTotal -= w;
        while(!dbbQueue.isEmpty())
        {
            ByteBuffer dbb = dbbQueue.peekFirst();
            Boolean isRed = dbbIsCopy.peekFirst();

            if( isRed.booleanValue())   // dbb is a red region in cb. it might be consumed partly or fully.
                cbFree(dbb); // consumed part of dbb, [0,position), can be freed in cb.

            if(dbb.hasRemaining())
                break;

            dbbQueue.removeFirst();
            dbbIsCopy.removeFirst();
        }
    }

    void freeWriteBuffers()
    {
        if(copyBuffer!=null)
            cbDispose();

        dbbQueue.clear();

        dbbIsCopy.clear();

        ubbQueue.clear();

    }


    @Override public Async<Void> awaitWritable()
    {
        if(trace)trace("requestWrite");
        if(closeAction !=null)
            throw new IllegalStateException("closed");

        // this method should be called after prev write() returns r2>0.
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
    static AtomicInteger connIdGen = new AtomicInteger(0);
    int connId;
    void trace(Object... args)
    {
        if(connId==0)
            connId = connIdGen.incrementAndGet();

        trace0(connId, args);
    }
    static int traceId = 0;
    synchronized static void trace0(int connId, Object... args)
    {
        System.out.print("PlainNbConnection   " +connId+ "   " +(traceId++)+"   ");
        for(Object arg : args)
        {
            System.out.print(arg);
            System.out.print(" ");
        }
        System.out.println();
    }







    // copy buffer management ===================================================================================

    final int cbM;
    ByteBuffer copyBuffer; // limit=capacity, never touched. position undefined, reset it before each access.
    // it's a ring, mod cbM.  [cbX, cbY) contains data, [cbY, cbX+cbM) is free space.
    int cbX; // 0 <= cbX < cbM
    int cbY; // cbX <= cbY

    // copy bb to cb. bb may be larger than room in cb. return bytes copied.
    int bb2cb(ByteBuffer bb)
    {
        int lim0 = bb.limit(); // to be restored
        int len = Math.min(bb.remaining(), cbM-(cbY-cbX));
        if(cbY+len<=cbM)
            bb2cb(cbY, bb, len);
        else if(cbY>=cbM)
            bb2cb(cbY - cbM, bb, len);
        else
        {
            int len1 = cbM-cbY;
            bb2cb(cbY, bb, len1);
            bb2cb(0,   bb, len - len1);
        }
        cbY += len;
        bb.limit(lim0);
        return len;
    }
    void bb2cb(int cb0, ByteBuffer bb, int len)
    {
        if(copyBuffer==null)
            copyBuffer = writeBufferPool.checkOut();

        copyBuffer.position(cb0); // it has enough room for `len` bytes

        bb.limit(bb.position() + len);
        copyBuffer.put(bb);
        // bb.position += len
    }

    // queue [cbY0, cbY) to dbb
    void cb2dbb(int cbY0)
    {
        if(cbY<=cbM)
            cb2dbb(cbY0, cbY);
        else if(cbY0>=cbM)
            cb2dbb(cbY0 - cbM, cbY - cbM);
        else
        {
            cb2dbb(cbY0, cbM);
            cb2dbb(0, cbY - cbM);
        }
    }
    void cb2dbb(int pos, int lim)
    {
        copyBuffer.position(pos);
        ByteBuffer bb = copyBuffer.slice(); // bb.capacity reflects where it starts in cb.
        bb.limit(lim - pos);

        dbbQueue.addLast(bb);
        dbbIsCopy.addLast(Boolean.TRUE);
        dbbTotal += (lim-pos);
    }

    void cbFree(ByteBuffer bb)
    {
        // bb was created in cb2dbb(). now its [0, position) content is written, we can reuse that space.
        // note cbFree(bb) might have been called before with same bb and a smaller position.
        int pos0 = cbM - bb.capacity();  // where bb starts in cb
        int cbX2 = pos0 + bb.position();
        assert cbX<=cbX2 && cbX2<=cbM && cbX2<=cbY ;
        cbX = cbX2;

        if(cbX==cbY)  // all free. common.
        {
            cbX=cbY=0;  // for max continuous space
        }
        else if(cbX==cbM)
        {
            //cbX -= cbM;
            cbX = 0;
            cbY -= cbM;
        }
    }

    void cbDispose()
    {
        if(trace)trace("dispose copyBuffer");
        writeBufferPool.checkIn(copyBuffer);
        copyBuffer = null;
        cbX = cbY = 0;
    }




}
