package bayou.text;

import _bayou._tmp._CharSeqSaver;
import bayou.mime.ContentType;

import java.nio.charset.Charset;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Textual document, for example text/html, text/plain.
 * <p>
 *     A TextDoc has a {@link ContentType},
 *     and a content body which is a sequence of chars.
 * </p>
 */
public interface TextDoc
{
    // e.g. "text/html;charset=UTF-8"

    /**
     * Get the content type of this document, for example "text/html;charset=UTF-8".
     * <p>
     *     The content type should contain a "charset" parameter that is consistent with
     *     {@link #getCharset()}.
     * </p>
     */
    ContentType getContentType();

    /**
     * Get the charset of this document, for example "UTF-8".
     * <p>
     *     The charset should be consistent with {@link #getContentType()}.
     * </p>
     */
    // usually a doc knows better about it's charset.
    // user of the doc doesn't have to honor the charset. he can use a diff one for chars->bytes
    Charset getCharset();

    /**
     * Print the content body to `out`.
     * <p>
     *     The implementation of this method may invoke `out.accept()` multiple times.
     * </p>
     */
    void getContentBody(Consumer<CharSequence> out);

    /**
     * Get the content body.
     * <p>
     *     This default implementation invokes {@link #getContentBody(Consumer) getContentBody(out)}
     *     and merges outputs to one char sequence.
     * </p>
     */
    default CharSequence getContentBody()
    {
        _CharSeqSaver out = new _CharSeqSaver(256);
        getContentBody(out);
        return out.toCharSequence();
    }
}
