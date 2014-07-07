package bayou.html;

import _bayou._tmp._Array2ReadOnlyList;
import _bayou._tmp._CharSubSeq;

import java.util.List;
import java.util.function.Consumer;

/**
 *  Html comment.
 * <p>
 *     For example,
 *     <code>`new HtmlComment("main article")`</code> will be rendered as
 *     <code>"&lt;!-- main article --&gt;"</code>.
 * </p>
 * <p>
 *     <b id=escaping>Escaping:</b>
 *     consecutive dashes "--" are not allowed in html comments.
 *     We escape it by inserting a backslash. For example,
 *     <code>`new HtmlComment("xx--yy\\zz")`</code> will be rendered as
 *     <code>"&lt;!-- xx-\-yy\\zz --&gt;"</code>.
 *     Note that the backslash is also escaped to "\\".
 * </p>
 */
// we expect comment to be small.
// mostly used by dev to match source code (java) and target code (html).
public class HtmlComment implements HtmlPiece
{
    final Object[] content;

    /**
     * Create an HtmlComment.
     * <p>
     *     See {@link #getContent()}.
     * </p>
     * <p>
     *     If an `obj` in `content` is not a CharSequence, it will be converted to one by
     *     `String.valueOf(obj)`. `obj` can be `null`.
     * </p>
     */
    public HtmlComment(Object... content)
    {
        this.content = HtmlHelper.toCharSeq(content);
    }

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


    // will be properly indented between two blocks
    //     </div>
    //     <!-- xyz -->
    //     <div>

    /**
     * Render this piece as an html comment <code>"&lt;!-- &#46;&#46;&#46; --&gt;"</code>.
     * <p>
     *     <a href="#escaping">Escaping</a> is done by this method.
     * </p>
     */
    @Override
    public void render(int indent, Consumer<CharSequence> out)
    {
        out.accept("<!-- ");

        // consecutive "--" not allowed in comment, we'll escape it as "-\-"
        int lastChar = -1;
        for(Object obj : content)
        {
            CharSequence csq = (CharSequence)obj;
            int L = csq.length();
            int s=0;
            for(int i=0; i<L; i++)
            {
                char c = csq.charAt(i);
                if( c=='\\' || (c=='-' && lastChar=='-') )
                {
                    if(i>s)
                        out.accept(_CharSubSeq.of(csq, s, i));
                    out.accept("\\");
                    s = i;
                }
                lastChar = c;
            }
            if(L>s)
            {
                if(s==0)
                    out.accept(csq);
                else
                    out.accept(_CharSubSeq.of(csq, s, L));
            }
        }

        out.accept(" -->");
    }
}
