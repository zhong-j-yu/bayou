package bayou.http;

import _bayou._async._Asyncs;
import _bayou._tmp.*;
import bayou.async.Async;
import bayou.async.Promise;
import bayou.ssl.SslChannel2Connection;
import bayou.tcp.*;
import bayou.util.Result;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

class HttpClientConnMan
{
    HttpClient httpClient;
    HttpClientConf conf;

    _Array2ReadOnlyList<TcpTunnel> tunnels; // null if none (most likely)


    TcpChannel2Connection tcpChannel2Connection;

    SslChannel2Connection sslChannel2Connection;


    HttpClientConnMan(HttpClient httpClient) throws Exception
    {
        this.httpClient = httpClient;
        this.conf = httpClient.conf;

        this.tcpChannel2Connection = new TcpChannel2Connection(conf.readBufferSize, conf.writeBufferSize);
        this.sslChannel2Connection = new SslChannel2Connection(true, conf.sslContext, conf.x_sslEngineConf());

        if(conf.tunnels.length>0)
            this.tunnels = new _Array2ReadOnlyList<TcpTunnel>(conf.tunnels);

    }





    // each TcpAddress is mapped to several cached connections
    final HashMap<TcpAddress, ConnQueue> cache = new HashMap<>();  // synchronized(cache)

    static class ConnHolder
    {
        HttpClientConnection conn;

        Executor executor(){ return conn.tcpConn.getExecutor(); }

        Async<Void> awaitReadable;

        Promise<HttpClientConnection> checkOutPromise;
    }

    static class ConnQueue extends ArrayList<ConnHolder>
    {
        // checking in/out happens on the same end (the tail)

        void checkIn(ConnHolder holder)
        {
            super.add(holder);
        }
        ConnHolder checkOut()
        {
            int iLast = super.size()-1;
            assert iLast>=0;
            for(int i=iLast; i>=0; i--)
            {
                if(super.get(i).executor()==Thread.currentThread()) // preferred
                    return super.remove(i);
            }
            return super.remove(iLast);
            // return a conn even if it's not on the proper thread.
            // it's better to reuse a connection than to establish a new one on the proper thread
        }
        void evict(ConnHolder holder)
        {
            // most likely this is an older conn, which is near the head of the queue
            // not very efficient for an ArrayList. to be optimized
            super.remove(holder);
        }
    }

    /*
        when a conn is put in cache, we want to evict it if:
            1. keep-alive timeout is reached
            2. some error occurs on the connection
            3. server closes the connection (we receive server FIN)
            4. server writes some unsolicited bytes to us!
               see http://lists.w3.org/Archives/Public/ietf-http-wg/2014AprJun/1050.html
        we use awaitReadable().timeout() to cover all these cases.

        when checking out a conn from cache, it is still pending on awaitReadable,
        we cannot immediately use it yet (it fails if caller does another awaitReadable)
        we send control exception `checkingOut` to cancel awaitReadable; checkOut() waits for its wakeup.

        on wakeup of awaitReadable, if it's due to control exception `checkingOut`,
            that's good, the previous checkOut() is now complete with the conn.
        otherwise, conn is evicted - though, it's possible that conn was picked in a previous checkOut(),
            in which case we create a new conn to satisfy the checkOut promise.

    */

    private static final _ControlException checkingOut = new _ControlException("");

    ConnHolder checkOut(TcpAddress dest)
    {
        ConnHolder holder = null;
        synchronized (cache)
        {
            ConnQueue queue = cache.get(dest);
            if(queue!=null)
            {
                holder = queue.checkOut(); // non-null
                holder.checkOutPromise = new Promise<>();
                if(queue.size()==0)
                    cache.remove(dest);
            }
        }

        if(holder!=null)
        {
            holder.awaitReadable.cancel(checkingOut);
            return holder;
        }

        return null;
    }

    boolean canReuse(HttpClientConnection conn)
    {
        if(conf.keepAliveTimeout==null)
            return false;

        if(conn.isLastResponse())
            return false;

        return true;
    }

    // maybe called on any thread
    void checkIn(HttpClientConnection conn)
    {
        assert canReuse(conn); // caller makes sure of that

        ConnHolder holder = new ConnHolder();
        holder.conn = conn;
        holder.awaitReadable = conn.tcpConn.awaitReadable(false);
        holder.awaitReadable.timeout(conf.keepAliveTimeout);

        synchronized (cache)
        {
            ConnQueue queue = cache.get(conn.dest);
            if(queue==null)
                cache.put(conn.dest, queue=new ConnQueue());

            queue.checkIn(holder);
        }

        // register readable callback *after* putting it in cache
        _Asyncs.onCompletion(holder.awaitReadable, holder.executor(), r -> onReadable(r, holder));
    }

