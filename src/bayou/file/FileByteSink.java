package bayou.file;

import bayou.async.Async;
import bayou.async.Promise;
import bayou.bytes.ByteSink;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * A {@link ByteSink} that writes bytes to a file.
 */
public class FileByteSink implements ByteSink
{
    final Path filePath;

    AsynchronousFileChannel channel;
    long position;
    Async<Void> closeAction;

    // todo: buffering?
    // todo: append mode?

    /**
     * Create a FileByteSink.
     */
    public FileByteSink(Path filePath)
    {
        this.filePath = filePath;
        // lazy, do not open file yet
    }

    /**
     * Create a FileByteSink.
     */
    public FileByteSink(String filePath)
    {
        this(Paths.get(filePath));
    }


    @Override
    public Async<Void> write(ByteBuffer bb)
    {
        if(closeAction!=null)
            throw new IllegalStateException("closed");

        if(channel!=null) // usually
            return write2(bb);

        return openChannel(filePath) // may fail
            .then(chann ->
            {
                channel = chann;
                return write2(bb);
            });
    }

    Promise<Void> write2(ByteBuffer bb)
    {
        WriteCompleteHandler handler = new WriteCompleteHandler();
        write3(bb, handler);
        return handler.promise;
    }

    void write3(ByteBuffer bb, WriteCompleteHandler handler)
    {
        try
        {
            channel.write(bb, position, bb, handler);  // throws
        }
        catch (Exception e)
        {
            handler.failed(e, bb);
        }
    }

    class WriteCompleteHandler implements CompletionHandler<Integer, ByteBuffer>
    {
        Promise<Void> promise = new Promise<>();
        // does not support cancel. hopefully file system is fast enough and the write completes quickly

        @Override public void completed(Integer result, ByteBuffer bb)
        {
            int bytesWritten = result.intValue();
            position += bytesWritten;
            if(bb.hasRemaining()) // this probably never occurs
            {
                write3(bb, this);
                return;
            }
            promise.succeed(null);
        }
        @Override public void failed(Throwable error, ByteBuffer bb)
        {
            // file in unknown state. we don't persist this error, but writer should abort.
            Exception ex = (error instanceof Exception)? (Exception)error : new Exception(error);
            promise.fail(ex);
        }
    }

    @Override
    public Async<Void> error(Exception error)
    {
        // hmm.. what to do? delete file?
        return Async.VOID;
    }

    @Override
    public Async<Void> close()
    {
        if(closeAction!=null)
            return closeAction;

        closeAction = Async.execute(()->
        {
            if(channel!=null) // could be null - never opened
            {
                channel.close(); // probably blocking. flushing data to disk
                channel=null;
            }
            return null;
        });
        return closeAction;
    }


    static final Set<StandardOpenOption> OPTIONS
        = EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    static final ExecutorService EXEC = null; // use default.
    static final FileAttribute<?>[] FILE_ATTRS = {} ;  // none, for now
    // on linux, the only permissions are rw for owner. this might be too limited. TBA.
    // note: we can use PosixFilePermissions, but not on windows (will throw error)

    static Async<AsynchronousFileChannel> openChannel(Path path)
    {
        // may fail
        return Async.execute(()->AsynchronousFileChannel.open(path, OPTIONS, EXEC, FILE_ATTRS));
    }

}
