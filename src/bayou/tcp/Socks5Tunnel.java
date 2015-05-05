package bayou.tcp;

import _bayou._tmp._Dns;
import _bayou._tmp._Ip;
import _bayou._tmp._Tcp;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.util.UserPass;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

/**
 * A TcpTunnel using SOCKS5 protocol.
 * See <a href="http://tools.ietf.org/html/rfc1928">RFC1928</a>
 * <p>
 *     Username/password authentication is supported,
 *     see <a href="http://tools.ietf.org/html/rfc1929">RFC1929</a>.
 * </p>
 */
public class Socks5Tunnel implements TcpTunnel
{

    TcpAddress address; // can be ssl

    boolean remoteDns;

    Supplier<Async<UserPass>> userPassSupplier;

    /**
     * Create a SOCKS5 tunnel.
     * <p>
     *     If remoteDns==true, `host` in {@link #tunnelTo(TcpConnection, String, int) tunnelTo()}
     *     is sent to the tunnel service to be resolved there;
     *     otherwise, `host` is resolved by local DNS, and the IP address is sent to the tunnel.
     *     See "ATYP" in <a href="http://tools.ietf.org/html/rfc1928#section-4">RFC1928 &sect;4</a>.
     * </p>
     * <p>
     *     If userPassSupplier!=null, username/password authentication is supported.
     * </p>
     */
    public Socks5Tunnel(TcpAddress address, boolean remoteDns, Supplier<Async<UserPass>> userPassSupplier)
    {
        this.address = address;
        this.remoteDns = remoteDns;
        this.userPassSupplier = userPassSupplier;
    }

    /**
     * Create a SOCK5 tunnel.
     * <p>
     *     This is convenience method for
     *     {@link #Socks5Tunnel(TcpAddress, boolean, java.util.function.Supplier)
     *     Socks5Tunnel(address, remoteDns, userPassSupplier)}
     *     with address.ssl=false, remoteDns=false.
     * </p>
     */
    public Socks5Tunnel(String host, int port, String username, String password)
    {
        this(
            new TcpAddress(false, host, port), false,
            ()->Async.success(new UserPass(username, password))
        );
    }

    /**
     * Create a SOCK5 tunnel.
     * <p>
     *     This is convenience method for
     *     {@link #Socks5Tunnel(TcpAddress, boolean, java.util.function.Supplier)
     *     Socks5Tunnel(address, remoteDns, userPassSupplier)}
     *     with address.ssl=false, remoteDns=false, userPassSupplier=null.
     * </p>
     */
    public Socks5Tunnel(String host, int port)
    {
        this( new TcpAddress(false, host, port), false, null);
    }



    @Override
    public TcpAddress address()
    {
        return address;
    }

    @Override
    public Async<TcpConnection> tunnelTo(TcpConnection tcpConn, String host, int port)
    {
        // send first request
        ByteBuffer bb = ByteBuffer.allocate(4); // http://tools.ietf.org/html/rfc1928#section-3
        bb.put((byte)0x05); // VER
        if(userPassSupplier==null)
        {
            bb.put((byte)1);    // NMETHODS
            bb.put((byte)0x00);  // METHODS
        }
        else
        {
            bb.put((byte)2);
            bb.put((byte)0x02);  // USERNAME/PASSWORD
            bb.put((byte)0x00);  // NO AUTHENTICATION REQUIRED. not sure if we need to include it.
        }
        bb.flip();
        tcpConn.queueWrite(bb);
        return _Tcp
            .writeFlush(tcpConn)
            .then(v->_Tcp.read(tcpConn, 2))
            .then(b2 -> onFirstResponse(b2, tcpConn, host, port));
    }
    Async<TcpConnection> onFirstResponse(byte[] b2, TcpConnection tcpConn, String nextHost, int nextPort) throws Exception
    {
        if(b2[0]!=0x05) // VER
            throw new Exception("unknown server VER: "+b2[0]);

        int method = b2[1];
        if(method==0x00) // we may have offered for user/pass, but server doesn't require it
            return sendConnectReq(tcpConn, nextHost, nextPort);
        else if(method==0x02)
            return userPassSupplier.get()
                .then(userPass-> sendUserPass(userPass, tcpConn, nextHost, nextPort));
        else
            throw new Exception("unsupported server method: "+method);
    }

