package bayou.file;

import bayou.bytes.ByteSource;
import bayou.http.HttpEntity;
import bayou.mime.ContentType;
import bayou.mime.FileSuffixToContentType;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;

/**
 * An HttpEntity based on a file.
 * <p>
 *     The body of the entity is the content of the file.
 * </p>
 * <p>
 *     This is a <a href="../http/HttpEntity.html#sharable">sharable</a> entity.
 * </p>
 */
public class FileHttpEntity implements HttpEntity
{
    final Path filePath;
    final FileByteSource.ChannelProvider fileSCP;
    // multiple concurrent body readers will share the same file channel

    final Long fileSize;
    final Instant lastModified;

    final ContentType contentType; // null is legal.

    // missing properties: expires and contentEncoding.
    // user can subclass to provide these properties.
    // contentEncoding:
    //   say the file is abc.html.gz, then Content-Type: text/html, Content-Encoding: gzip

    // constructors may block. tho usually not for very long. (user may take the risk treating it as if non-blocking)
    // use AsyncJ8.execAsync(()->new FileEntity()) for non-blocking.
    // blocking constructors are useful in blocking context, e.g. during app start up.

    /**
     * Create an FileHttpEntity over the file specified by `filePath`.
     * <p>
     *     If contentType==null, we'll use
     *     {@link bayou.mime.FileSuffixToContentType#getGlobalInstance()}
     *     to get the content type from the file suffix.
     * </p>
     * <p>
     *     CAUTION: this constructor reads file metadata from OS, which may involve blocking IO operations.
     * </p>
     */
    public FileHttpEntity(Path filePath, ContentType contentType) throws Exception
    {
        if(contentType==null)
            contentType = FileSuffixToContentType.getGlobalInstance().find(filePath.toString());

        this.filePath = filePath;
        this.fileSCP = FileByteSource.ChannelProvider.pooled(filePath);

        this.contentType = contentType;

        // following actions may spin the disk and block

        // we want to find as many problems as we can here, instead of later when body is accessed
        BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class); //follows sym link
        // line above throws NoSuchFileException if filePath doesn't exist
        if(!attrs.isRegularFile())
            throw new NoSuchFileException(filePath.toString());

        // it would be nice if we check readability here. but it's kind of slow, having visible impact on throughput.
        // usually file is readable anyway - app checks first if user is allowed to access the file,
        // based on dir etc. if allowed, app should have configured the file to be readable.
        // if the file is indeed unreadable, error occur when body is read, client will get no response.
        if(false)
        {
            if(!Files.isReadable(filePath))  // follows sym link
                throw new AccessDeniedException(filePath.toString(), null, "file is not readable");
        }

        fileSize = attrs.size();

        lastModified = attrs.lastModifiedTime().toInstant();
    }

    /**
     * The body of the entity, same as the content of the file.
     */
    @Override
    public ByteSource body()
    {
        return new FileByteSource(fileSCP);
    }

    /**
     * The content type.
     */
    @Override
    public ContentType contentType() { return contentType; }

    /**
     * Return the size of the file.
     */
    @Override
    public Long contentLength() { return fileSize; }

    /**
     * Return the last modified time of the file.
     */
    @Override
    public Instant lastModified() { return lastModified; }

}
