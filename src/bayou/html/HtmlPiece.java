package bayou.html;

import _bayou._tmp._CharSeqSaver;

import java.util.function.Consumer;

/**
 * An arbitrary piece of html.
 * <p>
 *     An HtmlPiece can correspond to an arbitrary sequence of chars in an html document.
 * </p>
 * <p>
 *     The only abstract method of this interface is {@code render(indent, out)},
 *     which renders this piece as a sequence of chars.
 * </p>
 */
public interface HtmlPiece
{
    // try to minimize memory usage, since we'll build a html tree for each response.

    /**
     * Render this piece as a sequence of chars to `out`.
     * <p>
     *     The implementation of this method may invoke `out.accept()` multiple times.
     * </p>
     * <p>
     *     <b>Indentation</b>
     * </p>
     * <p>
     *     The `indent` parameter indicates the indentation level of this piece.
     * </p>
     * <p>
     *     If {@code `indent<0`}, indentation should be suppressed within this piece.
     *     If this is a parent piece, it should not introduce indentations around its children,
     *     and it should pass negative `indent` to the children's render() methods as well.
     * </p>
     * <p>
     *     If {@code `indent>=0`}, this piece is indented.
     *     If this is a parent piece, it may introduce proper indentations around each of its children,
     *     and increment `indent` by 1 when invoking the children's render() methods.
     *     The implementation of this method does not need to add indentations
     *     before/after this piece (it's taken care of by the parent).
     *     Use {@link #indent(int)} method to create an indentation string.
     * </p>
     * <p>
     *     Do not introduce indentation between two inline pieces, see {@link #isBlock()}.
     * </p>
     * <p>
     *     Usually {@code `indent=0`} is passed to the root piece.
     *     Pass {@code `indent=-1`} to the root piece to suppress all indentations.
     * </p>
     * <p>
     *     The `indent` parameter may also be simply ignored by the implementation,
     *     since indentation is not semantically important.
     * </p>
     */
    abstract public void render(int indent, Consumer<CharSequence> out);
    // we used to also have
    //     Iterator<CharSequence> charsIterator(int indent)
    // but it's complicated and slow. it's to save memory in pull-style. however the iter references the whole tree.
    // removed. now when caller needs a char seq iterator, call writeTo() to accumulate all char sequences.
    // assuming most char sequences are shared, this won't consume too much memory.
    // html tree can be discard after that.
    // then usually we encode chars to bytes on demand, only 4K bytes at a time, to avoid buffering too many
    // bytes for a slow client.

    /**
     * Render this piece as a sequence of chars.
     * <p>
     *     The default implementation invokes `render(indent, out)` and merges outputs to one char sequence.
     * </p>
     */
    default public CharSequence render(int indent)
    {
        // render() is usually called on root, there will be a lot of char sequences.
        // ok if 256 is too many, it'll be GC-ed soon
        _CharSeqSaver out = new _CharSeqSaver(256);
        render(indent, out);
        return out.toCharSequence();
    }

    /**
     * Whether this is a "block" piece. For example a {@code <div>...</div>} element is a block piece.
     * <p>
     *     If a piece is not "block", it is "inline".
     * </p>
     * <p>
     *     This property is used by `render()` methods for indentation purposes.
     *     It is safe to introduce indentations before/after a block piece.
     * </p>
     * <p>
     *     However, it is generally not safe to introduce spaces between two adjacent inline pieces
     *     (whether sibling-sibling or parent-child), therefore it's not safe to introduce
     *     indentation between them.
     * </p>
     * <p>
     *     The default implementation returns `false`.
     *     When in doubt, use the default `false`, because extra spaces could be undesirable.
     * </p>
     *
     *
     */
    default public boolean isBlock()
    {
        return false;
    }

    // use Object's impl for these methods
    //   hashCode()
    //   equals()
    //   toString() // for debugging, display info about the object, not html chars.

    /**
     * Write the char sequence to `out`, escaping special chars.
     * <p>
     *     The following chars will be escaped:
     * </p>
     * <ul>
     *     <li><tt>&lt;   &nbsp;&rarr;&nbsp;   &amp;lt;</tt></li>
     *     <li><tt>&gt;   &nbsp;&rarr;&nbsp;   &amp;gt;</tt></li>
     *     <li><tt>&amp;  &nbsp;&rarr;&nbsp;   &amp;amp;</tt></li>
     *     <li><tt>&quot; &nbsp;&rarr;&nbsp;   &amp;quot;</tt></li>
     * </ul>
     * Single quote ' is not escaped.
     */
    // this method might be needed by custom builders, so we publish it.
    public static void renderEscaped(CharSequence csq, Consumer<CharSequence> out)
    {
        // we don't escape single quote as &apos;
        // we don't need it since we only use double quotes around attribute values.
        // it'll be ugly to escape single quotes in most cases.
        // also, &apos; is not defined in html4. but that probably is immaterial.

        HtmlHelper.renderEscaped4(csq, out);
    }

    /**
     * Return an indentation string of the <em>n</em>-th level.
     * <p>
     *     If `n&lt;0`, this method returns an empty string "".
     * </p>
     * <p>
     *     If `n&gt;=0`, this method returns a string that starts with '\n', followed by <em>n</em>&nbsp; '\t' chars.
     * </p>
     * <p>
     *     For example, `indent(2)` returns "\n\t\t".
     * </p>
     */
    // this method might be needed by custom builders, so we publish it.
    public static String indent(int n)
    {
        return HtmlHelper.indent(n);
    }

}
