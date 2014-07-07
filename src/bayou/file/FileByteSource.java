package bayou.file;

import _bayou._log._Logger;
import _bayou._tmp._ByteBufferPool;
import _bayou._tmp._ByteBufferUtil;
import _bayou._tmp._Exec;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.async.Promise;
import bayou.bytes.ByteSource;
import bayou.util.End;
import bayou.util.Result;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * A source of bytes from a file.
 * <p>
 *     Example Usage:
 * </p>
 * <pre>
     ByteSource src = new FileByteSource("/tmp/data.bin");

     AsyncIterator.forEach( src::read, System.out::println )
         .finally_( src::close );
 * </pre>
 *
 * Each FileByteSource depends on an {@link AsynchronousFileChannel}, obtained from a {@link ChannelProvider}.
 */
public class FileByteSource implements ByteSource
{
    // experimented on local machine, file->socket, bigger bufferSize shows a lot better performance.
    // the overhead of read->complete is not small (0.1ms? on my PC)
    // for further consideration. heavy load apps need to test with bigger bufferSize.
    // for now: file system is much faster than client network anyway;
    //          common requested files should be cached in memory anyway.
    static final int bufferSize = Integer.getInteger(FileByteSource.class.getName()+
            ".bufferSize", 16 * 1024).intValue();
    static final _ByteBufferPool bbPool = _ByteBufferPool.forCapacity(bufferSize);



    /**
     * Provider of AsynchronousFileChannel for FileByteSource.
     */
    public interface ChannelProvider
    {
        /**
         * Open a file channel.
         * <p>
         *     An implementation may return a shared channel from a pool,
         *     see {@link #pooled(java.nio.file.Path) pooled provider}.
         * </p>
         */
        Async<AsynchronousFileChannel> openChannel();

        /**
         * Close the channel obtained from a previous {@link #openChannel()} call.
         * <p>
         *     An implementation may delay the actual closing of the channel,
         *     see {@link #pooled(java.nio.file.Path) pooled provider}.
         * </p>
         */
        void closeChannel(AsynchronousFileChannel channel);

        /**
         * Create a simple ChannelProvider that opens/closes a file channel straightforwardly.
         */
        public static ChannelProvider simple(Path file)
        {
            return new SimpleChannelProvider(file);
        }

        /**
         * Create a pooled ChannelProvider that may return a shared file channel.
         * <p>
         *     openChannel() may return the same channel to multiple concurrent FileByteSources.
         *     This can be beneficial due to reduced system calls, and less system resources used.
         * </p>
         * <p>
         *     The channel will be actually closed after closeChannel()/openChannel() calls are matched.
         * </p>
         */
        public static ChannelProvider pooled(Path file)
        {
            return new SharedChannelProvider(file);
        }
    }


    // not thread safe

    final ChannelProvider channelProvider;
    AsynchronousFileChannel channel;
    long position;
    boolean closed;

    /**
     * Create a FileByteSource.
     */
    public FileByteSource(Path filePath)
    {
        this( new SimpleChannelProvider(filePath) );
    }

    /**
     * Create a FileByteSource.
     */
    public FileByteSource(String filePath)
    {
        this(Paths.get(filePath));
    }

    /**
     * Create a FileByteSource.
     * <p>
     *     The `channelProvider` will provide an `AsynchronousFileChannel` for this source.
     * </p>
     */
    public FileByteSource(ChannelProvider channelProvider)
    {
        // lazy, do nothing. channel is opened upon first read()
        this.channelProvider = channelProvider;
    }


    /**
     * Try to skip forward `n` bytes.
     *
     * @throws IllegalStateException
     *         if this source is closed.
     */
    @Override
    public long skip(long n) throws IllegalArgumentException, IllegalStateException
    {
        _Util.require(n >= 0, "n>=0");

        if(closed)
            throw new IllegalStateException("closed");

        position += n;  // may skip beyond EOF.
        return n;
    }

    /**
     * Read the next chunk of bytes.
     *
     * @throws IllegalStateException
     *         if this source is closed.
     */
    @Override
    public Async<ByteBuffer> read() throws IllegalStateException
    {
        // does not support cancel. we have no control of it in async file chann API.
        // hopefully file system is pretty fast, read will be served very soon anyway.

        if(closed)
            throw new IllegalStateException("closed");

        if(channel!=null) // usually
            return read2();

        // channel not open. this should be the 1st read(). get channel then read.
        Async<AsynchronousFileChannel> getChannel = channelProvider.openChannel();
        // can be imm completed. can fail.
        return getChannel.then(this::saveChannelAndRead);
    }

    Promise<ByteBuffer> saveChannelAndRead(AsynchronousFileChannel channel)
    {
        this.channel = channel;
        return read2();
    }

    Promise<ByteBuffer> read2()
    {
        ReadCompleteHandler handler = new ReadCompleteHandler();

        ByteBuffer buffer = bbPool.checkOut();
        try
        {
            channel.read(buffer, position, buffer, handler);  // throws
        }
        catch (Exception e)
        {
            handler.failed(e, buffer);
        }

        return handler.promise;
    }

    class ReadCompleteHandler implements CompletionHandler<Integer, ByteBuffer>
    {
        Promise<ByteBuffer> promise = new Promise<>();
        // does not support cancel. hopefully file system is fast enough and the read completes quickly

        @Override public void completed(Integer result, ByteBuffer buffer)
        {
            int r = result.intValue();
            if(r<0) // should be -1
            {
                bbPool.checkIn(buffer);
                promise.fail(End.instance());
            }
            else // r>=0 (probably never r==0)
            {
                position += r;

                buffer.flip(); // for get
                ByteBuffer bb = _ByteBufferUtil.copyOf(buffer);
                bbPool.checkIn(buffer);
                promise.succeed(bb);
                // if r==0, we served spurious result. that's bad, but no big deal.
            }
        }
        @Override public void failed(Throwable error, ByteBuffer buffer)
        {
            // the error is considered transient, maybe recoverable
            Exception ex = (error instanceof Exception)? (Exception)error : new Exception(error);
            bbPool.checkIn(buffer);
            promise.fail(ex);
        }
    }






