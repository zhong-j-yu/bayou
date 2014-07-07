package bayou.html;

import java.util.function.Consumer;

/**
 * A new line ("\n") in html.
 * <p>
 *     See the {@link #render(int, java.util.function.Consumer) render(indent, out)} method in this class
 *     for how it's actually rendered.
 * </p>
 * <p>
 *     The rendering algorithm will not automatically introduce spaces between two inline elements.
 *     You can manually insert an HtmlNewLine between two inline elements for formatting purpose
 *     (if it's ok to insert spaces between them).
 * </p>
 * <p>
 *     See also {@link HtmlBuilder#_newline()}.
 * </p>
 */
public final class HtmlNewLine implements HtmlPiece
{
    /**
     * An instance of HtmlNewLine.
     * Note that HtmlNewLine is stateless.
     */
    public static final HtmlNewLine instance = new HtmlNewLine();

    // we don't care to make this class a singleton class.
    // users can create new instances if they want to.

    /**
     * Create an HtmlNewLine instance.
     * <p>
     *     Note that HtmlNewLine is stateless. You can use
     *     {@link HtmlNewLine#instance HtmlNewLine.instance} instead.
     * </p>
     */
    public HtmlNewLine()
    {

    }

    /**
     * Render a new line to `out`.
     * <p>
     *     More accurately, render an indentation string
     *     {@link HtmlPiece#indent(int) HtmlPiece.indent(indent)} to `out`.
     * </p>
     * <p>
     *     Note that if `indent&lt;0`, nothing will be rendered.
     *     If necessary, use {@link HtmlRaw}("\n") instead.
     * </p>
     */
    @Override
    public void render(int indent, Consumer<CharSequence> out)
    {
        out.accept(HtmlPiece.indent(indent));
    }
}
