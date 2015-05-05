package _bayou._tmp;

import bayou.async.Async;
import bayou.tcp.TcpChannel;
import bayou.tcp.TcpConnection;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

// implemented by a TcpConnection to convert itself to TcpChannel
// this is used internally for SSL over SSL.
// since SslConnectionImpl is done over TcpChannel, and we don't want to touch that,
// we need to wrap TcpConnection/SslConnectionImpl as a TcpChannel.
// SSL over SSL is rare, so don't worry too much about perfection.
public interface _TcpConn2Chann
{
    // called on a TcpConnection only when there's no unread or queued writes.
    public TcpChannel toChann(String peerHost, int peerPort);

}