    /**
     * Close this source.
     */
    @Override
    public Async<Void> close()
    {
        if(closed)
            return Async.VOID;
        closed = true;

        if(channel!=null)
        {
            channelProvider.closeChannel(channel);
            channel=null;
        }
        return Async.VOID;
    }

















    static final Set<StandardOpenOption> READ = Collections.singleton(StandardOpenOption.READ);
    static final ExecutorService EXEC = null; // use default.
    static final FileAttribute<?>[] FILE_ATTRS = new FileAttribute<?>[0];  // none

    static AsynchronousFileChannel syncOpenChannel(Path filePath) throws IOException
    {
        return AsynchronousFileChannel.open(filePath, READ, EXEC, FILE_ATTRS);  // blocking
    }
    static Async<AsynchronousFileChannel> asyncOpenChannel(final Path file)
    {
        return Async.execute(() -> syncOpenChannel(file));
    }
    static void _closeChannel(AsynchronousFileChannel channel)
    {
        try
        {
            channel.close(); // assume non-blocking
        }
        catch (Exception e) // not supposed to happen
        {
            _Logger.of(FileByteSource.class).error("%s", e);
        }
    }





    // simply open and close channel
    static class SimpleChannelProvider implements ChannelProvider
    {
        final Path file;
        public SimpleChannelProvider(Path file)
        {
            this.file = file;
        }

        @Override
        public Async<AsynchronousFileChannel> openChannel()
        {
            return asyncOpenChannel(file);
        }

        @Override
        public void closeChannel(AsynchronousFileChannel channel)
        {
            _closeChannel(channel);
        }
    }

    // opening an AsynchronousFileChannel is not cheap, it's a sys call at least.
    // if we share a channel between concurrent readers, that can save some resources.
    // for small files, sharing is especially beneficial, because 1 sys call is saved.
    //     local http server serving small files:
    //         windows sees improvement from 10k req/s to 20k,
    //         linux sees 20k to 30k.
    // for big files, multiple readers reading the same channel can be slightly slower
    //     than each reader reading its own channel.

    // real-world http server is usually network bound, so sharing or not probably doesn't affect throughput.
    // still, multiple requests could be opening the same file, and if we share the channel, resource is saved.
    // we can have a lot less open file handles.

    // provide shared channel to concurrent readers. channel is closed when all readers returned it.
    // (even if only one reader at a time, the overhead of this class is negligible)

    // brittle: if a reader fails to return the channel, the channel is kept forever.
    // if the channel is broken somehow, we are damned, all future readers will get the broken channel.
    // if all readers do return the channel, and if the channel is broken somehow,
    // it's likely that any new reader getting the broken channel will return it immediately,
    // if new readers (e.g. http requests) don't come up too fast, checkOutCount should drop to 0 quickly,
    // so the broken channel will be discarded, and a new channel will be opened.

    static class SharedChannelProvider implements ChannelProvider
    {
        // a global off button, in case sharing causes problem on some systems.
        // when disabled, this behaves the same as the simple provider
        static boolean disable = Boolean.getBoolean(SharedChannelProvider.class.getName()+".disable");

        final Path file;

        final Object lock(){ return this; }

        Promise<AsynchronousFileChannel> promise;
        // null     : checkOutCount==0
        // pending  : channel is being opened
        // complete : channel is opened, successfully

        int checkOutCount;  // number of clients that got the async, but has not returned the channel
        int nOpen, nClose;

        public SharedChannelProvider(Path file)
        {
            this.file = file;
        }

        @Override
        public Async<AsynchronousFileChannel> openChannel()
        {
            if(disable)
            {
                return asyncOpenChannel(file);
            }

            synchronized (lock())
            {
                if(promise !=null)  // opening or opened
                {
                    if(checkOutCount==Integer.MAX_VALUE)
                        return Result.failure(new RuntimeException("checkOutCount overflow"));
                    checkOutCount++;
                    return promise;  // note: return shared promise to different callers on different flows!
                }

                // async==null
                nOpen++;
                promise = new Promise<>();
                // does not support cancel. hopefully file system is fast enough and open completes quickly
                // since multiple consumers are awaiting on the promise, it's unclear what to do if one wants to cancel.
                assert checkOutCount==0;
                checkOutCount=1;
                // continue to open channel
            }
            _Exec.executeB(this::_openChannel);
            return promise;
        }

        void _openChannel() // blocking
        {
            AsynchronousFileChannel channelX = null;
            try
            {
                channelX = syncOpenChannel(file); // blocking
            }
            catch (Exception e)
            {
                Promise<AsynchronousFileChannel> _async;
                synchronized (lock())
                {
                    _async = promise;
                    promise = null;
                    checkOutCount=0;
                }
                _async.fail(e);
                return;
            }

            promise.succeed(channelX);
            // trigger multiple completion callbacks;
            // each is executed on the local AsyncExec at the time of onCompletion() call.
        }


        @Override
        public void closeChannel(AsynchronousFileChannel channelX)
        {
            if(disable)
            {
                _closeChannel(channelX);
                return;
            }

            boolean toClose=false;
            synchronized (lock())
            {
                assert checkOutCount > 0;
                checkOutCount--;

                if(checkOutCount==0)
                {
                    nClose++;
                    promise = null;
                    toClose=true;
                }
            }

            if(toClose)
                _closeChannel(channelX);
        }
    }
}
