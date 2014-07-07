package bayou.html;

import _bayou._tmp._Array2ReadOnlyList;

import java.util.List;
import java.util.function.Consumer;

/**
 * Html textual content.
 * <p>
 *     For example,
 *     <code>`new HtmlText("n&gt;", 1)`</code> will be rendered as
 *     <code>"n&amp;gt;1"</code>.
 * </p>
 * <p>
 *     <b id="escaping">Escaping: </b>
 *     The following chars will be escaped:
 * </p>
 * <ul>
 *     <li><tt>&lt;   &nbsp;&rarr;&nbsp;   &amp;lt;</tt></li>
 *     <li><tt>&gt;   &nbsp;&rarr;&nbsp;   &amp;gt;</tt></li>
 *     <li><tt>&amp;  &nbsp;&rarr;&nbsp;   &amp;amp;</tt></li>
 * </ul>
 * <p>
 *     Note that double/single quotes are not escaped.
 * </p>
 */
public class HtmlText implements HtmlPiece
{
    // double-quote is not escaped.
    // it's quite common in texts. we don't want the performance penalty of escaping it.
    // and it would look ugly when view source.


    final Object[] content;

    /**
     * Create an HtmlText piece.
     * <p>
     *     See {@link #getContent()}.
     * </p>
     * <p>
     *     If an `obj` in `content` is not a CharSequence, it will be converted to one by
     *     `String.valueOf(obj)`. `obj` can be `null`.
     * </p>
     */
    public HtmlText(Object... content)
    {
        this.content = HtmlHelper.toCharSeq(content);
    }
    // we also tried to optimize for single-arg case, it doesn't help.

    /**
     * Get the content of this piece.
     * <p>
     *     The returned char sequences are from `content` passed to the constructor.
     * </p>
     * <p>
     *     No <a href="#escaping">escaping</a> is done by this method.
     *     If you want an escaped result, use `render(0)`.
     * </p>
     */
    // can be used in unit test
    public List<CharSequence> getContent()
    {
        return new _Array2ReadOnlyList<>(content);
    }

    /**
     * Write the content to `out`, <a href="#escaping">escaping</a> special chars.
     */
    @Override
    public void render(int indent, Consumer<CharSequence> out)
    {
        for(Object csq : content)
            HtmlHelper.renderEscaped3((CharSequence) csq, out);
    }
}
