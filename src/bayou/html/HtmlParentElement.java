package bayou.html;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Html element that can contain child pieces.
 * <p>
 *     A parent element contains a list of child pieces, for example
 * </p>
 * <pre>
        HtmlParentElement div = new Html4.DIV();
        div.addChild(new HtmlText("abc"));
        div.addChild(new Html4.P());
        div.render(0, System.out::print);
        //    &lt;div&gt;
        //        abc
        //        &lt;p&gt;&lt;/p&gt;
        //    &lt;/div&gt;
 * </pre>
 * <p>
 *     This is an abstract class; a concrete subclass needs to implement {@link #getElementType()}.
 * </p>
 * <p>
 *     An HtmlParentElement must not be a {@linkplain HtmlElementType#isVoid void} element.
 * </p>
 */
abstract public class HtmlParentElement extends HtmlElement implements HtmlParent
{
    /**
     * Protected constructor for subclasses.
     */
    protected HtmlParentElement(){}

    /*
    http://www.w3.org/TR/REC-html40/appendix/notes.html#notes-line-breaks
      a line break immediately following a start tag must be ignored,
      as must a line break immediately before an end tag.

    http://www.w3.org/TR/REC-html40/intro/sgmltut.html#h-3.3.3
      end tag must be omitted for EMPTY element

    http://www.w3.org/TR/REC-html40/struct/text.html#whitespace
      except for <pre>, white spaces are used to separate "words"
    */


    /**
     * Render this element to `out`.
     * <p>
     *     See {@link HtmlElement#render(int, java.util.function.Consumer) HtmlElement.render(indent, out)}
     *     for attribute rendering.
     * </p>
     * <p>
     *     Child pieces will be rendered in order,
     *     possibly with indentations added.
     * </p>
     */
    @Override
    public void render(int indent, Consumer<CharSequence> out)
    {
        HtmlElementType type = getElementType();

        if(attributesSize==0)
            out.accept(type.startTag);  // <div>
        else
            writeStartTagTo(type.startTagX, out);       // <div attr=value..>

        assert !type.isVoid;

        if(childrenSize==0)
        {
            // imm followed by end tag. <div></div>

            // self-closing tag, <div/> is illegal in html, it screws up doc tree. for example
            //     doc type html4 strict
            //     <div style="color:red;" /> text outside div
            // text outside div will be red!
        }
        else if(indent<0) // no indentation throughout
        {
            for(int i=0; i<childrenSize; i++)
                children[i].render(indent, out);
        }
        else if(type.isPre)
        {
            out.accept("\n");        // line break imm after start tag, always harmless.
            // [3] preserve space in children. no indent before end tag.
            for(int i=0; i<childrenSize; i++)
                children[i].render(/*indent*/-1, out);

            // example
            //    <pre>
            //  line1
            //  line2
            //  </pre>

            // if children ends with line break, the line break is imm before </pre>, so it'll be gobbled by browser.
            // usually that's not a problem, visually or semantically.
            // the disappearing of the last line break can also be observed, e.g. when copy & paste.
            // if user really wants to preserve the last line break, always manually append an extra one.
            // if our code add \n before </pre>, it appears as an extra blank line, which is ugly.
        }
        else // may indent children
        {
            // insert indentation between two adjacent children only if one of them is block.
            // consecutive "inline" children are stuck together as one piece, no space between them
            // usually, either all children are inline, or all children are block.
            // spaces between block start/end tags and inline children are not problem. e.g.
            // <p> xxx yyy </p>  ==  <p>xxx yyy</p>
            // (no visual difference. when copy&paste, may observe one trailing space)
            // so we can indent before 1st child, and indent after last child.

            boolean prevBlock = this.isBlock();
            for(int i=0; i<childrenSize; i++)
            {
                HtmlPiece child = children[i];
                boolean thisBlock = child.isBlock();

                if(prevBlock||thisBlock) // insert indent
                    out.accept(HtmlPiece.indent(indent+1));

                child.render(indent + 1, out);  // indent+1, even if child is inline

                prevBlock = thisBlock;
            }
            if(prevBlock || this.isBlock())
                out.accept(HtmlPiece.indent(indent));      // indent before end tag

            /*
            example

            <div>
                <div></div>
                <div></div>
            </div>

            <span><a>text</a></span>

            <div>
                <span></span><a></a>
                <div>
                </div>
                <span></span><a></a>
            </div>

            blah <select>  // inline element
                <option></option> // block
            </select> blah

             */
        }

        out.accept(type.endTag);

    }



    // children =================================================================================================
    // inherit javadocs from HtmlParent
    // override super default methods for better performance

    // usually number of children won't be too large.
    HtmlPiece[] children; // lazy. some elements have no child.
    int childrenSize;

    @Override
    public int getChildCount()
    {
        return childrenSize;
    }

    void checkIndex(int index, int upper)
    {
        if(index<0)
            throw new IndexOutOfBoundsException(""+index+"<0");
        if(index>=upper)
            throw new IndexOutOfBoundsException(""+index+">="+upper);
    }

    @Override
    public HtmlPiece getChild(int index)
    {
        checkIndex(index, childrenSize);

        assert children!=null;
        return children[index];
    }

    @Override
    public void addChild(HtmlPiece child)
    {
        if(child==null)
            throw new NullPointerException("child==null");

        makeRoom(1);

        children[childrenSize++] = child;
    }

    // for ContextParent
    // non-HtmlPiece obj will be converted to HtmlText.
    //    actually, we could convert obj to CharSequence csq, then store csq directly in children.
    //    only replace csq with HtmlText when an outsider inquires.
    //    however the saving won't be much. and it matters very little in the build->iterate->encode
    //    process, the bottleneck is usually at the encoding chars->bytes phase.
    @Override
    public void addChildren(Object... children)
    {
        makeRoom(children.length);
        for(Object child : children)
        {
            HtmlPiece piece = (child instanceof HtmlPiece)? (HtmlPiece)child : new HtmlText(child);
            this.children[childrenSize++] = piece;
        }
    }

    @Override
    public void addChild(int index, HtmlPiece child)
    {
        checkIndex(index, childrenSize+1);  // 0<=index<=size

        if(child==null)
            throw new NullPointerException("child==null");

        makeRoom(1);

        int rest = childrenSize - index;
        if(rest>0)
            System.arraycopy(children, index, children, index+1, rest);

        children[index] = child;
        childrenSize++;
    }

    void makeRoom(int n)
    {
        if(children==null)
            children = new HtmlPiece[Math.max(n, 4)];
        else if(children.length<childrenSize+n)
            children = Arrays.copyOf(children, Math.max(childrenSize+n, childrenSize*3/2));
    }

    @Override
    public HtmlPiece removeChild(int index)
    {
        checkIndex(index, childrenSize);

        HtmlPiece child = children[index];

        int rest = childrenSize-1-index;
        if(rest>0)
            System.arraycopy(children, index+1, children, index, rest);
        children[--childrenSize]=null;

        return child;
    }

    @Override
    public void detachChildren(Object... args)
    {
        int argSize = args.length;
        while(argSize>0 && childrenSize>0)
        {
            HtmlPiece lastChild = children[childrenSize-1];

            while(argSize>0 && args[argSize-1]!=lastChild)
                --argSize;

            if(argSize==0)
                return;

            children[--childrenSize]=null;
            --argSize;
        }
    }

    @Override
    public HtmlPiece setChild(int index, HtmlPiece newChild)
    {
        checkIndex(index, childrenSize);

        if(newChild==null)
            throw new NullPointerException("newChild==null");

        HtmlPiece oldChild = children[index];
        children[index] = newChild;
        return oldChild;
    }




}
