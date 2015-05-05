package bayou.websocket;

import _bayou._tmp._TrafficDumpWrapper;
import _bayou._tmp._ByteBufferUtil;
import bayou.async.Async;
import bayou.async.Fiber;
import bayou.async.Promise;
import bayou.ssl.SslConnection;
import bayou.tcp.TcpConnection;
import bayou.util.function.FunctionX;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.Executor;

class WebSocketChannelImpl implements WebSocketChannel
{
    final Object lock(){ return this; }
    // we don't really worry about lock contention. usually all actions are done on the selector flow.

    final WebSocketServerConf conf;
    final TcpConnection tcpConn;

    final _TrafficDumpWrapper dump;

    final WebSocketOutbound outbound;
    final WebSocketInbound inbound;

    final Promise<Void> closePromise;

    Executor pumpExec;
    // we must run pumps in a fiber, instead of simply in tcpConn executor,
    // because otherwise some API (e.g. Async.onCompletion) would dispatch to the default executor.

    WebSocketChannelImpl(WebSocketServer server, TcpConnection tcpConn, ByteBuffer handshakeResponse)
    {
        this.conf = server.conf;
        this.dump = conf.trafficDumpWrapper;
        this.tcpConn = tcpConn;

        if(dump!=null)
            dump.print(
                "== connection #", ""+ tcpConn.getId(), " upgraded to websocket ==\r\n",
                _ByteBufferUtil.toLatin1String(handshakeResponse),
                connId(), " open [", tcpConn.getPeerIp().getHostAddress(), "] ==\r\n"
            );

        outbound = new WebSocketOutbound(this, server, tcpConn, handshakeResponse);
        inbound = new WebSocketInbound(this, server, tcpConn, outbound);

        closePromise = new Promise<>();
        closePromise.fiberTracePop();
    }

    void start(FunctionX<WebSocketChannel, Async<?>> channHandler)
    {
        if(Fiber.enableTrace)
        {
            // don't run pumps in chann handler fiber; they'll mess up the stack trace
            new Fiber<>( tcpConn.getExecutor(), fiberName()+" - background", () ->
            {
                pumpExec = Fiber.current().getExecutor();
                outbound.pump();
                inbound.pump();
                return closePromise; // fiber ends after both pumps retire
                // or, we could return Async.VOID here, so that this fiber "completes" immediately
                // and it doesn't show up in Fiber.getAll(). (a fiber still functions after "completion")
                // well, let's rather show this fiber, it might be interesting to users.
            });

            new Fiber<>( tcpConn.getExecutor(), fiberName(), ()->
                channHandler.apply(this)
            );
        }
        else // no fiber trace, so it's ok to run pump flows in chann handler fiber
        {
            new Fiber<>( tcpConn.getExecutor(), fiberName(), ()->
            {
                pumpExec = Fiber.current().getExecutor();
                outbound.pump();
                inbound.pump();
                return channHandler.apply(this);
            });
        }
        // do not auto close chann after channHandler completes.
        // app may continue to use chann without fiber
    }

    @Override
    public Async<WebSocketMessage> readMessage()
    {
        return inbound.readMessage();
    }

    @Override
    public Async<Long> writeMessage(WebSocketMessage message)
    {
        return outbound.stageMessage(message);
    }

    @Override
    public Async<Void> writeClose()
    {
        return outbound.stageCloseFrame();
    }


    // close
    //
    // inbound/outbound may retire due to
    //     error
    //     close-frame received/sent
    //     close() (by chann.close())
    // after each retires, it calls chann.tcpConn_close() once.
    // after both calls chann.tcpConn_close(), tcpConn is really closed.

    @Override
    public Async<Void> close()
    {
        // request inbound&outbound to close. they'll handle duplicate close() calls.
        inbound.close();
        outbound.close();

        if(!Fiber.enableTrace || closePromise.isCompleted())
            return closePromise;

        // fiber trace enabled, and close is pending. do not return `closePromise`
        // create a new Promise here, so that caller fiber trace appear to be stuck at close()
        Promise<Void> promise = new Promise<>();
        closePromise.onCompletion(promise::complete);
        // no cancel propagation?
        return promise;
    }

    byte tcpConnCloseCount;
    void tcpConn_close(boolean graceful)
    {
        // this method is called once by inbound and once by outbound
        // we'll close for real on the 2nd call. `graceful` is AND-ed.
        final int T=3, F=4;
        byte _tcpConnCloseCount;
        synchronized (lock())
        {
            tcpConnCloseCount += ( graceful ? T : F );
            _tcpConnCloseCount = tcpConnCloseCount;
        }
        boolean grace;
        switch (_tcpConnCloseCount)
        {
            case T:
            case F:
                return; // do not close yet

            case T+T:
                grace = true;
                break;

            case T+F:
            case F+F:
                grace = false; // not graceful close; no draining. FIN is sent.
                break;

            default:
                throw new AssertionError();
        }

        Async<Void> tcpConnClosing = tcpConn.close( grace ? conf.closeTimeout : null );

        if(tcpConnClosing.isCompleted()) // not rare
            closePromise.complete(tcpConnClosing.pollResult());
        else
            tcpConnClosing.onCompletion(closePromise::complete); // propagate cancel? promise->tcpConn

        if(dump!=null)
            dump.print(connId(), " closed", " == \r\n");

        if(false) // for testing
            closePromise.onCompletion( r->System.out.println("close done: "+r) );
    }



    String connId()
    {
        return "== " + ((tcpConn instanceof SslConnection)?"wss":"ws") + " connection #" + tcpConn.getId();
    }
    String fiberName()
    {
        StringBuilder sb = new StringBuilder();
        sb.append((tcpConn instanceof SslConnection)?"wss":"ws")
            .append(" connection #").append(tcpConn.getId())
            .append(" [").append(tcpConn.getPeerIp().getHostAddress()).append("]");
        return sb.toString();
    }


    void dumpFrameHead(boolean incoming, ByteBuffer bb)
    {
        int p0 = bb.position();
        byte byte0 = bb.get(p0);

        boolean fin  = (byte0 & 0b1000_0000)!=0;
        boolean rsv1 = (byte0 & 0b0100_0000)!=0;
        boolean rsv2 = (byte0 & 0b0010_0000)!=0;
        boolean rsv3 = (byte0 & 0b0001_0000)!=0;
        int opCode   = (byte0 & 0b0000_1111);

        byte byte1 = bb.get(p0+1);
        boolean mask = (byte1 & 0b1000_0000)!=0;
        long payloadLength = 0x7F & byte1;
        int lenEx = payloadLength==127? 8 : payloadLength==126? 2 : 0;
        if(lenEx!=0)
        {
            payloadLength=0;
            for(int i=0; i<lenEx; i++)
            {
                payloadLength <<= 8;
                payloadLength += 0xFF & bb.get(p0+2+i);
            }
            // may become negative if 64 bit
        }

        ArrayList<CharSequence> toDump = new ArrayList<>();

        toDump.add(connId());
        toDump.add(incoming? " frame in <= " : " frame out =>");
        toDump.add(" opcode=");
        toDump.add(WsOp.strings[opCode]);

        toDump.add(", payload=");
        toDump.add(""+payloadLength);

        if(fin)  toDump.add(", FIN");
        if(rsv1) toDump.add(", RSV1");
        if(rsv2) toDump.add(", RSV2");
        if(rsv3) toDump.add(", RSV3");

        if(incoming&&!mask) // show MASK bit only if client forgot to set it
            toDump.add(", MASK=0");
        if(!incoming&&mask)
            throw new AssertionError();

        toDump.add(" ==\r\n");

        dump.print(toDump);
    }

}
