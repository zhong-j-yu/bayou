package bayou.html;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Html element.
 * <p>
 *     An html element has attributes.
 *     An attribute is a name-value pair.
 *     For example
 * </p>
 * <pre>
     HtmlElement input = new Html4.INPUT();
     input.setAttribute("id", "q");
     input.setAttribute("disabled", "");
     input.render(0, System.out::print); // &lt;input id="q" disabled&gt;
 * </pre>
 * <p>
 *     For
 *     <a href="http://www.w3.org/TR/REC-html40/intro/sgmltut.html#h-3.3.4.2">boolean attributes</a>,
 *     use "" as true, {@code null} as false, for example
 * </p>
 * <pre>
     input.setAttribute("disabled", "");   // &lt;input name="q" disabled&gt;
     input.setAttribute("disabled", null); // &lt;input name="q"&gt;  // not disabled
 * </pre>
 * <p>
 *     This is an abstract class; a concrete subclass needs to implement {@link #getElementType()}.
 * </p>
 * <p>
 *     See {@link bayou.html.HtmlParentElement} for elements that can contain children.
 * </p>
 */
abstract public class HtmlElement implements HtmlPiece
{
    // empty attr value:
    // we'll render <tag attr> instead of <tag attr="">
    // this is good for boolean attr: <input disabled>
    // but what about non-boolean attr e.g. <a href title>? is it equivalent to  <a href="" title="">?
    // apparently so, according to browser tests, and html5 spec.
    // tho, for non-boolean attr, <a href="" title=""> would look nicer than <a href title>
    // <input disabled=""> is also valid, per html5. but it doesn't look nice. and not sure if it works in all browsers.
    // we reason that empty non-boolean attr values would be rare. so we choose <tag attr> over <tag attr="">


    // user need to subclass this class, override getElementType()
    // if this element can contain children, subclass HtmlParentElement instead.

    /**
     * Protected constructor for subclasses.
     */
    protected HtmlElement(){}

    /**
     * Get the metadata of this element. See {@link HtmlElementType}.
     */
    abstract public HtmlElementType getElementType();

    /**
     * Whether this is a "block" element.
     * <p>
     *     This method is equivalent to `getElementType().isBlock`.
     * </p>
     */
    @Override
    public boolean isBlock()
    {
        return getElementType().isBlock;
    }

    // attributes ------------------------------------------------------
    // usually there aren't many attributes.
    // we don't use a hash table. memory is our main concern.
    //   use brute force search; it's ok for small number of attributes

    // we allow setAttr(name, null), but we don't store null here.
    CharSequence[] attributes; // n1,v1,n2,v2,...  lazy, many elements have no attr
    int attributesSize;

    /**
     * Get the number of attributes.
     */
    public int getAttributeCount()
    {
        return attributesSize;
    }
    void checkIndex(int index)
    {
        if(index<0)
            throw new IndexOutOfBoundsException(""+index+"<0");
        if(index>=attributesSize)
            throw new IndexOutOfBoundsException(""+index+">="+attributesSize);
    }

    // faster iteration of attributes. probably not needed by user app.
    String getAttributeName(int index)
    {
        checkIndex(index);
        return (String)attributes[2*index];
    }
    CharSequence getAttributeValue(int index)
    {
        checkIndex(index);
        return attributes[2*index+1];
    }

    // return -1 if not found
    // this is mostly used by setAttribute() during tree building, usually the name isn't present
    int attrIndex(String name)
    {
        // safe if attributes==null, since attributesSize==0

        int h = name.hashCode();
        for(int i=0; i<attributesSize; i++)
        {
            String n = (String)attributes[2*i];
            if(h==n.hashCode() && name.equals(n)) // usually fail; usually fail at hash code comparison
                return i;
        }
        return -1; // most likely result
    }
    // we compare hash codes of 2 strings, to fail fast if they are not equal.
    // but why doesn't String.equal() do that inside its impl?
    // probably because it's not usually a fast path.
    // in the popular use of HashMap with String keys, hash code has been compared before equal() is called.
    // but in our case here, we expect 2 strings to be unequal, so it is a fast path

    /**
     * Get the value of the attribute; null if the attribute is not present.
     */
    public CharSequence getAttribute(String name)
    {
        int index = attrIndex(name);
        if(index==-1)
            return null;
        return attributes[2*index+1];
    }

    /**
     * Set the value of the attribute.
     * <p>
     *     If `value` is null, the attribute is removed.
     * </p>
     * <p>
     *     The value can contain any chars. It can be empty.
     * </p>
     * @return the previous value of the attribute, or null if the attribute didn't exist.
     */
    // no public removeAttribute() method.
    public CharSequence setAttribute(String name, CharSequence value)
    {
        validateAttributeName(name);

        // usually the name isn't present yet
        int index = attrIndex(name);
        if(index!=-1) // usually not the case. html building usually only adds new attributes.
        {
            CharSequence old = attributes[2*index+1];
            if(value==null)
                _removeAttribute(index);
            else
                attributes[2*index+1] = value;
            return old;
        }

        // new name
        if(value==null)
            return null;

        if(attributes==null)
            attributes = new CharSequence[2*4];  // usually not many attrs
        else if(attributes.length==2*attributesSize) // full
            attributes = Arrays.copyOf(attributes, 2*(attributesSize+4)); // we don't expect many more attrs

        attributes[2*attributesSize] = name;
        attributes[2*attributesSize+1] = value;
        attributesSize++;
        return null;
    }

    void _removeAttribute(int index)
    {
        int remain = attributesSize - index - 1;
        if(remain>0)
            System.arraycopy(attributes, 2*index+2, attributes, 2*index, 2*remain);
        attributes[2*attributesSize-2]=null;
        attributes[2*attributesSize-1]=null;
        attributesSize--;
    }

    static void validateAttributeName(String name)
    {
        if(name==null)
            throw new IllegalArgumentException("attribute name cannot be null");
        if(name.isEmpty())
            throw new IllegalArgumentException("attribute name cannot be empty");
        // we are not doing full validation on attr name which is pretty complicated.
        // usually user code passes in a string constant of a well-known attr name,
        // it's unlikely that it's invalid.
    }


    /**
     * Get a live Map view of the attributes.
     * <p>
     *     The returned Map supports read and write.
     * </p>
     */
    // we don't use this method, but users may want to.
    public Map<String,CharSequence> getAttributeMap()
    {
        return new HtmlAttributeMap(this);
    }


    /**
     * Render this element to `out`.
     * <p>
     *     An example rendering: <code>&lt;input name="q" disabled&gt;</code>
     * </p>
     * <p>
     *     If an attribute value is empty, only the name is rendered.
     *     For example.
     * </p>
     * <pre> setAttribute("disabled", "")    ==&gt;    &lt;input disabled&gt; </pre>
     * <p>
     *     Special chars in attribute values will be escaped,
     *     using {@link HtmlPiece#renderEscaped HtmlPiece.renderEscaped()}.
     *     For example
     * </p>
     * <pre> setAttribute("name", "a&amp;b")     ==&gt;    &lt;input name="a&amp;amp;b"&gt; </pre>
     *
     *
     */
    @Override
    public void render(int indent, Consumer<CharSequence> out)
    {
        HtmlElementType type = getElementType();

        if(attributesSize==0)
            out.accept(type.startTag);  // <tag>
        else
            writeStartTagTo(type.startTagX, out);       // <tag attr=value..>

        if(type.isVoid) // an html void element. must not have end tag. (nor self-closing like <br />)
            return;
        // actually, isVoid must be true here, since this is not HtmlParentElement.

        // subclass: insert children here
        // self-closing tag is illegal see comment in HtmlParentElement.render()

        // imm followed by end tag. <tag></tag>
        out.accept(type.endTag); // actually, this line is never reached.
    }

    void writeStartTagTo(String startTagX, Consumer<CharSequence> out)
    {
        // a lot of tiny fragments here, bad for the consumer (e.g. TextByteSource)
        // we also tried this: calculated the total length, allocate one StringBuilder, call sb.append(),
        //   then do out.accept(sb).  consumer sees only one sequence. (alt: use _CharSeqSaver)
        //   however, the overhead of doing that is too big, out-weighs the benefit the consumer enjoys.

        // this impl is messy, just to reduce fragments as much as possible.

        // at least 1 attr
        out.accept(startTagX); // "<tag "
        int i=0;
        for(; i<attributesSize-1; i++)
        {
            CharSequence name = attributes[2*i];
            CharSequence value = attributes[2*i+1];  // non-null

            out.accept(name);
            if(value.length()>0)
            {
                out.accept("=\"");
                HtmlPiece.renderEscaped(value, out);
                out.accept("\" ");
            }
            else
                out.accept(" ");
        }
        // last attr
        {
            CharSequence name = attributes[2*i];
            CharSequence value = attributes[2*i+1];  // non-null

            out.accept(name);
            if(value.length()>0)
            {
                out.accept("=\"");
                HtmlPiece.renderEscaped(value, out);
                out.accept("\">");
            }
            else
                out.accept(">");
        }
    }

}
