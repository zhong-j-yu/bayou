package bayou.http;

import bayou.bytes.ByteSource;
import bayou.bytes.SimpleByteSource;
import bayou.mime.ContentType;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.stream.Stream;

/**
 * An HttpEntity of some bytes.
 * <p>
 *     The bytes are supplied to the constructor, as the body of the entity.
 * </p>
 * <p>
 *     This is a <a href="HttpEntity.html#sharable">sharable</a> entity.
 * </p>
 */
public class SimpleHttpEntity implements HttpEntity
{
    final ContentType contentType; // may be null.
    final Instant lastModified;

    final ArrayList<ByteBuffer> bbs;
    final Long bodyLength;

    /**
     * Create an HttpEntity with `bytes` as the body.
     */
    public SimpleHttpEntity(ContentType contentType, Stream<ByteBuffer> bytes)
    {
        // contentType may be null. we don't care.

        this.contentType = contentType;
        this.lastModified = Instant.now();

        ArrayList<ByteBuffer> list = new ArrayList<>();
        long[] sum={0};

        bytes.forEach(bb -> {
            list.add(bb);
            sum[0] += bb.remaining();
        });

        this.bbs = list;
        this.bodyLength = sum[0];
    }

    /**
     * Create an HttpEntity with `bytes` as the body.
     */
    public SimpleHttpEntity(ContentType contentType, ByteBuffer bytes)
    {
        this(contentType, Stream.of( bytes ));
    }

    /**
     * Create an HttpEntity with `bytes` as the body.
     */
    public SimpleHttpEntity(ContentType contentType, byte[] bytes)
    {
        this(contentType, ByteBuffer.wrap(bytes));
    }


    /**
     * The body of this entity.
     */
    @Override
    public ByteSource body()
    {
        // entity can be shared. getBody() can be called multiple times.
        // SimpleByteSource will not modify buffers.
        return new SimpleByteSource(bbs.stream());
    }

    /**
     * The content type.
     */
    @Override
    public ContentType contentType()
    {
        return contentType;
    }

    /**
     * The length of the body.
     */
    @Override
    public Long contentLength()
    {
        return bodyLength;
    }

    /**
     * When this entity was last modified.
     * <p>
     *     This implementation returns the time this object was instantiated.
     * </p>
     */
    @Override public Instant lastModified()
    {
        return lastModified;
    }

}
