package bayou.html;

import java.util.List;
import java.util.function.Consumer;

/**
 * An HtmlPiece that can contain child pieces.
 * <p>
 *     The children are in an ordered list, indexed from 0 to getChildCount()-1.
 * </p>
 * <p>
 *     A child piece cannot be null.
 * </p>
 */

// this interface has only most basic methods. fancier methods can be provided by subclasses
// TBA: HtmlGroup<:HtmlParent, for a list of pieces.
//      probably not very useful; use span/div instead.
//      user can easily create a HtmlGroup if they want to.
// why even have this interface? why not just use class HtmlParentElement?
//     because we need multiple inheritance to support html4/5 inheritance relation. see Html4.ParentElement
//     may also support non-element parents.
public interface HtmlParent extends HtmlPiece
{
    // we only append/detach. insert/remove/replace may be used by app


    /**
     * Get the number of children.
     */
    public int getChildCount();

    /**
     * Get the child at the index.
     */
    public HtmlPiece getChild(int index);

    /**
     * Replace the child at the index.
     * @return the previous child at the index.
     */
    public HtmlPiece setChild(int index, HtmlPiece newChild);

    /**
     * Insert the child at the index.
     * <p>
     *     Required: 0 &lt;= index &lt;= getChildCount()
     * </p>
     */
    public void addChild(int index, HtmlPiece child);

    /**
     * Remove the child at the index.
     * @return the previous child at the index.
     */
    public HtmlPiece removeChild(int index);




    // default methods.


    /**
     * Append the child piece.
     */
    default public void addChild(HtmlPiece child)
    {
        addChild(getChildCount(), child);
    }

    /**
     * Append the child pieces.
     * <p>
     *     If a `child` in `children` is not an HtmlPiece,
     *     it will be converted to text by {@link HtmlText#HtmlText(Object...) new HtmlText(child)}.
     * </p>
     */
    // for ContextParent
    default public void addChildren(Object... children)
    {
        for(Object child : children)
        {
            HtmlPiece piece = (child instanceof HtmlPiece)? (HtmlPiece)child : new HtmlText(child);
            addChild(piece);
        }
    }

    /**
     * Detach child pieces from this parent.
     * <p>
     *     See the specification in
     *     {@link bayou.html.HtmlBuilder.ContextParent#detach(Object...) ContentParent.detach(args)}
     * </p>
     * <p>
     *     The implementation of this method must treat the array `args` as read-only.
     * </p>
     */
    default public void detachChildren(Object... args)
    {
        int argSize = args.length;
        int n = getChildCount();
        while(argSize>0 && n>0)
        {
            --n;
            HtmlPiece lastChild = getChild(n);

            while(argSize>0 && args[argSize-1]!=lastChild)
                --argSize;

            if(argSize==0)
                return;

            removeChild(n);
            --argSize;
        }
    }

    /**
     * Get a live List view of the children.
     * <p>
     *     The returned List supports read and write.
     * </p>
     */
    // we don't use this method, but users may want to.
    default public List<HtmlPiece> getChildList()
    {
        return new HtmlChildList(this);
    }

    /**
     * Render this piece to `out`.
     * <p>
     *     This default implementation just calls render(indent, out) on each child piece,
     *     possibly with indentations inserted between adjacent children.
     * </p>
     */
    // this default impl is not used by us.
    // but user may find it useful. for example, a HtmlGroup, which is just a list of children.
    @Override
    default public void render(int indent, Consumer<CharSequence> out)
    {
        // insert indentation between two adjacent children only if
        // * indent>=0
        // * one of them is block
        // consecutive "inline" children are stuck together as a block; no space between them
        // usually, either all children are inline, or all children are block.

        if(indent<0) // fast path. no indent all the way
        {
            for(int i=0; i<getChildCount(); i++)
                getChild(i).render(indent, out);
            return;
        }

        // indent children
        boolean prevBlock=true; // init value doesn't matter. it's not used when i==0.
        for(int i=0; i<getChildCount(); i++)
        {
            HtmlPiece child = getChild(i);
            boolean thisBlock = child.isBlock();

            if(i>0 && (prevBlock||thisBlock)) // insert indent
                out.accept(HtmlPiece.indent(indent));

            child.render(indent, out);

            prevBlock = thisBlock;
        }
    }

}
