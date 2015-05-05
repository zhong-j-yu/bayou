package bayou.form;

import _bayou._tmp._Exec;
import bayou.async.Async;
import bayou.async.Fiber;
import bayou.async.Promise;
import bayou.bytes.ByteSource;
import bayou.mime.ContentType;
import bayou.util.End;
import bayou.util.OverLimitException;
import bayou.util.Result;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

// on my pc hard disk: write ~20M bytes / second
// the logic flow is pretty messy; can be made better later.
class DoSaveToTmpFile
{
    String filename;
    ContentType contentType;

    long maxFileSize;

    Path dir;

    ByteSource src;

    Path filePath;
    AsynchronousFileChannel fileChannel;

    long position;

    ByteBuffer bb;

    DoSaveToTmpFile(String filename, ContentType contentType,
                    long maxFileSize, Path dir, ByteSource src)
    {
        this.filename = filename;
        this.contentType = contentType;

        this.maxFileSize = maxFileSize;
        this.dir = dir;

        this.src =src;
    }

    Executor executor;
    Promise<FormDataFile> promise;
    Async<FormDataFile> start()
    {
        executor = Fiber.currentExecutor();

        promise = new Promise<>();

        _Exec.executeB(this::openTmpFile);

        return promise;
    }

    Void err(Exception error)
    {
        bb = null;

        src.close();
        src = null;

        if(fileChannel!=null)
        {
            try
            {
                fileChannel.close(); // assume non-blocking
            }
            catch (Exception e)
            {
                error.addSuppressed(e);
            }
            fileChannel = null;
        }

        if(filePath!=null)
        {
            final Path filePathL = filePath;
            filePath = null;
            _Exec.executeB(() -> FormDataFile.del(filePathL));
        }

        promise.fail(error);
        return null;
    }


    static final Set<StandardOpenOption> WRITE = Collections.singleton(StandardOpenOption.WRITE);
    static final ExecutorService EXEC = null; // use default.
    static final FileAttribute<?>[] FILE_ATTRS = {} ;  // none, for now
    // on linux, the only permissions are rw for owner. this might be too limited. TBA.
    // note: we can use PosixFilePermissions, but not on windows (will throw error)

    Void openTmpFile() // blocking, hopefully only for a very short time
    {
        try
        {
            Files.createDirectories(dir);
            filePath = Files.createTempFile(dir, "", "");
            fileChannel = AsynchronousFileChannel.open(filePath, WRITE, EXEC, FILE_ATTRS);
        }
        catch (Exception e)
        {
            return err(e);
        }

        executor.execute(readSource);
        return null;
    }

    Runnable readSource = ()->
    {
        // if promise is cancelled, we forward the cancel request to `bbAsync`.
        // however every bbAsync may miss or ignore the cancel request.
        // that's why we checked cancel request earlier to make sure to break loop on cancel.

        Exception cancelReq = promise.pollCancel();
        if(cancelReq!=null)
        {
            err(cancelReq);
            return;
        }

        Async<ByteBuffer> bbAsync = src.read();
        promise.onCancel(bbAsync::cancel);  // source can be slow (e.g. request body from client)

        bbAsync.onCompletion(this::acceptSrcReadResult);
    };

    Void acceptSrcReadResult(Result<ByteBuffer> result)
    {
        ByteBuffer bb;
        try
        {
            bb = result.getOrThrow();
        }
        catch (End end)
        {
            bb = null;
        }
        catch (Exception e)
        {
            return err(e);
        }

        if(bb==null) // EOF
        {
            try
            {
                fileChannel.close(); // assume non-blocking
                fileChannel = null;
            }
            catch (Exception e) // uh?
            {
                fileChannel = null;  // so it won't be closed again in err()
                return err(e);
            }

            src.close();
            src = null;

            promise.succeed(new FormDataFile(filename, contentType, filePath, position));  // DONE
            return null;
        }

        if(position+bb.remaining()>maxFileSize)
        {
            return err(new OverLimitException("maxFileSize",maxFileSize));
        }

        this.bb = bb;
        // usually bb is a heap buffer from TcpConnection.read(), which won't be empty or too big.
        // rely on java nio to copy bb to direct buffer.
        // we could be more sophisticated - hoard small bb; copy to our direct buffer. not very important.

        return writeToFile();
    }

    Void writeToFile()
    {
        // write() won't throw
        // observed: completes sync-ly if bb is empty.
        // we don't deal with cancel here. hopefully write is fast enough.
        promise.fiberTracePush();
        fileChannel.write(bb, position, null, new CompletionHandler<Integer, Object>()
        {
            @Override public void failed(Throwable t, Object attachment)
            {
                promise.fiberTracePop();
                Exception ex = (t instanceof Exception)? (Exception)t : new Exception(t);
                err(ex);
            }
            @Override public void completed(Integer result, Object attachment)
            {
                promise.fiberTracePop();
                writeCompleted(result.intValue());
            }
        });
        return null;
    }
    Void writeCompleted(int bytesWritten)
    {
        position += bytesWritten;
        if(bb.hasRemaining()) // this probably never occurs
            return writeToFile();

        bb = null;

        executor.execute(readSource);
        return null;
    }

}
