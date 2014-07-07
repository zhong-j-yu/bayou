package bayou.form;

import _bayou._log._Logger;
import _bayou._tmp._Exec;
import bayou.mime.ContentType;
import bayou.mime.FileSuffixToContentType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A file in a form data set.
 */

// spurious file: browser behavior: if a form has a file field, but it's not filled by user,
// upon submission, browser usually will send a spurious file:
//    filename="", size=0, Content-Type=application/octet-stream
// our parse will discard it, not put it in FormData

public class FormDataFile
{
    // fileName should not contain CR or LF. it's highly unlikely, we don't check here.
    // warn: fileName may be from client; may contain invalid chars in local file system
    final String fileName;

    final ContentType contentType;  // can be null if client does not supply the info

    final Path localPath; // may not contain fileName

    final long size;

    /**
     * Create a FormDataFile.
     */
    public FormDataFile(String fileName, ContentType contentType, Path localPath, long size)
    {
        this.fileName = fileName;
        this.contentType = contentType;

        this.localPath = localPath;
        this.size = size;
    }

    /**
     * Create a FormDataFile based on the local file.
     * <p>
     *     The ContentType is looked up from {@link bayou.mime.FileSuffixToContentType#getGlobalInstance()}.
     * </p>
     * <p>
     *     CAUTION: this method reads file metadata from OS, which may involve blocking IO operations.
     * </p>
     * @throws RuntimeException if the file does not exist
     */
    // may read disk. may block, may throw.
    // convenience method, mostly for testing.
    public static FormDataFile of(String localPath)
    {
        Path path = Paths.get(localPath);
        long size;
        try
        {
            size = Files.size(path); // blocking, throws
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }

        String fileName = path.getFileName().toString();
        ContentType contentType = FileSuffixToContentType.getGlobalInstance().find(localPath); // could be null

        return new FormDataFile(fileName, contentType, path, size);
    }

    /**
     * The name of the file, as reported by the client.
     * <p>
     *     The file name may contain chars that are invalid on the server's file system.
     * </p>
     */
    public String fileName(){ return fileName;}

    /**
     * The content type of the file, as reported by the client.
     * <p>
     *     This method may return null.
     * </p>
     */
    public ContentType contentType(){ return contentType;}

    /**
     * Where the file is stored on the local file system.
     * <p>
     *     {@link FormParser} will store the file on local file system,
     *     and this path identifies where it is stored.
     * </p>
     * <p>
     *     This path does not necessarily contain {@link #fileName()}.
     * </p>
     */
    public Path localPath(){ return localPath;}

    /**
     * The size of the file.
     */
    public long size(){ return size;}


    /**
     * A string representing this object, for diagnosis purpose.
     */
    @Override
    public String toString()
    {
        return String.format("FormDataFile{fileName=%s, contentType=%s, size=%,d, path=%s}",
                fileName, contentType, size, localPath);
    }

    // no methods for move/copy etc. use `Files` class. note they might be blocking.

    /**
     * Delete the local file identified by <code>localPath</code>.
     * <p>
     *     This method is non-blocking; errors will be silently logged.
     * </p>
     */
    // ok if file doesn't exist. e.g. file was moved
    public void delete()
    {
        // non-blocking. silent. error will be logged, but not considered significant to app logic.
        // if these are not what the user wants, he can use `Files` to delete instead.
        _Exec.executeB(() -> del(localPath));
    }

    static void del(Path path)  // silent, blocking
    {
        try
        {   Files.deleteIfExists(path);   }
        catch (Exception e)
        {   _Logger.of(FormDataFile.class).error("Fail to delete file: %s", path, e);   }
    }


}