    void onReadable(Result<Void> result, ConnHolder holder)
    {
        if(checkingOut==result.getException())
        {
            // cancel(checkingOut) happens-before. no lock required.
            assert holder.checkOutPromise !=null;
            holder.checkOutPromise.succeed(holder.conn);
            return;
        }

        // evict conn
        holder.conn.close();
        TcpAddress dest = holder.conn.dest;
        Promise<HttpClientConnection> checkOutPromise;
        synchronized (cache)
        {
            checkOutPromise = holder.checkOutPromise;
            if(checkOutPromise==null) // common. conn is evicted before checkOut()
            {
                ConnQueue queue = cache.get(dest);
                assert queue!=null;
                queue.evict(holder);
                if(queue.size()==0)
                    cache.remove(dest);
                return;
            }

            // checkOutPromise!=null. checkOut() happened, but then conn is evicted. rare case.
            // we create a new connection to satisfy the checkOut promise.
        }

        // we are on the selector thread
        TcpClient tcpClient = httpClient.getCurrTcpClient();
        boolean sendAbsUri = holder.conn.outbound.sendAbsoluteUri;

        Async<HttpClientConnection> newConn = newConn(dest, tcpClient, sendAbsUri);
        newConn.onCompletion(checkOutPromise::complete);
        checkOutPromise.onCancel(newConn::cancel);

    }




    // create new conn
    //##############################################################################################################

    // exception analysis:
    // we cannot just catch the exception at the highest level and close the channel
    // multiple wrapper of connections may be created that need to be closed top-down.
    // catch exception at expected places and close the outermost wrapper at that point.
    // these places are marked as [close on exception]



    Async<HttpClientConnection> newConn(TcpAddress dest, TcpClient tcpClient, boolean sendAbsoluteUri)
    {
        // inside tcpClient.executor
        return createConnectionChain(dest, tcpClient)
            .map( tcpConn->new HttpClientConnection(dest, tcpConn, sendAbsoluteUri, conf, false) );
    }
    Async<TcpConnection> createConnectionChain(TcpAddress finalHop, TcpClient tcpClient)
    {
        // create an tcp connection, possibly through tunnels, to finalHop
        if(tunnels==null) // most likely.
            return tcpConnect(finalHop, tcpClient);

        return tcpConnect(tunnels.get(0).address(), tcpClient)
            .then(conn -> tunnelTo(conn, 0, finalHop));
    }
    Async<TcpConnection> tcpConnect(TcpAddress hop, TcpClient tcpClient)
    {
        return _Dns
            .resolve(hop.host())
            .then( ip->tcpClient.connect(hop.host(), ip, hop.port()) )
            .then( tcpChann -> tcpChann2Conn(tcpChann, hop.ssl()));
    }
    Async<TcpConnection> tunnelTo(TcpConnection tcpConn, int iTunnel, TcpAddress finalHop)
    {
        // we have a tcp conn to the tunnel
        // now tunnel to the next hop (which could be another tunnel)
        int iNextTunnel = iTunnel+1;
        boolean lastTunnel = iNextTunnel==tunnels.size(); // usually true, where there's only one tunnel
        TcpAddress nextHop = lastTunnel ? finalHop : tunnels.get(iNextTunnel).address();
        Async<TcpConnection> result = tunnels.get(iTunnel)
            .tunnelTo(tcpConn, nextHop.host(), nextHop.port());
        if(nextHop.ssl()) // perform SSL handshake on tcpConn (which could be ssl itself)
            result = result.then(_tcpConn->tcpConn2ssl(_tcpConn, nextHop.host(), nextHop.port()));
        if(!lastTunnel)
            result = result.then(_tcpConn->tunnelTo(_tcpConn, iNextTunnel, finalHop));
        return result;
    }


    Async<TcpConnection> tcpChann2Conn(TcpChannel tcpChann, boolean ssl)
    {
        if(!ssl)
            return Async.success(tcpChannel2Connection.convert(tcpChann)); // won't fail
        else
            return sslChannel2Connection.convert(tcpChann).covary();
        // [close on exception] - if ssl fails, tcpChann is automatically closed
    }

    Async<TcpConnection> tcpConn2ssl(TcpConnection tcpConn, String peerHost, int peerPort)
    {
        assert tcpConn instanceof _TcpConn2Chann;
        TcpChannel chann = ((_TcpConn2Chann)tcpConn).toChann(peerHost, peerPort);
        // it's important to set peerHost here for SSL host name verification.

        return sslChannel2Connection.convert(chann).covary();
        // [close on exception] - if ssl fails, chann is automatically closed
    }

}
