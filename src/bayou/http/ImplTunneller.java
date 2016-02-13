package bayou.http;

import _bayou._http._HttpDate;
import _bayou._http._HttpHostPort;
import _bayou._http._HttpUtil;
import _bayou._str._CharSeqSaver;
import _bayou._tmp.*;
import bayou.async.Async;
import bayou.mime.HeaderMap;
import bayou.mime.Headers;
import bayou.ssl.SslConnection;
import bayou.tcp.TcpChannel;
import bayou.tcp.TcpChannel2Connection;
import bayou.tcp.TcpClient;
import bayou.tcp.TcpConnection;
import bayou.util.Result;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

// tunnel:  http://tools.ietf.org/html/rfc7230#section-2.3
// CONNECT: http://tools.ietf.org/html/rfc7231#section-4.3.6

class ImplTunneller
{
    long highMark;
    TcpChannel2Connection chann2conn;
    Duration connectTimeout = Duration.ofSeconds(10); // todo conf

    TcpClient[] clients;

    ImplTunneller(HttpServer server) throws Exception
    {
        HttpServerConf conf = server.conf;

        this.highMark = conf.outboundBufferSize;

        chann2conn = new TcpChannel2Connection(conf.readBufferSize, conf.writeBufferSize);

        int[] selectorIds = conf.get_selectorIds();
        clients = new TcpClient[selectorIds.length];
        for(int i=0; i<selectorIds.length; i++)
        {
            TcpClient.Conf c = new TcpClient.Conf();
            c.selectorId = selectorIds[i];
            // todo: other TcpClient.Conf vars
            clients[i] = new TcpClient(c); // throws, unlikely
        }
    }


    void close()
    {
        for(TcpClient client : clients)
            client.close();
    }


    Async<HttpResponse> tryConnect(ImplHttpRequest request, HttpResponse response, ImplConn ic)
    {
        assert request.method.equals("CONNECT");
        if(response.statusCode()/100!=2) // not 2xx
            return _HttpUtil.toAsync(response);

        // if the CONNECT request has a body, and it's not read by app, it becomes tunnel payload.

        // it's ok to CONNECT to self, i.e. target ip:port = self ip:port. probably not happening in practice.
        // in that case, we don't need to spawn a new connect to self; just reuse this connection.
        // but it's no big deal either to tunnel to self with a new connection. later.

        _HttpHostPort hp = _HttpHostPort.parse(request.uri);
        assert hp!=null && hp.port!=-1; // request-target was validated as host:port
        Async<InetAddress> asyncIp;
        if(hp.ip!=null)
            asyncIp = Result.call( ()->InetAddress.getByAddress(hp.ip) ); // won't fail
        else
            asyncIp = _Dns.resolve(hp.domain); // may fail

        return asyncIp
            .then(ip -> tryConnect2(ip, hp.port, response, ic))  // may fail
            .timeout(connectTimeout) // combined timeout for dns.resolve() and client.connect()
            .catch_(Exception.class, ex->
                {
                    HttpServer.logErrorOrDebug(ex);
                    return HttpResponse.text(502, "Unable to connect to target: "+request.uri);
                } );
    }
    Async<HttpResponse> tryConnect2(InetAddress ip, int port, HttpResponse response, ImplConn ic)
    {
        TcpClient client = clients[0];
        // prefer client associated with the current selector thread
        Executor exec = ic.tcpConn.getExecutor();
        for(TcpClient c : clients)
        {
            if(c.getExecutor()==exec)
            {
                client = c; // this should be reached; caller is in one of the selector threads
                break;
            }
        }

        Async<TcpChannel> asyncChann = client.connect(null, ip, port);
        return asyncChann.then(chann ->
        {
            ic.tunnelConn = chann2conn.convert(chann);
            return _HttpUtil.toAsync(response);
            // later, ImplConn invokes doTunnel()
        });
    }

    void doTunnel(ImplConn hConn, TcpConnection sourceConn, TcpConnection targetConn)
    {
        ArrayList<Cookie> jarCookies = (ArrayList<Cookie>)CookieJar.getAllChanges();
        _CharSeqSaver responseChars = responseChars(hConn.response, jarCookies);
        ByteBuffer responseBytes = ByteBuffer.wrap( responseChars.toLatin1Bytes() );
        sourceConn.queueWrite(responseBytes);

        _TrafficDumpWrapper dump = hConn.dump;
        if(dump!=null)
            dump.print(
                "== connection #", ""+sourceConn.getId(),
                " tunnels to ",
                hConn.request.uri,
                " ==\r\n",
                hConn.respId(),
                responseChars.toCharSequence()
            );
        String connDesc = dump==null ? null : "#"+sourceConn.getId()+" (tunnel to "+hConn.request.uri+")";

        Pump p1 = new Pump(true , sourceConn, targetConn, highMark, dump, connDesc);
        Pump p2 = new Pump(false, targetConn, sourceConn, highMark, dump, connDesc);
        p1.that = p2;
        p2.that = p1;

        p2.jump(Pump.Goto.drainMark); // first, write 2xx response to client
        p1.jump(Pump.Goto.readWrite);

    }