    Async<TcpConnection> sendConnectReq(TcpConnection tcpConn, String nextHost, int nextPort) throws Exception
    {
        if(remoteDns)
        {
            byte[] nextIp = _Ip.parseIp(nextHost, 0, nextHost.length()); // non-null if nextHost is IP literal
            return sendConnectReq(tcpConn, nextIp, nextHost, nextPort);
        }

        return _Dns.resolve(nextHost)
            .then(address->sendConnectReq(tcpConn, address.getAddress(), nextHost, nextPort));
    }
    Async<TcpConnection> sendConnectReq(TcpConnection tcpConn, byte[] nextIp, String nextHost, int nextPort) throws Exception
    {
        ByteBuffer bb = ByteBuffer.allocate(4+256+2); // http://tools.ietf.org/html/rfc1928#section-4

        bb.put((byte)0x05); // VER
        bb.put((byte)0x01); // CMD
        bb.put((byte)0x00); // RSV

        if(nextIp!=null)
        {
            bb.put((byte)(nextIp.length==4? 0x01 : 0x04)); // ATYP
            bb.put(nextIp); // DST.ADDR
        }
        else
        {
            bb.put((byte)0x03); // ATYP
            put(bb, nextHost, "domain name");   // DST.ADDR
        }

        // DST.PORT
        bb.put((byte)(nextPort>>8));
        bb.put((byte)(nextPort));

        bb.flip();
        tcpConn.queueWrite(bb);
        return _Tcp
            .writeFlush(tcpConn)
            .then(v->_Tcp.read(tcpConn, 5))
            .then(b5 -> onConnectResponse(b5, tcpConn));
    }
    Async<TcpConnection> onConnectResponse(byte[] b5, TcpConnection tcpConn) throws Exception
    {
        if(b5[0]!=0x05) // VER
            throw new Exception("unknown server VER: "+b5[0]);
        if(b5[1]!=0x00) // Reply field
            throw new Exception("connect failed, REP="+b5[1]);
        if(b5[2]!=0x00) // RSV
            throw new Exception("connect failed, RSV="+b5[2]);

        int remaining; // drain and discard remaining bytes
        if(b5[3]==0x01) // ATYP ipv4
            remaining = -1+4+2;
        else if(b5[3]==0x04) // ipv6
            remaining = -1+16+2;
        else if(b5[3]==0x03) // domain
            remaining = (0xff & b5[4]) + 2;
        else
            throw new Exception("unknown server ATYP: "+b5[3]);
        return _Tcp.read(tcpConn, remaining).map(b->tcpConn);
    }


    Async<TcpConnection> sendUserPass(UserPass userPass, TcpConnection tcpConn, String nextHost, int nextPort) throws Exception
    {
        if(userPass==null)
            throw new Exception("server requires username/password");

        ByteBuffer bb = ByteBuffer.allocate(1+256+256); // http://tools.ietf.org/html/rfc1929#section-2
        bb.put((byte)0x01);
        put(bb, userPass.username(), "username");
        put(bb, userPass.password(), "password");
        bb.flip();
        tcpConn.queueWrite(bb);
        return _Tcp
            .writeFlush(tcpConn)
            .then(v->_Tcp.read(tcpConn, 2))
            .then(b2 -> onUserPassResponse(b2, tcpConn, nextHost, nextPort));
    }
    Async<TcpConnection> onUserPassResponse(byte[] b2, TcpConnection tcpConn, String nextHost, int nextPort) throws Exception
    {
        if(b2[0]!=0x01) // VER
            throw new Exception("unknown server VER: "+b2[0]);
        if(b2[1]!=0x00)// STATUS
            throw new Exception("unknown server STATUS: "+b2[1]);
        // username/password authentication successful
        return sendConnectReq(tcpConn, nextHost, nextPort);
    }



    static void put(ByteBuffer bb, String string, String desc) throws Exception
    {
        // put the length as 1 byte, then the chars. return false if length>255
        int len = string.length();
        if(len>255)
            throw new Exception(desc+" is too long");
        bb.put((byte)len);
        for(int i=0; i<len; i++)
            bb.put((byte)string.charAt(i));
    }

}
