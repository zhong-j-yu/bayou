package bayou.html;

/**
 * Metadata of an html element, including its name etc.
 * <p>
 *     See {@link HtmlElement#getElementType()}.
 * </p>
 * <p>
 *     The properties of this object are mainly used in rendering.
 * </p>
 */
public class HtmlElementType
{
    /**
     * The name of this element, for example "div".
     */
    public final String name;

    final String startTag;   // "<name>"
    final String startTagX;  // "<name "
    final String endTag;     // "</name>", null if no end tag.

    /**
     * Whether this is a "void" element, one that cannot contain children.
     * <p>
     *     For example, &lt;img&gt; is a void element.
     * </p>
     * <p>
     *     A void element must not have an end tag.
     *     Don't use self-closing (e.g. &lt;img ... /&gt;) either.
     * </p>
     */
    public final boolean isVoid;

    /**
     * Whether this is a block element.
     * <p>
     *     For example, &lt;div&gt; is a block element.
     * </p>
     * <p>
     *     It is safe to introduce spaces around block elements.
     *     See {@link HtmlPiece#isBlock()}.
     * </p>
     * <p>
     *     An element is either a block or an inline element.
     * </p>
     */
    public final boolean isBlock;

    // block-ish element, but spaces inside are important.
    /**
     * Whether spaces should be preserved inside this element.
     * <p>
     *     For example, <code> &lt;pre&gt; &lt;script&gt; &lt;style&gt; &lt;textarea&gt; </code>
     *     are "pre" elements.
     * </p>
     */
    public final boolean isPre;  // pre script style textarea.
    // textarea is both inline and pre.

    /**
     * Create an HtmlElementType.
     * <p>
     *     Example usage: {@code new HtmlElementType("div", false, true, false)}
     * </p>
     */
    public HtmlElementType(String name, boolean isVoid, boolean isBlock, boolean isPre)
    {
        this.name = name;     // we don't check name. it's complicated. hopefully name is valid.
        this.isVoid = isVoid;
        this.isBlock = isBlock;
        this.isPre = isPre;

        this.startTag  = "<"+name+">";
        this.startTagX = "<"+name+" ";
        this.endTag = isVoid? null : "</"+name+">";
    }

}