    // may throw from user code from user object `appResponse`. treat it as unexpected.
    static _CharSeqSaver responseChars(HttpResponse appResponse, ArrayList<Cookie> jarCookies)
    {
        // don't touch the original response (e.g. it may be a shared stock response)

        ArrayList<Cookie> cookies = jarCookies;
        cookies.addAll(appResponse.cookies());

        // copy headers.
        HeaderMap headers = new HeaderMap();
        for(Map.Entry<String,String> entry : appResponse.headers().entrySet())
        {
            String name = entry.getKey();
            String value = entry.getValue();
            // we don't trust name/value. check them.
            _HttpUtil.checkHeader(name, value);
            headers.put(name, value);
        }

        // no response body for CONNECT. ignore appResponse.entity().
        headers.xRemove(Headers.Content_Length);  // in case app set it
        headers.xRemove(Headers.Transfer_Encoding);   // in case app set it

        // Connection header?

        // other headers
        if(!headers.xContainsKey(Headers.Date))
            headers.xPut(Headers.Date, _HttpDate.getCurrStr());

        String hServer = headers.xGet(Headers.Server);
        if(hServer==null || hServer.isEmpty())
            hServer = "Bayou";
        else
            hServer = "Bayou " + hServer;  // hServer was checked, is still valid
        headers.xPut(Headers.Server, hServer);

        _CharSeqSaver out = new _CharSeqSaver( 4 + 4*headers.size() + 4*cookies.size() );
        {
            out.append("HTTP/1.1 ").append(appResponse.status().toString()).append("\r\n");

            for(Map.Entry<String,String> nv : headers.entrySet())
            {
                String name = nv.getKey();
                String value = nv.getValue();
                // name value have been sanity checked. we'll not generate syntactically incorrect header.
                out.append(name).append(": ").append(value).append("\r\n");
            }
            for(Cookie cookie : cookies)   // setCookie guaranteed to be valid
                out.append(Headers.Set_Cookie).append(": ").append(cookie.toSetCookieString()).append("\r\n");

            out.append("\r\n");
        }
        return out;
    }


    static class Pump
    {
        boolean c2s; // which direction
        TcpConnection input;
        TcpConnection output;
        long highMark;
        _TrafficDumpWrapper dump;
        String connDesc;
        Pump that;

        int state; // [0] running [1] error [2] FIN

        Pump(boolean c2s, TcpConnection input, TcpConnection output, long highMark, _TrafficDumpWrapper dump, String connDesc)
        {
            this.c2s = c2s;
            this.input = input;
            this.output = output;
            this.highMark = highMark;
            this.dump = dump;
            this.connDesc = connDesc;
        }

        enum Goto { NA, readWrite, drainMark, flushAll }

        void jump(Goto g)
        {
            try
            {
                while(g!=Goto.NA)
                {
                    switch(g)
                    {
                        case readWrite: g=readWrite(); break;
                        case drainMark: g=drainMark(); break;
                        case flushAll : g=flushAll() ; break;
                        default: throw new AssertionError();
                    }
                }
            }
            catch (Exception e)
            {
                err(e);
            }
        }

        Goto gNext;
        Consumer<Result<Void>> onWakeup = result ->
        {
            if(result.isFailure())
                err(result.getException());
            else
                jump(gNext);
        };
        Goto awaitWritable(Goto g)
        {
            Async<Void> awaitW = output.awaitWritable(); // todo timeout?
            gNext = g;
            awaitW.onCompletion(onWakeup);
            return Goto.NA;
        }
        Goto awaitReadable(Goto g)
        {
            Async<Void> awaitR = input.awaitReadable(false); // todo timeout?
            gNext = g;
            awaitR.onCompletion(onWakeup);
            return Goto.NA;
        }


        Goto readWrite() throws Exception
        {
            ByteBuffer bb = input.read(); // throws

            if(bb==TcpConnection.STALL)
            {
                output.write(); // throws
                if(output.getWriteQueueSize()>0) // read and write both stall.
                {
                    // await output writable only. doesn't matter if input becomes readable first.
                    // upon writable, we'll try read() again.
                    return awaitWritable(Goto.readWrite);
                }
                else // read stall, write queue empty. await readable.
                {
                    return awaitReadable(Goto.readWrite);
                }
            }

            // sourceConn could be SSL. todo: analyze close-notify from and to sourceConn
            if(bb==TcpConnection.TCP_FIN || bb== SslConnection.SSL_CLOSE_NOTIFY)
            {
                output.queueWrite(TcpConnection.TCP_FIN);
                return Goto.flushAll;
            }

            // bb is data
            long qs = output.queueWrite(bb);
            if(qs>highMark)
                return Goto.drainMark;

            return Goto.readWrite;
        }

        Goto drainMark() throws Exception
        {
            output.write(); // throws
            if(output.getWriteQueueSize()>highMark)
                return awaitWritable(Goto.drainMark);
            else
                return Goto.readWrite;
        }

        Goto flushAll() throws Exception
        {
            output.write(); // throws
            if(output.getWriteQueueSize()>0)
                return awaitWritable(Goto.flushAll);
            else
                return fin();
        }

        Goto fin()
        {
            this.state = 2;
            if(that.state==2) // FIN exchanged on both directions
                return close(c2s?"closed by server":"closed by client", null);
            else
                return Goto.NA;
        }

        Goto err(Exception e)
        {
            HttpServer.logErrorOrDebug(e);
            this.state=1;
            if(that.state==1)
                return Goto.NA;
            else
                return close(null, e); // if that.state==0, close() will trigger error on that pump.
        }

        Goto close(String reason, Exception ex)
        {
            if(dump!=null)
                dump.print(
                    "== connection ",
                    connDesc,
                    " closed == ",
                    reason==null? "":reason,
                    ex==null?"":ex.toString(),
                    "\r\n"
                );

            input.close(null);
            output.close(null);
            return Goto.NA;
        }
    }

}
