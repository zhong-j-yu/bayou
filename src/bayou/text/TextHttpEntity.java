package bayou.text;

import _bayou._tmp._CharSeqSaver;
import bayou.bytes.ByteSource;
import bayou.bytes.SimpleByteSource;
import bayou.http.HttpEntity;
import bayou.mime.ContentType;
import bayou.util.End;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * An HttpEntity representing some texts.
 * <p>
 *     The entity body is a sequence of bytes encoding the texts,
 *     using the charset specified in the content type.
 * </p>
 * <p>
 *     This entity is usually for <code>"text/*"</code> content types, but may also be used for
 *     other types like <code>"application/javascript"</code>.
 * </p>
 * <p>
 *     This is a <a href="../http/HttpEntity.html#sharable">sharable</a> entity.
 *     If this entity is to be served to multiple http messages, consider the
 *     {@link bayou.http.CachedHttpEntity} wrapper.
 * </p>
 */
public class TextHttpEntity implements HttpEntity
{
    // if source chars <= 4K, will do eager encoding. Content-Length is known in that case.
    static final int EAGER_CHAR_MAX = 4*1024; // do eager if chars<=4K
    static final int BUF_SIZE = 4*1024;

    final ContentType contentType;
    final Charset charset;
    final Instant lastModified;

    final ArrayList<ByteBuffer> bufList; // result from eager encoding
    final Long byteCount;

    final List<CharSequence> charSrc; // for lazy encoding

    // contentType should also include charset param
    TextHttpEntity(ContentType contentType, Charset charset, _CharSeqSaver saver)
    {
        this.contentType = contentType;
        this.charset = charset;
        this.lastModified = Instant.now();

        // eager encoding: immediately encode chars to bytes here.
        //
        // we don't always want to encode eagerly.
        // for example, the source contains some big cached strings, shared by all responses.
        // a new text entity is created per response. if we eagerly encode, we produce a lot of
        // bytes upfront; since clients are slow, the bytes will live for a long time, wasting memory.
        // lazy encoding can avoid that problem - bytes are generated on demand.
        //
        // however, if the number of chars is small, we do want to encode eagerly.
        // it's not too wasteful since the bytes produced won't be too many either.
        // we want to encode eagerly, to find out Content-Length, which is useful for
        // auto-gzip (which needs Content-Length to decide to gzip or not)
        // also, apache ab seems *very* slow in parsing chunked response.
        //
        // because we have to iterate to find charCount, that may be more wasteful than a
        // pure lazy strategy (i.e. do nothing until getBody()) which only produce chars on demand.
        // if that's a problem, don't use this class, write a custom lazy entity.

        if(saver.charCount<=EAGER_CHAR_MAX) // do eager
        {
            charSrc = null;
            bufList = new ArrayList<>();
            byteCount = doEagerEncoding(saver, charset, bufList);
        }
        else // do lazy
        {
            charSrc = saver;
            bufList = null;
            byteCount = null;
        }
    }

    /**
     * Create a TextHttpEntity over the TextDoc.
     */
    public TextHttpEntity(TextDoc doc)
    {
        this(doc.getContentType(), doc.getCharset(), saver(doc));
    }

    /**
     * Create a TextHttpEntity of the texts.
     * <p>
     *     The content type should have a "charset" parameter, otherwise UTF-8 is used for encoding.
     * </p>
     */
    public TextHttpEntity(ContentType contentType, Stream<? extends CharSequence> texts)
    {
        this(contentType, extract_charset(contentType), saver(texts));
    }

    /**
     * Create a TextHttpEntity of the texts.
     * <p>
     *     The content type should have a "charset" parameter, otherwise UTF-8 is used for encoding.
     * </p>
     */
    public TextHttpEntity(ContentType contentType, CharSequence... texts)
    {
        this(contentType, extract_charset(contentType), saver(texts));
    }

    /**
     * Create a TextHttpEntity of the texts, as <code>"text/plain;charset=UTF-8"</code>.
     */
    public TextHttpEntity(CharSequence... texts)
    {
        this(ContentType.text_plain_UTF_8, StandardCharsets.UTF_8, saver(texts));
    }

    static Charset extract_charset(ContentType contentType)
    {
        String param = contentType.params().get("charset");
        if(param!=null)
            return Charset.forName(param);
        // rfc2046#section-4.1.2 says default charset is US_ASCII;
        // if texts are all ascii chars, UTF-8 yield same encoded bytes.
        return StandardCharsets.UTF_8;
    }

    static _CharSeqSaver saver(TextDoc doc)
    {
        _CharSeqSaver saver = new _CharSeqSaver(1000);
        doc.getContentBody(saver);
        return saver;
    }
    static _CharSeqSaver saver(CharSequence... texts)
    {
        _CharSeqSaver saver = new _CharSeqSaver(texts.length);
        for(CharSequence csq : texts)
            saver.accept(csq);
        return saver;
    }
    static _CharSeqSaver saver(Stream<? extends CharSequence> source)
    {
        _CharSeqSaver saver = new _CharSeqSaver(1000);
        source.forEach(saver);
        return saver;
    }


    /**
     * The entity body.
     * <p>
     *     The entity body is a sequence of bytes encoding the texts,
     *     using the charset specified in the content type.
     * </p>
     */
    @Override
    public ByteSource body()
    {
        // entity can be shared. getBody() can be called multiple times.
        if(bufList!=null)
            return new SimpleByteSource(bufList.stream()); // SimpleByteSource will not modify buffers
        else
            return new TextByteSource(BUF_SIZE, newEncoder(charset), charSrc.stream());
        // we need to keep charSrc since this is a shared entity.
        // if this entity is one-off for one response, we are keeping the whole charSrc in memory
        //   till response is all sent. that sucks. hopefully, most parts of the doc, e.g. texts,
        //   are shared by many responses, so the amortized size is small.
        // if this entity is shared for multiple responses, we do encoding for each body()
        //   which sucks. it would be better to encode just once, cache bytes with ByteSourceCache.
        //   user can create a cached entity wrapper.
        // we don't know the usage pattern. the current behavior is a compromise.
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
     * The length of the entity body.
     * <p>
     *     This implementation may return null,
     *     because it's difficult to know the length without actually performing the encoding.
     * </p>
     */
    @Override
    public Long contentLength()
    {
        return byteCount; // null or non-null, depending on lazy/eager encoding
    }

    /**
     * When this entity was last modified.
     * <p>
     *     This implementation returns the time this object was instantiated.
     * </p>
     */
    @Override
    public Instant lastModified()
    {
        return lastModified;
    }

    static long doEagerEncoding(List<CharSequence> list, Charset charset, ArrayList<ByteBuffer> outList)
    {
        TextByteSource src = new TextByteSource(BUF_SIZE, newEncoder(charset), list.stream());
        long byteCount=0;
        try
        {
            while(true)
            {
                ByteBuffer bb = src.read0();  // throws End if no more
                outList.add(bb);
                byteCount+=bb.remaining();
            }
        }
        catch (End end)
        {
            src.close();
            return byteCount;
        }
        catch (Exception e) // uh? should not occur
        {
            src.close();
            throw new RuntimeException(e);
        }
    }



    static CharsetEncoder newEncoder(Charset charset)
    {
        CharsetEncoder encoder = charset.newEncoder();
        encoder.onMalformedInput(CodingErrorAction.REPLACE);
        encoder.onUnmappableCharacter(CodingErrorAction.REPLACE);
        return encoder;
    }

}
