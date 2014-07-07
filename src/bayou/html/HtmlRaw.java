package bayou.html;

import _bayou._tmp._Array2ReadOnlyList;

import java.util.List;
import java.util.function.Consumer;

/**
 * Html raw content.
 * <p>
 *     The content will be rendered verbatim, without any escaping.
 * </p>
 * <p>
 *     For example,
 *     <code>`new HtmlRaw("&lt;td&gt;", 1, "&lt;/td&gt;")`</code> will be rendered as
 *     <code>"&lt;td&gt;1&lt;/td&gt;"</code>.
 * </p>
 *
 */
// can be used for things like doc type, copy&paste html snippet, etc.
// note: raw chars, not raw bytes.
public class HtmlRaw implements HtmlPiece
{
    final Object[] content;

    /**
     * Create an HtmlRaw.
     * <p>
     *     See {@link #getContent()}.
     * </p>
     * <p>
     *     If an `obj` in `content` is not a CharSequence, it will be converted to one by
     *     `String.valueOf(obj)`. `obj` can be `null`.
     * </p>
     */
    public HtmlRaw(Object... content)
    {
        this.content = HtmlHelper.toCharSeq(content);
    }

    /**
     * Get the content of this piece.
     * <p>
     *     The returned char sequences are from `content` passed to the constructor.
     * </p>
     */
    // can be used in unit test
    public List<CharSequence> getContent()
    {
        return new _Array2ReadOnlyList<>(content);
    }

    /**
     * Write the content to `out`, without any escaping.
     */
    @Override
    public void render(int indent, Consumer<CharSequence> out)
    {
        for(Object csq : content)
            out.accept((CharSequence)csq);
    }
}
