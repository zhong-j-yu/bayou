package bayou.tcp;

import bayou.async.Async;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.Executor;

// abstract tcp/ssl socket channel. async interface

// support EOF, drain.
// example: HTTP server should do "lingering close": first half-close; then drain; then close.
// translated into our API: queue(EOF), write() till wr=0, close( drainTimeout>0 )

// thread safe; all methods can be called at any time on any thread.
// however no concurrent pending read/write

// unread/getWriteRemaining/queueWrite/queue(EOF) after close() will throw IllegalStateException
// not necessarily a programming error, the channel may be closed async-ly, so it's not caller's fault.
// we could just ignore the call. but we throw instead so caller can know what's going on.

interface AsyncConnection
{
    InetAddress getRemoteIp();

    // ----

    // if successful, something available to read. (successful read).
    //     guarantee: bb.length()>0 (unless an empty bb was given in unread())
    // if fail with End, EOF
    // if fail with other error, usually unrecoverable, corrupt. source should then be closed.
    Async<ByteBuffer> read(boolean accepting) throws IllegalStateException;

    // next read() will return `bb` as is (unless close() called before read())
    // bb can be empty, we don't check
    // only 1 unread() is supported.
    // cannot unread() while a read is pending.
    void unread(ByteBuffer bb) throws IllegalStateException;
    // this method is very useful to apps; often read() may return more data than currently needed.

    // ----

    // the async channel maintains a queue of ByteBuffer to be written. write() will attempt to write them.
    // (this mechanism is useful/helpful to user. and we need it for ssl multi-stage buffering.)
    // write queue size (wqs) is the number of bytes not written yet.

    long getWriteQueueSize() throws IllegalStateException;

    // returns write queue size.
    // bytes may be very small, e.g. "\r\n", or very large, e.g. some giant cached data in one piece.
    // CAUTION: conn now owns bb, and may update it's position etc. use duplicate if necessary
    // can be invoked during write pending
    long queueWrite(ByteBuffer bb) throws IllegalStateException;
    // can queue FIN/CLOSE_NOTIFY. probably should have new methods for that.

    // try to write n bytes or more
    // write is aggressive - after n bytes, we'll write as much as possible as long as it's not blocking
    // 0<n<=wr
    //   don't handle n==0 case; not sure what's the caller intend to do
    //   don't handle n>wr case; caller probably miscalculated
    // return: actual bytes written
    // remember EOF counts as 1 byte.
    // if error, likely unrecoverable, some bytes may have been written. state may be corrupt. caller should close()
    Async<Long> write(long n) throws IllegalStateException;

    // ----

    Executor getExecutor();


    // can be called multiple times. can be called anytime on any thread.
    // (only 1st call is effective, particularly, only the drainTimeout arg of the 1st call is effective)
    // pending read/write will complete with exception
    // abandon any buffered writes.
    //
    // if drainTimeout is null or <=0, no drain
    // if drainTimeout>0, will attempt to drain inbound data (raw tcp) before closing the TCP connection.
    // this is to prevent the RST problem - if inbound data comes after close.
    // a properly designed app protocol may not need to drain, because it knows there's no more inbound data.
    // though draining can also be done by read(), it might be a little expensive (e.g. decoding SSL records)
    // also no need to drain if EOF was read; an error occurred; app wants to kill conn imm.
    // this method does not send EOF to peer before draining; use queue(EOF)+write() instead.
    void close(Duration drainTimeout);
}
