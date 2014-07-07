package bayou.html;

/**
 * Html4 builder.
 * <p>
 *     This interface contains html4 element types (e.g. {@link Html4.DIV}),
 *     and element builder methods (e.g. {@link Html4#_div()}).
 * </p>
 * <h4>Element Type</h4>
 * <p>
 *     Each element type contains methods to set element-specific attributes,
 *     for example {@link A#href A.href(value)}.
 * </p>
 * <p>
 *     For common attributes or non-standard attributes, see methods in {@link Html4.Element}.
 * </p>
 * <p>
 *     If the element is a parent element, it has methods to add children, see {@link Html4.ParentElement}.
 * </p>
 * <h4>Builder Method</h4>
 * <p>
 *     A builder method creates an element and adds it to the context parent,
 *     for example, {@link #_img()}.
 * </p>
 * <p>
 *     For each parent element, there are also two builder methods that take children,
 *     for example, {@link #_div(Object...)} and {@link #_div(Runnable)}.
 * </p>
 *
 * <h4>Standard Conformance</h4>
 * <p>
 *     The elements and attributes conform to
 *     <a href="http://www.w3.org/TR/REC-html40/sgml/dtd.html">HTML 4.01 Strict DTD</a>,
 *     with the following exceptions:
 * </p>
 * <ul>
 *     <li>
 *         we have the &lt;iframe&gt; element;
 *         it's in the loose DTD but not deprecated.
 *     </li>
 *     <li>
 *         we have the "target" attribute on &lt;a/area/base/form/link&gt; elements;
 *         it's in the loose DTD but not deprecated.
 *     </li>
 *     <li>
 *         attributes "id/class/style/title/lang/dir" are considered common attributes on all elements;
 *         even though in HTML 4.01 some elements do not have all of these attributes.
 *     </li>
 * </ul>
 * <p>
 *     See also {@link Html5}.
 * </p>
 */

@SuppressWarnings({"UnusedDeclaration"})
public interface Html4 extends HtmlBuilder
{
    /**
     * An instance of Html4.
     * <p>
     *     Usually members of Html4 are accessed through inheritance.
     *     Note that Html4 is an interface with no abstract methods.
     * </p>
     * <p>
     *     However, if inheritance is not suitable in some situations,
     *     application code can access Html4 methods through this instance, e.g.
     * </p>
     * <pre>
     *     import static bayou.html.Html4.*;
     *
     *         html4._div();
     * </pre>
     */
    public static final Html4 html4 = new Html4(){};

    /**
     * The DOCTYPE of HTML 4.01 Strict.
     */
    public static final String DOCTYPE =
            "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01//EN\" \"http://www.w3.org/TR/html4/strict.dtd\">";


    /**
     * Html4 element.
     * <p>
     *     This interface contains convenience method to set common attributes or non-standard attributes.
     * </p>
     * <p>
     *     Most methods return `this` for method chaining.
     * </p>
     */
    @SuppressWarnings("unchecked")
    public interface Element<This> extends HtmlPiece
    {
        // these two methods are actually implemented by HtmlElement
        // we need to declare them here, since default methods use them

        /**
         * Get the attribute value. See {@link HtmlElement#getAttribute HtmlElement.getAttribute(name)}.
         */
        abstract public CharSequence getAttribute(String name);
        /**
         * Set the attribute value. See {@link HtmlElement#setAttribute HtmlElement.setAttribute(name, value)}.
         */
        abstract public CharSequence setAttribute(String name, CharSequence value);

        /**
         * Get the attribute value. Equivalent to {@link #getAttribute getAttribute(name)}
         */
        default public CharSequence attr(String name)
        {
            return getAttribute(name);
        }

        // CAUTION: all methods returning This must be overridden in Html5

        /**
         * Set the attribute value. Has the safe effect of {@link #setAttribute setAttribute(name)}.
         */
        default public This attr(String name, CharSequence value)  // this one is used most often
        {
            setAttribute(name, value);
            return (This)this;
        }

        /**
         * Set a boolean attribute. Equivalent to `attr(name, value?"":null)`.
         */
        default public This attr(String name, boolean value)
        {
            setAttribute(name, value?"":null);
            return (This)this;
        }

        /**
         * Set an integer attribute. Equivalent to `attr(name, ""+value)`.
         */
        default public This attr(String name, int value)
        {
            setAttribute(name, Integer.toString(value));
            return (This)this;
        }
        // don't provide a general attr(String,Object)

        // e.g. a().on("click", "alert('boo')");

        /**
         * Set an event attribute. Equivalent to `attr("on"+event, script)`.
         * <p>For example,</p>
         * <pre>
         *     _a().on("click", "alert('boo')"); // &lt;a onclick="alert('boo')"&gt;
         * </pre>
         */
        default public This on(String event, CharSequence script)
        {
            setAttribute("on"+event, script);
            return (This)this;
        }

        // common attrs id/class/style/title/lang/dir (coreattrs and i18n in DTD)
        // in html4, a handful elements don't have all these attrs
        //   elements without class/style/title:
        //     base head html meta param script style* title
        //   elements without lang/dir:
        //     iframe base br param script
        //   * <style> does have "title" attr, but not sure what for
        // we add these attrs to all elements anyway.
        // note: html5 adds accesskey/tabindex as global attrs; but few html4 elements have them.

        /**
         * Add a new class to the "class" attribute.
         * <p>For example</p>
         * <pre>
         *     DIV div = _div();    // &lt;div&gt;
         *     div.class_add("c1"); // &lt;div class="c1"&gt;
         *     div.class_add("c2"); // &lt;div class="c1 c2"&gt;
         * </pre>
         * <p>
         *     If `clazz` is null or empty, this method has no effect.
         * </p>
         */
        default public This class_add(CharSequence clazz)
        {
            if(clazz==null || clazz.length()==0)
                return (This)this;
            CharSequence attrValue = getAttribute("class");
            if(attrValue==null)
                attrValue = clazz;
            else
                attrValue = ""+attrValue +' '+ clazz;
            return class_(attrValue);
        }

        // generated code //////////////////////////////////////////////////////////////////////////////

        /** Set attribute <code><b>class="</b><i>{value}</i><b>"</b></code>. */
        default public This class_(CharSequence value) { return attr("class", value); }

        /** Set attribute <code><b>dir="</b><i>{value}</i><b>"</b></code>. */
        default public This dir(CharSequence value) { return attr("dir", value); }

        /** Set attribute <code><b>id="</b><i>{value}</i><b>"</b></code>. */
        default public This id(CharSequence value) { return attr("id", value); }

        /** Set attribute <code><b>lang="</b><i>{value}</i><b>"</b></code>. */
        default public This lang(CharSequence value) { return attr("lang", value); }

        /** Set attribute <code><b>style="</b><i>{value}</i><b>"</b></code>. */
        default public This style(CharSequence value) { return attr("style", value); }

        /** Set attribute <code><b>title="</b><i>{value}</i><b>"</b></code>. */
        default public This title(CharSequence value) { return attr("title", value); }
    }

    /**
     * Html4 parent element.
     * <p>
     *     This interface contains methods to add children to this parent.
     * </p>
     */
    @SuppressWarnings("unchecked")
    public interface ParentElement<This> extends Element<This>, HtmlParent
    {
        // CAUTION: all methods returning This must be overridden in Html5

        // detach children from context parent, then append them to this element

        /**
         * Add children to this parent.
         * <p>
         *     The children are first detached from the context parent.
         *     See {@link HtmlBuilder.ContextParent#detachThenAddTo(Object[], HtmlParent)
         *         ContextParent.detachThenAddTo(children, parent)}.
         * </p>
         * @return `this`
         */
        default public This add(Object... children)
        {
            ContextParent.detachThenAddTo(children, this);
            return (This)this;
        }

        // run block with this element as the context parent

        /**
         * Run `code` with this element as the context parent.
         * <p>
         *     See {@link HtmlBuilder.ContextParent#with ContextParent.with(parent, code)}.
         * </p>
         * @return `this`
         */
        default public This add(Runnable code)
        {
            ContextParent.with(this, code);
            return (This)this;
        }
    }


    // generated code            //////////////////////////////////////////////////////////////////////////////

    // check: all attribute methods must be overriden in html5 (unless element does not exist in html5)


    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;a&gt;</code>. */
    public static class A extends HtmlParentElement implements Html4.ParentElement<A>
    {
        /** HtmlElementType for <code>&lt;a&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("a", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>accesskey="</b><i>{value}</i><b>"</b></code>. */
        public A accesskey(CharSequence value) { return attr("accesskey", value); }

        /** Set attribute <code><b>charset="</b><i>{value}</i><b>"</b></code>. */
        public A charset(CharSequence value) { return attr("charset", value); }

        /** Set attribute <code><b>coords="</b><i>{value}</i><b>"</b></code>. */
        public A coords(CharSequence value) { return attr("coords", value); }

        /** Set attribute <code><b>href="</b><i>{value}</i><b>"</b></code>. */
        public A href(CharSequence value) { return attr("href", value); }

        /** Set attribute <code><b>hreflang="</b><i>{value}</i><b>"</b></code>. */
        public A hreflang(CharSequence value) { return attr("hreflang", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>. */
        public A name(CharSequence value) { return attr("name", value); }

        /** Set attribute <code><b>rel="</b><i>{value}</i><b>"</b></code>. */
        public A rel(CharSequence value) { return attr("rel", value); }

        /** Set attribute <code><b>rev="</b><i>{value}</i><b>"</b></code>. */
        public A rev(CharSequence value) { return attr("rev", value); }

        /** Set attribute <code><b>shape="</b><i>{value}</i><b>"</b></code>. */
        public A shape(CharSequence value) { return attr("shape", value); }

        /** Set attribute <code><b>tabindex="</b><i>{value}</i><b>"</b></code>. */
        public A tabindex(int value) { return attr("tabindex", value); }

        /** Set attribute <code><b>target="</b><i>{value}</i><b>"</b></code>. */
        public A target(CharSequence value) { return attr("target", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>. */
        public A type(CharSequence value) { return attr("type", value); }

    }
    /** Build element <code>&lt;a&gt;</code>. */
    default public A _a(){ return ContextParent.add(new A()); }
    /** Build element <code>&lt;a&gt;</code> with children. */
    default public A _a(Object... children){ return ContextParent.add(new A(), children); }
    /** Build element <code>&lt;a&gt;</code>; with it as the context parent, run `code`. */
    default public A _a(Runnable code){ return ContextParent.add(new A(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;abbr&gt;</code>. */
    public static class ABBR extends HtmlParentElement implements Html4.ParentElement<ABBR>
    {
        /** HtmlElementType for <code>&lt;abbr&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("abbr", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;abbr&gt;</code>. */
    default public ABBR _abbr(){ return ContextParent.add(new ABBR()); }
    /** Build element <code>&lt;abbr&gt;</code> with children. */
    default public ABBR _abbr(Object... children){ return ContextParent.add(new ABBR(), children); }
    /** Build element <code>&lt;abbr&gt;</code>; with it as the context parent, run `code`. */
    default public ABBR _abbr(Runnable code){ return ContextParent.add(new ABBR(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;acronym&gt;</code>. */
    public static class ACRONYM extends HtmlParentElement implements Html4.ParentElement<ACRONYM>
    {
        /** HtmlElementType for <code>&lt;acronym&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("acronym", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;acronym&gt;</code>. */
    default public ACRONYM _acronym(){ return ContextParent.add(new ACRONYM()); }
    /** Build element <code>&lt;acronym&gt;</code> with children. */
    default public ACRONYM _acronym(Object... children){ return ContextParent.add(new ACRONYM(), children); }
    /** Build element <code>&lt;acronym&gt;</code>; with it as the context parent, run `code`. */
    default public ACRONYM _acronym(Runnable code){ return ContextParent.add(new ACRONYM(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;address&gt;</code>. */
    public static class ADDRESS extends HtmlParentElement implements Html4.ParentElement<ADDRESS>
    {
        /** HtmlElementType for <code>&lt;address&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("address", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;address&gt;</code>. */
    default public ADDRESS _address(){ return ContextParent.add(new ADDRESS()); }
    /** Build element <code>&lt;address&gt;</code> with children. */
    default public ADDRESS _address(Object... children){ return ContextParent.add(new ADDRESS(), children); }
    /** Build element <code>&lt;address&gt;</code>; with it as the context parent, run `code`. */
    default public ADDRESS _address(Runnable code){ return ContextParent.add(new ADDRESS(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;area&gt;</code>. */
    public static class AREA extends HtmlElement implements Html4.Element<AREA>
    {
        /** HtmlElementType for <code>&lt;area&gt;</code> (void=true, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("area", true, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>accesskey="</b><i>{value}</i><b>"</b></code>. */
        public AREA accesskey(CharSequence value) { return attr("accesskey", value); }

        /** Set attribute <code><b>alt="</b><i>{value}</i><b>"</b></code>. */
        public AREA alt(CharSequence value) { return attr("alt", value); }

        /** Set attribute <code><b>coords="</b><i>{value}</i><b>"</b></code>. */
        public AREA coords(CharSequence value) { return attr("coords", value); }

        /** Set attribute <code><b>href="</b><i>{value}</i><b>"</b></code>. */
        public AREA href(CharSequence value) { return attr("href", value); }

        /** Set boolean attribute <code><b>nohref</b></code>. */
        public AREA nohref(boolean value) { return attr("nohref", value); }

        /** Set attribute <code><b>shape="</b><i>{value}</i><b>"</b></code>. */
        public AREA shape(CharSequence value) { return attr("shape", value); }

        /** Set attribute <code><b>tabindex="</b><i>{value}</i><b>"</b></code>. */
        public AREA tabindex(int value) { return attr("tabindex", value); }

        /** Set attribute <code><b>target="</b><i>{value}</i><b>"</b></code>. */
        public AREA target(CharSequence value) { return attr("target", value); }

    }
    /** Build element <code>&lt;area&gt;</code>. */
    default public AREA _area(){ return ContextParent.add(new AREA()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;b&gt;</code>. */
    public static class B extends HtmlParentElement implements Html4.ParentElement<B>
    {
        /** HtmlElementType for <code>&lt;b&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("b", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;b&gt;</code>. */
    default public B _b(){ return ContextParent.add(new B()); }
    /** Build element <code>&lt;b&gt;</code> with children. */
    default public B _b(Object... children){ return ContextParent.add(new B(), children); }
    /** Build element <code>&lt;b&gt;</code>; with it as the context parent, run `code`. */
    default public B _b(Runnable code){ return ContextParent.add(new B(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;base&gt;</code>. */
    public static class BASE extends HtmlElement implements Html4.Element<BASE>
    {
        /** HtmlElementType for <code>&lt;base&gt;</code> (void=true, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("base", true, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>href="</b><i>{value}</i><b>"</b></code>. */
        public BASE href(CharSequence value) { return attr("href", value); }

        /** Set attribute <code><b>target="</b><i>{value}</i><b>"</b></code>. */
        public BASE target(CharSequence value) { return attr("target", value); }

    }
    /** Build element <code>&lt;base&gt;</code>. */
    default public BASE _base(){ return ContextParent.add(new BASE()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;bdo&gt;</code>. */
    public static class BDO extends HtmlParentElement implements Html4.ParentElement<BDO>
    {
        /** HtmlElementType for <code>&lt;bdo&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("bdo", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;bdo&gt;</code>. */
    default public BDO _bdo(){ return ContextParent.add(new BDO()); }
    /** Build element <code>&lt;bdo&gt;</code> with children. */
    default public BDO _bdo(Object... children){ return ContextParent.add(new BDO(), children); }
    /** Build element <code>&lt;bdo&gt;</code>; with it as the context parent, run `code`. */
    default public BDO _bdo(Runnable code){ return ContextParent.add(new BDO(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;big&gt;</code>. */
    public static class BIG extends HtmlParentElement implements Html4.ParentElement<BIG>
    {
        /** HtmlElementType for <code>&lt;big&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("big", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;big&gt;</code>. */
    default public BIG _big(){ return ContextParent.add(new BIG()); }
    /** Build element <code>&lt;big&gt;</code> with children. */
    default public BIG _big(Object... children){ return ContextParent.add(new BIG(), children); }
    /** Build element <code>&lt;big&gt;</code>; with it as the context parent, run `code`. */
    default public BIG _big(Runnable code){ return ContextParent.add(new BIG(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;blockquote&gt;</code>. */
    public static class BLOCKQUOTE extends HtmlParentElement implements Html4.ParentElement<BLOCKQUOTE>
    {
        /** HtmlElementType for <code>&lt;blockquote&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("blockquote", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>cite="</b><i>{value}</i><b>"</b></code>. */
        public BLOCKQUOTE cite(CharSequence value) { return attr("cite", value); }

    }
    /** Build element <code>&lt;blockquote&gt;</code>. */
    default public BLOCKQUOTE _blockquote(){ return ContextParent.add(new BLOCKQUOTE()); }
    /** Build element <code>&lt;blockquote&gt;</code> with children. */
    default public BLOCKQUOTE _blockquote(Object... children){ return ContextParent.add(new BLOCKQUOTE(), children); }
    /** Build element <code>&lt;blockquote&gt;</code>; with it as the context parent, run `code`. */
    default public BLOCKQUOTE _blockquote(Runnable code){ return ContextParent.add(new BLOCKQUOTE(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;body&gt;</code>. */
    public static class BODY extends HtmlParentElement implements Html4.ParentElement<BODY>
    {
        /** HtmlElementType for <code>&lt;body&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("body", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;body&gt;</code>. */
    default public BODY _body(){ return ContextParent.add(new BODY()); }
    /** Build element <code>&lt;body&gt;</code> with children. */
    default public BODY _body(Object... children){ return ContextParent.add(new BODY(), children); }
    /** Build element <code>&lt;body&gt;</code>; with it as the context parent, run `code`. */
    default public BODY _body(Runnable code){ return ContextParent.add(new BODY(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;br&gt;</code>. */
    public static class BR extends HtmlElement implements Html4.Element<BR>
    {
        /** HtmlElementType for <code>&lt;br&gt;</code> (void=true, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("br", true, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;br&gt;</code>. */
    default public BR _br(){ return ContextParent.add(new BR()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;button&gt;</code>. */
    public static class BUTTON extends HtmlParentElement implements Html4.ParentElement<BUTTON>
    {
        /** HtmlElementType for <code>&lt;button&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("button", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>accesskey="</b><i>{value}</i><b>"</b></code>. */
        public BUTTON accesskey(CharSequence value) { return attr("accesskey", value); }

        /** Set boolean attribute <code><b>disabled</b></code>. */
        public BUTTON disabled(boolean value) { return attr("disabled", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>. */
        public BUTTON name(CharSequence value) { return attr("name", value); }

        /** Set attribute <code><b>tabindex="</b><i>{value}</i><b>"</b></code>. */
        public BUTTON tabindex(int value) { return attr("tabindex", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>. */
        public BUTTON type(CharSequence value) { return attr("type", value); }

        /** Set attribute <code><b>value="</b><i>{value}</i><b>"</b></code>. */
        public BUTTON value(CharSequence value) { return attr("value", value); }

    }
    /** Build element <code>&lt;button&gt;</code>. */
    default public BUTTON _button(){ return ContextParent.add(new BUTTON()); }
    /** Build element <code>&lt;button&gt;</code> with children. */
    default public BUTTON _button(Object... children){ return ContextParent.add(new BUTTON(), children); }
    /** Build element <code>&lt;button&gt;</code>; with it as the context parent, run `code`. */
    default public BUTTON _button(Runnable code){ return ContextParent.add(new BUTTON(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;caption&gt;</code>. */
    public static class CAPTION extends HtmlParentElement implements Html4.ParentElement<CAPTION>
    {
        /** HtmlElementType for <code>&lt;caption&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("caption", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;caption&gt;</code>. */
    default public CAPTION _caption(){ return ContextParent.add(new CAPTION()); }
    /** Build element <code>&lt;caption&gt;</code> with children. */
    default public CAPTION _caption(Object... children){ return ContextParent.add(new CAPTION(), children); }
    /** Build element <code>&lt;caption&gt;</code>; with it as the context parent, run `code`. */
    default public CAPTION _caption(Runnable code){ return ContextParent.add(new CAPTION(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;cite&gt;</code>. */
    public static class CITE extends HtmlParentElement implements Html4.ParentElement<CITE>
    {
        /** HtmlElementType for <code>&lt;cite&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("cite", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;cite&gt;</code>. */
    default public CITE _cite(){ return ContextParent.add(new CITE()); }
    /** Build element <code>&lt;cite&gt;</code> with children. */
    default public CITE _cite(Object... children){ return ContextParent.add(new CITE(), children); }
    /** Build element <code>&lt;cite&gt;</code>; with it as the context parent, run `code`. */
    default public CITE _cite(Runnable code){ return ContextParent.add(new CITE(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;code&gt;</code>. */
    public static class CODE extends HtmlParentElement implements Html4.ParentElement<CODE>
    {
        /** HtmlElementType for <code>&lt;code&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("code", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;code&gt;</code>. */
    default public CODE _code(){ return ContextParent.add(new CODE()); }
    /** Build element <code>&lt;code&gt;</code> with children. */
    default public CODE _code(Object... children){ return ContextParent.add(new CODE(), children); }
    /** Build element <code>&lt;code&gt;</code>; with it as the context parent, run `code`. */
    default public CODE _code(Runnable code){ return ContextParent.add(new CODE(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;col&gt;</code>. */
    public static class COL extends HtmlElement implements Html4.Element<COL>
    {
        /** HtmlElementType for <code>&lt;col&gt;</code> (void=true, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("col", true, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>align="</b><i>{value}</i><b>"</b></code>. */
        public COL align(CharSequence value) { return attr("align", value); }

        /** Set attribute <code><b>char="</b><i>{value}</i><b>"</b></code>. */
        public COL char_(CharSequence value) { return attr("char", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>. */
        public COL charoff(int value) { return attr("charoff", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>. */
        public COL charoff(CharSequence value) { return attr("charoff", value); }

        /** Set attribute <code><b>span="</b><i>{value}</i><b>"</b></code>. */
        public COL span(int value) { return attr("span", value); }

        /** Set attribute <code><b>valign="</b><i>{value}</i><b>"</b></code>. */
        public COL valign(CharSequence value) { return attr("valign", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>. */
        public COL width(int value) { return attr("width", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>. */
        public COL width(CharSequence value) { return attr("width", value); }

    }
    /** Build element <code>&lt;col&gt;</code>. */
    default public COL _col(){ return ContextParent.add(new COL()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;colgroup&gt;</code>. */
    public static class COLGROUP extends HtmlParentElement implements Html4.ParentElement<COLGROUP>
    {
        /** HtmlElementType for <code>&lt;colgroup&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("colgroup", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>align="</b><i>{value}</i><b>"</b></code>. */
        public COLGROUP align(CharSequence value) { return attr("align", value); }

        /** Set attribute <code><b>char="</b><i>{value}</i><b>"</b></code>. */
        public COLGROUP char_(CharSequence value) { return attr("char", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>. */
        public COLGROUP charoff(int value) { return attr("charoff", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>. */
        public COLGROUP charoff(CharSequence value) { return attr("charoff", value); }

        /** Set attribute <code><b>span="</b><i>{value}</i><b>"</b></code>. */
        public COLGROUP span(int value) { return attr("span", value); }

        /** Set attribute <code><b>valign="</b><i>{value}</i><b>"</b></code>. */
        public COLGROUP valign(CharSequence value) { return attr("valign", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>. */
        public COLGROUP width(int value) { return attr("width", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>. */
        public COLGROUP width(CharSequence value) { return attr("width", value); }

    }
    /** Build element <code>&lt;colgroup&gt;</code>. */
    default public COLGROUP _colgroup(){ return ContextParent.add(new COLGROUP()); }
    /** Build element <code>&lt;colgroup&gt;</code> with children. */
    default public COLGROUP _colgroup(Object... children){ return ContextParent.add(new COLGROUP(), children); }
    /** Build element <code>&lt;colgroup&gt;</code>; with it as the context parent, run `code`. */
    default public COLGROUP _colgroup(Runnable code){ return ContextParent.add(new COLGROUP(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;dd&gt;</code>. */
    public static class DD extends HtmlParentElement implements Html4.ParentElement<DD>
    {
        /** HtmlElementType for <code>&lt;dd&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("dd", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;dd&gt;</code>. */
    default public DD _dd(){ return ContextParent.add(new DD()); }
    /** Build element <code>&lt;dd&gt;</code> with children. */
    default public DD _dd(Object... children){ return ContextParent.add(new DD(), children); }
    /** Build element <code>&lt;dd&gt;</code>; with it as the context parent, run `code`. */
    default public DD _dd(Runnable code){ return ContextParent.add(new DD(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;del&gt;</code>. */
    public static class DEL extends HtmlParentElement implements Html4.ParentElement<DEL>
    {
        /** HtmlElementType for <code>&lt;del&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("del", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>cite="</b><i>{value}</i><b>"</b></code>. */
        public DEL cite(CharSequence value) { return attr("cite", value); }

        /** Set attribute <code><b>datetime="</b><i>{value}</i><b>"</b></code>. */
        public DEL datetime(CharSequence value) { return attr("datetime", value); }

    }
    /** Build element <code>&lt;del&gt;</code>. */
    default public DEL _del(){ return ContextParent.add(new DEL()); }
    /** Build element <code>&lt;del&gt;</code> with children. */
    default public DEL _del(Object... children){ return ContextParent.add(new DEL(), children); }
    /** Build element <code>&lt;del&gt;</code>; with it as the context parent, run `code`. */
    default public DEL _del(Runnable code){ return ContextParent.add(new DEL(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;dfn&gt;</code>. */
    public static class DFN extends HtmlParentElement implements Html4.ParentElement<DFN>
    {
        /** HtmlElementType for <code>&lt;dfn&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("dfn", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;dfn&gt;</code>. */
    default public DFN _dfn(){ return ContextParent.add(new DFN()); }
    /** Build element <code>&lt;dfn&gt;</code> with children. */
    default public DFN _dfn(Object... children){ return ContextParent.add(new DFN(), children); }
    /** Build element <code>&lt;dfn&gt;</code>; with it as the context parent, run `code`. */
    default public DFN _dfn(Runnable code){ return ContextParent.add(new DFN(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;div&gt;</code>. */
    public static class DIV extends HtmlParentElement implements Html4.ParentElement<DIV>
    {
        /** HtmlElementType for <code>&lt;div&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("div", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;div&gt;</code>. */
    default public DIV _div(){ return ContextParent.add(new DIV()); }
    /** Build element <code>&lt;div&gt;</code> with children. */
    default public DIV _div(Object... children){ return ContextParent.add(new DIV(), children); }
    /** Build element <code>&lt;div&gt;</code>; with it as the context parent, run `code`. */
    default public DIV _div(Runnable code){ return ContextParent.add(new DIV(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;dl&gt;</code>. */
    public static class DL extends HtmlParentElement implements Html4.ParentElement<DL>
    {
        /** HtmlElementType for <code>&lt;dl&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("dl", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;dl&gt;</code>. */
    default public DL _dl(){ return ContextParent.add(new DL()); }
    /** Build element <code>&lt;dl&gt;</code> with children. */
    default public DL _dl(Object... children){ return ContextParent.add(new DL(), children); }
    /** Build element <code>&lt;dl&gt;</code>; with it as the context parent, run `code`. */
    default public DL _dl(Runnable code){ return ContextParent.add(new DL(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;dt&gt;</code>. */
    public static class DT extends HtmlParentElement implements Html4.ParentElement<DT>
    {
        /** HtmlElementType for <code>&lt;dt&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("dt", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;dt&gt;</code>. */
    default public DT _dt(){ return ContextParent.add(new DT()); }
    /** Build element <code>&lt;dt&gt;</code> with children. */
    default public DT _dt(Object... children){ return ContextParent.add(new DT(), children); }
    /** Build element <code>&lt;dt&gt;</code>; with it as the context parent, run `code`. */
    default public DT _dt(Runnable code){ return ContextParent.add(new DT(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;em&gt;</code>. */
    public static class EM extends HtmlParentElement implements Html4.ParentElement<EM>
    {
        /** HtmlElementType for <code>&lt;em&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("em", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;em&gt;</code>. */
    default public EM _em(){ return ContextParent.add(new EM()); }
    /** Build element <code>&lt;em&gt;</code> with children. */
    default public EM _em(Object... children){ return ContextParent.add(new EM(), children); }
    /** Build element <code>&lt;em&gt;</code>; with it as the context parent, run `code`. */
    default public EM _em(Runnable code){ return ContextParent.add(new EM(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;fieldset&gt;</code>. */
    public static class FIELDSET extends HtmlParentElement implements Html4.ParentElement<FIELDSET>
    {
        /** HtmlElementType for <code>&lt;fieldset&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("fieldset", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;fieldset&gt;</code>. */
    default public FIELDSET _fieldset(){ return ContextParent.add(new FIELDSET()); }
    /** Build element <code>&lt;fieldset&gt;</code> with children. */
    default public FIELDSET _fieldset(Object... children){ return ContextParent.add(new FIELDSET(), children); }
    /** Build element <code>&lt;fieldset&gt;</code>; with it as the context parent, run `code`. */
    default public FIELDSET _fieldset(Runnable code){ return ContextParent.add(new FIELDSET(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;form&gt;</code>. */
    public static class FORM extends HtmlParentElement implements Html4.ParentElement<FORM>
    {
        /** HtmlElementType for <code>&lt;form&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("form", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>accept="</b><i>{value}</i><b>"</b></code>. */
        public FORM accept(CharSequence value) { return attr("accept", value); }

        /** Set attribute <code><b>accept-charset="</b><i>{value}</i><b>"</b></code>. */
        public FORM accept_charset(CharSequence value) { return attr("accept-charset", value); }

        /** Set attribute <code><b>action="</b><i>{value}</i><b>"</b></code>. */
        public FORM action(CharSequence value) { return attr("action", value); }

        /** Set attribute <code><b>enctype="</b><i>{value}</i><b>"</b></code>. */
        public FORM enctype(CharSequence value) { return attr("enctype", value); }

        /** Set attribute <code><b>method="</b><i>{value}</i><b>"</b></code>. */
        public FORM method(CharSequence value) { return attr("method", value); }

        /** Set attribute <code><b>target="</b><i>{value}</i><b>"</b></code>. */
        public FORM target(CharSequence value) { return attr("target", value); }

    }
    /** Build element <code>&lt;form&gt;</code>. */
    default public FORM _form(){ return ContextParent.add(new FORM()); }
    /** Build element <code>&lt;form&gt;</code> with children. */
    default public FORM _form(Object... children){ return ContextParent.add(new FORM(), children); }
    /** Build element <code>&lt;form&gt;</code>; with it as the context parent, run `code`. */
    default public FORM _form(Runnable code){ return ContextParent.add(new FORM(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;h1&gt;</code>. */
    public static class H1 extends HtmlParentElement implements Html4.ParentElement<H1>
    {
        /** HtmlElementType for <code>&lt;h1&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("h1", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;h1&gt;</code>. */
    default public H1 _h1(){ return ContextParent.add(new H1()); }
    /** Build element <code>&lt;h1&gt;</code> with children. */
    default public H1 _h1(Object... children){ return ContextParent.add(new H1(), children); }
    /** Build element <code>&lt;h1&gt;</code>; with it as the context parent, run `code`. */
    default public H1 _h1(Runnable code){ return ContextParent.add(new H1(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;h2&gt;</code>. */
    public static class H2 extends HtmlParentElement implements Html4.ParentElement<H2>
    {
        /** HtmlElementType for <code>&lt;h2&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("h2", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;h2&gt;</code>. */
    default public H2 _h2(){ return ContextParent.add(new H2()); }
    /** Build element <code>&lt;h2&gt;</code> with children. */
    default public H2 _h2(Object... children){ return ContextParent.add(new H2(), children); }
    /** Build element <code>&lt;h2&gt;</code>; with it as the context parent, run `code`. */
    default public H2 _h2(Runnable code){ return ContextParent.add(new H2(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;h3&gt;</code>. */
    public static class H3 extends HtmlParentElement implements Html4.ParentElement<H3>
    {
        /** HtmlElementType for <code>&lt;h3&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("h3", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;h3&gt;</code>. */
    default public H3 _h3(){ return ContextParent.add(new H3()); }
    /** Build element <code>&lt;h3&gt;</code> with children. */
    default public H3 _h3(Object... children){ return ContextParent.add(new H3(), children); }
    /** Build element <code>&lt;h3&gt;</code>; with it as the context parent, run `code`. */
    default public H3 _h3(Runnable code){ return ContextParent.add(new H3(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;h4&gt;</code>. */
    public static class H4 extends HtmlParentElement implements Html4.ParentElement<H4>
    {
        /** HtmlElementType for <code>&lt;h4&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("h4", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;h4&gt;</code>. */
    default public H4 _h4(){ return ContextParent.add(new H4()); }
    /** Build element <code>&lt;h4&gt;</code> with children. */
    default public H4 _h4(Object... children){ return ContextParent.add(new H4(), children); }
    /** Build element <code>&lt;h4&gt;</code>; with it as the context parent, run `code`. */
    default public H4 _h4(Runnable code){ return ContextParent.add(new H4(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;h5&gt;</code>. */
    public static class H5 extends HtmlParentElement implements Html4.ParentElement<H5>
    {
        /** HtmlElementType for <code>&lt;h5&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("h5", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;h5&gt;</code>. */
    default public H5 _h5(){ return ContextParent.add(new H5()); }
    /** Build element <code>&lt;h5&gt;</code> with children. */
    default public H5 _h5(Object... children){ return ContextParent.add(new H5(), children); }
    /** Build element <code>&lt;h5&gt;</code>; with it as the context parent, run `code`. */
    default public H5 _h5(Runnable code){ return ContextParent.add(new H5(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;h6&gt;</code>. */
    public static class H6 extends HtmlParentElement implements Html4.ParentElement<H6>
    {
        /** HtmlElementType for <code>&lt;h6&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("h6", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;h6&gt;</code>. */
    default public H6 _h6(){ return ContextParent.add(new H6()); }
    /** Build element <code>&lt;h6&gt;</code> with children. */
    default public H6 _h6(Object... children){ return ContextParent.add(new H6(), children); }
    /** Build element <code>&lt;h6&gt;</code>; with it as the context parent, run `code`. */
    default public H6 _h6(Runnable code){ return ContextParent.add(new H6(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;head&gt;</code>. */
    public static class HEAD extends HtmlParentElement implements Html4.ParentElement<HEAD>
    {
        /** HtmlElementType for <code>&lt;head&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("head", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>profile="</b><i>{value}</i><b>"</b></code>. */
        public HEAD profile(CharSequence value) { return attr("profile", value); }

    }
    /** Build element <code>&lt;head&gt;</code>. */
    default public HEAD _head(){ return ContextParent.add(new HEAD()); }
    /** Build element <code>&lt;head&gt;</code> with children. */
    default public HEAD _head(Object... children){ return ContextParent.add(new HEAD(), children); }
    /** Build element <code>&lt;head&gt;</code>; with it as the context parent, run `code`. */
    default public HEAD _head(Runnable code){ return ContextParent.add(new HEAD(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;hr&gt;</code>. */
    public static class HR extends HtmlElement implements Html4.Element<HR>
    {
        /** HtmlElementType for <code>&lt;hr&gt;</code> (void=true, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("hr", true, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;hr&gt;</code>. */
    default public HR _hr(){ return ContextParent.add(new HR()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;html&gt;</code>. */
    public static class HTML extends HtmlParentElement implements Html4.ParentElement<HTML>
    {
        /** HtmlElementType for <code>&lt;html&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("html", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;html&gt;</code>. */
    default public HTML _html(){ return ContextParent.add(new HTML()); }
    /** Build element <code>&lt;html&gt;</code> with children. */
    default public HTML _html(Object... children){ return ContextParent.add(new HTML(), children); }
    /** Build element <code>&lt;html&gt;</code>; with it as the context parent, run `code`. */
    default public HTML _html(Runnable code){ return ContextParent.add(new HTML(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;i&gt;</code>. */
    public static class I extends HtmlParentElement implements Html4.ParentElement<I>
    {
        /** HtmlElementType for <code>&lt;i&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("i", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;i&gt;</code>. */
    default public I _i(){ return ContextParent.add(new I()); }
    /** Build element <code>&lt;i&gt;</code> with children. */
    default public I _i(Object... children){ return ContextParent.add(new I(), children); }
    /** Build element <code>&lt;i&gt;</code>; with it as the context parent, run `code`. */
    default public I _i(Runnable code){ return ContextParent.add(new I(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;iframe&gt;</code>. */
    public static class IFRAME extends HtmlParentElement implements Html4.ParentElement<IFRAME>
    {
        /** HtmlElementType for <code>&lt;iframe&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("iframe", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>frameborder="</b><i>{value}</i><b>"</b></code>. */
        public IFRAME frameborder(CharSequence value) { return attr("frameborder", value); }

        /** Set attribute <code><b>height="</b><i>{value}</i><b>"</b></code>. */
        public IFRAME height(int value) { return attr("height", value); }

        /** Set attribute <code><b>height="</b><i>{value}</i><b>"</b></code>. */
        public IFRAME height(CharSequence value) { return attr("height", value); }

        /** Set attribute <code><b>longdesc="</b><i>{value}</i><b>"</b></code>. */
        public IFRAME longdesc(CharSequence value) { return attr("longdesc", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>. */
        public IFRAME name(CharSequence value) { return attr("name", value); }

        /** Set attribute <code><b>scrolling="</b><i>{value}</i><b>"</b></code>. */
        public IFRAME scrolling(CharSequence value) { return attr("scrolling", value); }

        /** Set attribute <code><b>src="</b><i>{value}</i><b>"</b></code>. */
        public IFRAME src(CharSequence value) { return attr("src", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>. */
        public IFRAME width(int value) { return attr("width", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>. */
        public IFRAME width(CharSequence value) { return attr("width", value); }

    }
    /** Build element <code>&lt;iframe&gt;</code>. */
    default public IFRAME _iframe(){ return ContextParent.add(new IFRAME()); }
    /** Build element <code>&lt;iframe&gt;</code> with children. */
    default public IFRAME _iframe(Object... children){ return ContextParent.add(new IFRAME(), children); }
    /** Build element <code>&lt;iframe&gt;</code>; with it as the context parent, run `code`. */
    default public IFRAME _iframe(Runnable code){ return ContextParent.add(new IFRAME(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;img&gt;</code>. */
    public static class IMG extends HtmlElement implements Html4.Element<IMG>
    {
        /** HtmlElementType for <code>&lt;img&gt;</code> (void=true, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("img", true, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>alt="</b><i>{value}</i><b>"</b></code>. */
        public IMG alt(CharSequence value) { return attr("alt", value); }

        /** Set attribute <code><b>height="</b><i>{value}</i><b>"</b></code>. */
        public IMG height(int value) { return attr("height", value); }

        /** Set attribute <code><b>height="</b><i>{value}</i><b>"</b></code>. */
        public IMG height(CharSequence value) { return attr("height", value); }

        /** Set boolean attribute <code><b>ismap</b></code>. */
        public IMG ismap(boolean value) { return attr("ismap", value); }

        /** Set attribute <code><b>longdesc="</b><i>{value}</i><b>"</b></code>. */
        public IMG longdesc(CharSequence value) { return attr("longdesc", value); }

        /** Set attribute <code><b>src="</b><i>{value}</i><b>"</b></code>. */
        public IMG src(CharSequence value) { return attr("src", value); }

        /** Set attribute <code><b>usemap="</b><i>{value}</i><b>"</b></code>. */
        public IMG usemap(CharSequence value) { return attr("usemap", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>. */
        public IMG width(int value) { return attr("width", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>. */
        public IMG width(CharSequence value) { return attr("width", value); }

    }
    /** Build element <code>&lt;img&gt;</code>. */
    default public IMG _img(){ return ContextParent.add(new IMG()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;input&gt;</code>. */
    public static class INPUT extends HtmlElement implements Html4.Element<INPUT>
    {
        /** HtmlElementType for <code>&lt;input&gt;</code> (void=true, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("input", true, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>accept="</b><i>{value}</i><b>"</b></code>. */
        public INPUT accept(CharSequence value) { return attr("accept", value); }

        /** Set attribute <code><b>accesskey="</b><i>{value}</i><b>"</b></code>. */
        public INPUT accesskey(CharSequence value) { return attr("accesskey", value); }

        /** Set attribute <code><b>alt="</b><i>{value}</i><b>"</b></code>. */
        public INPUT alt(CharSequence value) { return attr("alt", value); }

        /** Set boolean attribute <code><b>checked</b></code>. */
        public INPUT checked(boolean value) { return attr("checked", value); }

        /** Set boolean attribute <code><b>disabled</b></code>. */
        public INPUT disabled(boolean value) { return attr("disabled", value); }

        /** Set attribute <code><b>maxlength="</b><i>{value}</i><b>"</b></code>. */
        public INPUT maxlength(int value) { return attr("maxlength", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>. */
        public INPUT name(CharSequence value) { return attr("name", value); }

        /** Set boolean attribute <code><b>readonly</b></code>. */
        public INPUT readonly(boolean value) { return attr("readonly", value); }

        /** Set attribute <code><b>size="</b><i>{value}</i><b>"</b></code>. */
        public INPUT size(int value) { return attr("size", value); }

        /** Set attribute <code><b>src="</b><i>{value}</i><b>"</b></code>. */
        public INPUT src(CharSequence value) { return attr("src", value); }

        /** Set attribute <code><b>tabindex="</b><i>{value}</i><b>"</b></code>. */
        public INPUT tabindex(int value) { return attr("tabindex", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>. */
        public INPUT type(CharSequence value) { return attr("type", value); }

        /** Set attribute <code><b>usemap="</b><i>{value}</i><b>"</b></code>. */
        public INPUT usemap(CharSequence value) { return attr("usemap", value); }

        /** Set attribute <code><b>value="</b><i>{value}</i><b>"</b></code>. */
        public INPUT value(CharSequence value) { return attr("value", value); }

    }
    /** Build element <code>&lt;input&gt;</code>. */
    default public INPUT _input(){ return ContextParent.add(new INPUT()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;ins&gt;</code>. */
    public static class INS extends HtmlParentElement implements Html4.ParentElement<INS>
    {
        /** HtmlElementType for <code>&lt;ins&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("ins", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>cite="</b><i>{value}</i><b>"</b></code>. */
        public INS cite(CharSequence value) { return attr("cite", value); }

        /** Set attribute <code><b>datetime="</b><i>{value}</i><b>"</b></code>. */
        public INS datetime(CharSequence value) { return attr("datetime", value); }

    }
    /** Build element <code>&lt;ins&gt;</code>. */
    default public INS _ins(){ return ContextParent.add(new INS()); }
    /** Build element <code>&lt;ins&gt;</code> with children. */
    default public INS _ins(Object... children){ return ContextParent.add(new INS(), children); }
    /** Build element <code>&lt;ins&gt;</code>; with it as the context parent, run `code`. */
    default public INS _ins(Runnable code){ return ContextParent.add(new INS(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;kbd&gt;</code>. */
    public static class KBD extends HtmlParentElement implements Html4.ParentElement<KBD>
    {
        /** HtmlElementType for <code>&lt;kbd&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("kbd", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;kbd&gt;</code>. */
    default public KBD _kbd(){ return ContextParent.add(new KBD()); }
    /** Build element <code>&lt;kbd&gt;</code> with children. */
    default public KBD _kbd(Object... children){ return ContextParent.add(new KBD(), children); }
    /** Build element <code>&lt;kbd&gt;</code>; with it as the context parent, run `code`. */
    default public KBD _kbd(Runnable code){ return ContextParent.add(new KBD(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;label&gt;</code>. */
    public static class LABEL extends HtmlParentElement implements Html4.ParentElement<LABEL>
    {
        /** HtmlElementType for <code>&lt;label&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("label", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>accesskey="</b><i>{value}</i><b>"</b></code>. */
        public LABEL accesskey(CharSequence value) { return attr("accesskey", value); }

        /** Set attribute <code><b>for="</b><i>{value}</i><b>"</b></code>. */
        public LABEL for_(CharSequence value) { return attr("for", value); }

    }
    /** Build element <code>&lt;label&gt;</code>. */
    default public LABEL _label(){ return ContextParent.add(new LABEL()); }
    /** Build element <code>&lt;label&gt;</code> with children. */
    default public LABEL _label(Object... children){ return ContextParent.add(new LABEL(), children); }
    /** Build element <code>&lt;label&gt;</code>; with it as the context parent, run `code`. */
    default public LABEL _label(Runnable code){ return ContextParent.add(new LABEL(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;legend&gt;</code>. */
    public static class LEGEND extends HtmlParentElement implements Html4.ParentElement<LEGEND>
    {
        /** HtmlElementType for <code>&lt;legend&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("legend", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>accesskey="</b><i>{value}</i><b>"</b></code>. */
        public LEGEND accesskey(CharSequence value) { return attr("accesskey", value); }

    }
    /** Build element <code>&lt;legend&gt;</code>. */
    default public LEGEND _legend(){ return ContextParent.add(new LEGEND()); }
    /** Build element <code>&lt;legend&gt;</code> with children. */
    default public LEGEND _legend(Object... children){ return ContextParent.add(new LEGEND(), children); }
    /** Build element <code>&lt;legend&gt;</code>; with it as the context parent, run `code`. */
    default public LEGEND _legend(Runnable code){ return ContextParent.add(new LEGEND(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;li&gt;</code>. */
    public static class LI extends HtmlParentElement implements Html4.ParentElement<LI>
    {
        /** HtmlElementType for <code>&lt;li&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("li", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;li&gt;</code>. */
    default public LI _li(){ return ContextParent.add(new LI()); }
    /** Build element <code>&lt;li&gt;</code> with children. */
    default public LI _li(Object... children){ return ContextParent.add(new LI(), children); }
    /** Build element <code>&lt;li&gt;</code>; with it as the context parent, run `code`. */
    default public LI _li(Runnable code){ return ContextParent.add(new LI(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;link&gt;</code>. */
    public static class LINK extends HtmlElement implements Html4.Element<LINK>
    {
        /** HtmlElementType for <code>&lt;link&gt;</code> (void=true, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("link", true, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>charset="</b><i>{value}</i><b>"</b></code>. */
        public LINK charset(CharSequence value) { return attr("charset", value); }

        /** Set attribute <code><b>href="</b><i>{value}</i><b>"</b></code>. */
        public LINK href(CharSequence value) { return attr("href", value); }

        /** Set attribute <code><b>hreflang="</b><i>{value}</i><b>"</b></code>. */
        public LINK hreflang(CharSequence value) { return attr("hreflang", value); }

        /** Set attribute <code><b>media="</b><i>{value}</i><b>"</b></code>. */
        public LINK media(CharSequence value) { return attr("media", value); }

        /** Set attribute <code><b>rel="</b><i>{value}</i><b>"</b></code>. */
        public LINK rel(CharSequence value) { return attr("rel", value); }

        /** Set attribute <code><b>rev="</b><i>{value}</i><b>"</b></code>. */
        public LINK rev(CharSequence value) { return attr("rev", value); }

        /** Set attribute <code><b>target="</b><i>{value}</i><b>"</b></code>. */
        public LINK target(CharSequence value) { return attr("target", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>. */
        public LINK type(CharSequence value) { return attr("type", value); }

    }
    /** Build element <code>&lt;link&gt;</code>. */
    default public LINK _link(){ return ContextParent.add(new LINK()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;map&gt;</code>. */
    public static class MAP extends HtmlParentElement implements Html4.ParentElement<MAP>
    {
        /** HtmlElementType for <code>&lt;map&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("map", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>. */
        public MAP name(CharSequence value) { return attr("name", value); }

    }
    /** Build element <code>&lt;map&gt;</code>. */
    default public MAP _map(){ return ContextParent.add(new MAP()); }
    /** Build element <code>&lt;map&gt;</code> with children. */
    default public MAP _map(Object... children){ return ContextParent.add(new MAP(), children); }
    /** Build element <code>&lt;map&gt;</code>; with it as the context parent, run `code`. */
    default public MAP _map(Runnable code){ return ContextParent.add(new MAP(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;meta&gt;</code>. */
    public static class META extends HtmlElement implements Html4.Element<META>
    {
        /** HtmlElementType for <code>&lt;meta&gt;</code> (void=true, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("meta", true, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>content="</b><i>{value}</i><b>"</b></code>. */
        public META content(CharSequence value) { return attr("content", value); }

        /** Set attribute <code><b>http-equiv="</b><i>{value}</i><b>"</b></code>. */
        public META http_equiv(CharSequence value) { return attr("http-equiv", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>. */
        public META name(CharSequence value) { return attr("name", value); }

        /** Set attribute <code><b>scheme="</b><i>{value}</i><b>"</b></code>. */
        public META scheme(CharSequence value) { return attr("scheme", value); }

    }
    /** Build element <code>&lt;meta&gt;</code>. */
    default public META _meta(){ return ContextParent.add(new META()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;noscript&gt;</code>. */
    public static class NOSCRIPT extends HtmlParentElement implements Html4.ParentElement<NOSCRIPT>
    {
        /** HtmlElementType for <code>&lt;noscript&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("noscript", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;noscript&gt;</code>. */
    default public NOSCRIPT _noscript(){ return ContextParent.add(new NOSCRIPT()); }
    /** Build element <code>&lt;noscript&gt;</code> with children. */
    default public NOSCRIPT _noscript(Object... children){ return ContextParent.add(new NOSCRIPT(), children); }
    /** Build element <code>&lt;noscript&gt;</code>; with it as the context parent, run `code`. */
    default public NOSCRIPT _noscript(Runnable code){ return ContextParent.add(new NOSCRIPT(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;object&gt;</code>. */
    public static class OBJECT extends HtmlParentElement implements Html4.ParentElement<OBJECT>
    {
        /** HtmlElementType for <code>&lt;object&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("object", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>archive="</b><i>{value}</i><b>"</b></code>. */
        public OBJECT archive(CharSequence value) { return attr("archive", value); }

        /** Set attribute <code><b>classid="</b><i>{value}</i><b>"</b></code>. */
        public OBJECT classid(CharSequence value) { return attr("classid", value); }

        /** Set attribute <code><b>codebase="</b><i>{value}</i><b>"</b></code>. */
        public OBJECT codebase(CharSequence value) { return attr("codebase", value); }

        /** Set attribute <code><b>codetype="</b><i>{value}</i><b>"</b></code>. */
        public OBJECT codetype(CharSequence value) { return attr("codetype", value); }

        /** Set attribute <code><b>data="</b><i>{value}</i><b>"</b></code>. */
        public OBJECT data(CharSequence value) { return attr("data", value); }

        /** Set boolean attribute <code><b>declare</b></code>. */
        public OBJECT declare(boolean value) { return attr("declare", value); }

        /** Set attribute <code><b>height="</b><i>{value}</i><b>"</b></code>. */
        public OBJECT height(int value) { return attr("height", value); }

        /** Set attribute <code><b>height="</b><i>{value}</i><b>"</b></code>. */
        public OBJECT height(CharSequence value) { return attr("height", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>. */
        public OBJECT name(CharSequence value) { return attr("name", value); }

        /** Set attribute <code><b>standby="</b><i>{value}</i><b>"</b></code>. */
        public OBJECT standby(CharSequence value) { return attr("standby", value); }

        /** Set attribute <code><b>tabindex="</b><i>{value}</i><b>"</b></code>. */
        public OBJECT tabindex(int value) { return attr("tabindex", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>. */
        public OBJECT type(CharSequence value) { return attr("type", value); }

        /** Set attribute <code><b>usemap="</b><i>{value}</i><b>"</b></code>. */
        public OBJECT usemap(CharSequence value) { return attr("usemap", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>. */
        public OBJECT width(int value) { return attr("width", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>. */
        public OBJECT width(CharSequence value) { return attr("width", value); }

    }
    /** Build element <code>&lt;object&gt;</code>. */
    default public OBJECT _object(){ return ContextParent.add(new OBJECT()); }
    /** Build element <code>&lt;object&gt;</code> with children. */
    default public OBJECT _object(Object... children){ return ContextParent.add(new OBJECT(), children); }
    /** Build element <code>&lt;object&gt;</code>; with it as the context parent, run `code`. */
    default public OBJECT _object(Runnable code){ return ContextParent.add(new OBJECT(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;ol&gt;</code>. */
    public static class OL extends HtmlParentElement implements Html4.ParentElement<OL>
    {
        /** HtmlElementType for <code>&lt;ol&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("ol", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;ol&gt;</code>. */
    default public OL _ol(){ return ContextParent.add(new OL()); }
    /** Build element <code>&lt;ol&gt;</code> with children. */
    default public OL _ol(Object... children){ return ContextParent.add(new OL(), children); }
    /** Build element <code>&lt;ol&gt;</code>; with it as the context parent, run `code`. */
    default public OL _ol(Runnable code){ return ContextParent.add(new OL(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;optgroup&gt;</code>. */
    public static class OPTGROUP extends HtmlParentElement implements Html4.ParentElement<OPTGROUP>
    {
        /** HtmlElementType for <code>&lt;optgroup&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("optgroup", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set boolean attribute <code><b>disabled</b></code>. */
        public OPTGROUP disabled(boolean value) { return attr("disabled", value); }

        /** Set attribute <code><b>label="</b><i>{value}</i><b>"</b></code>. */
        public OPTGROUP label(CharSequence value) { return attr("label", value); }

    }
    /** Build element <code>&lt;optgroup&gt;</code>. */
    default public OPTGROUP _optgroup(){ return ContextParent.add(new OPTGROUP()); }
    /** Build element <code>&lt;optgroup&gt;</code> with children. */
    default public OPTGROUP _optgroup(Object... children){ return ContextParent.add(new OPTGROUP(), children); }
    /** Build element <code>&lt;optgroup&gt;</code>; with it as the context parent, run `code`. */
    default public OPTGROUP _optgroup(Runnable code){ return ContextParent.add(new OPTGROUP(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;option&gt;</code>. */
    public static class OPTION extends HtmlParentElement implements Html4.ParentElement<OPTION>
    {
        /** HtmlElementType for <code>&lt;option&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("option", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set boolean attribute <code><b>disabled</b></code>. */
        public OPTION disabled(boolean value) { return attr("disabled", value); }

        /** Set attribute <code><b>label="</b><i>{value}</i><b>"</b></code>. */
        public OPTION label(CharSequence value) { return attr("label", value); }

        /** Set boolean attribute <code><b>selected</b></code>. */
        public OPTION selected(boolean value) { return attr("selected", value); }

        /** Set attribute <code><b>value="</b><i>{value}</i><b>"</b></code>. */
        public OPTION value(CharSequence value) { return attr("value", value); }

    }
    /** Build element <code>&lt;option&gt;</code>. */
    default public OPTION _option(){ return ContextParent.add(new OPTION()); }
    /** Build element <code>&lt;option&gt;</code> with children. */
    default public OPTION _option(Object... children){ return ContextParent.add(new OPTION(), children); }
    /** Build element <code>&lt;option&gt;</code>; with it as the context parent, run `code`. */
    default public OPTION _option(Runnable code){ return ContextParent.add(new OPTION(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;p&gt;</code>. */
    public static class P extends HtmlParentElement implements Html4.ParentElement<P>
    {
        /** HtmlElementType for <code>&lt;p&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("p", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;p&gt;</code>. */
    default public P _p(){ return ContextParent.add(new P()); }
    /** Build element <code>&lt;p&gt;</code> with children. */
    default public P _p(Object... children){ return ContextParent.add(new P(), children); }
    /** Build element <code>&lt;p&gt;</code>; with it as the context parent, run `code`. */
    default public P _p(Runnable code){ return ContextParent.add(new P(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;param&gt;</code>. */
    public static class PARAM extends HtmlElement implements Html4.Element<PARAM>
    {
        /** HtmlElementType for <code>&lt;param&gt;</code> (void=true, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("param", true, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>. */
        public PARAM name(CharSequence value) { return attr("name", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>. */
        public PARAM type(CharSequence value) { return attr("type", value); }

        /** Set attribute <code><b>value="</b><i>{value}</i><b>"</b></code>. */
        public PARAM value(CharSequence value) { return attr("value", value); }

        /** Set attribute <code><b>valuetype="</b><i>{value}</i><b>"</b></code>. */
        public PARAM valuetype(CharSequence value) { return attr("valuetype", value); }

    }
    /** Build element <code>&lt;param&gt;</code>. */
    default public PARAM _param(){ return ContextParent.add(new PARAM()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;pre&gt;</code>. */
    public static class PRE extends HtmlParentElement implements Html4.ParentElement<PRE>
    {
        /** HtmlElementType for <code>&lt;pre&gt;</code> (void=false, block=true, pre=true). */
        public static final HtmlElementType TYPE = new HtmlElementType("pre", false, true, true);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;pre&gt;</code>. */
    default public PRE _pre(){ return ContextParent.add(new PRE()); }
    /** Build element <code>&lt;pre&gt;</code> with children. */
    default public PRE _pre(Object... children){ return ContextParent.add(new PRE(), children); }
    /** Build element <code>&lt;pre&gt;</code>; with it as the context parent, run `code`. */
    default public PRE _pre(Runnable code){ return ContextParent.add(new PRE(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;q&gt;</code>. */
    public static class Q extends HtmlParentElement implements Html4.ParentElement<Q>
    {
        /** HtmlElementType for <code>&lt;q&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("q", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>cite="</b><i>{value}</i><b>"</b></code>. */
        public Q cite(CharSequence value) { return attr("cite", value); }

    }
    /** Build element <code>&lt;q&gt;</code>. */
    default public Q _q(){ return ContextParent.add(new Q()); }
    /** Build element <code>&lt;q&gt;</code> with children. */
    default public Q _q(Object... children){ return ContextParent.add(new Q(), children); }
    /** Build element <code>&lt;q&gt;</code>; with it as the context parent, run `code`. */
    default public Q _q(Runnable code){ return ContextParent.add(new Q(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;samp&gt;</code>. */
    public static class SAMP extends HtmlParentElement implements Html4.ParentElement<SAMP>
    {
        /** HtmlElementType for <code>&lt;samp&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("samp", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;samp&gt;</code>. */
    default public SAMP _samp(){ return ContextParent.add(new SAMP()); }
    /** Build element <code>&lt;samp&gt;</code> with children. */
    default public SAMP _samp(Object... children){ return ContextParent.add(new SAMP(), children); }
    /** Build element <code>&lt;samp&gt;</code>; with it as the context parent, run `code`. */
    default public SAMP _samp(Runnable code){ return ContextParent.add(new SAMP(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;script&gt;</code>. */
    public static class SCRIPT extends HtmlParentElement implements Html4.ParentElement<SCRIPT>
    {
        /** HtmlElementType for <code>&lt;script&gt;</code> (void=false, block=true, pre=true). */
        public static final HtmlElementType TYPE = new HtmlElementType("script", false, true, true);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>charset="</b><i>{value}</i><b>"</b></code>. */
        public SCRIPT charset(CharSequence value) { return attr("charset", value); }

        /** Set boolean attribute <code><b>defer</b></code>. */
        public SCRIPT defer(boolean value) { return attr("defer", value); }

        /** Set attribute <code><b>src="</b><i>{value}</i><b>"</b></code>. */
        public SCRIPT src(CharSequence value) { return attr("src", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>. */
        public SCRIPT type(CharSequence value) { return attr("type", value); }

    }
    /** Build element <code>&lt;script&gt;</code>. */
    default public SCRIPT _script(){ return ContextParent.add(new SCRIPT()); }
    /** Build element <code>&lt;script&gt;</code> with children. */
    default public SCRIPT _script(Object... children){ return ContextParent.add(new SCRIPT(), children); }
    /** Build element <code>&lt;script&gt;</code>; with it as the context parent, run `code`. */
    default public SCRIPT _script(Runnable code){ return ContextParent.add(new SCRIPT(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;select&gt;</code>. */
    public static class SELECT extends HtmlParentElement implements Html4.ParentElement<SELECT>
    {
        /** HtmlElementType for <code>&lt;select&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("select", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set boolean attribute <code><b>disabled</b></code>. */
        public SELECT disabled(boolean value) { return attr("disabled", value); }

        /** Set boolean attribute <code><b>multiple</b></code>. */
        public SELECT multiple(boolean value) { return attr("multiple", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>. */
        public SELECT name(CharSequence value) { return attr("name", value); }

        /** Set attribute <code><b>size="</b><i>{value}</i><b>"</b></code>. */
        public SELECT size(int value) { return attr("size", value); }

        /** Set attribute <code><b>tabindex="</b><i>{value}</i><b>"</b></code>. */
        public SELECT tabindex(int value) { return attr("tabindex", value); }

    }
    /** Build element <code>&lt;select&gt;</code>. */
    default public SELECT _select(){ return ContextParent.add(new SELECT()); }
    /** Build element <code>&lt;select&gt;</code> with children. */
    default public SELECT _select(Object... children){ return ContextParent.add(new SELECT(), children); }
    /** Build element <code>&lt;select&gt;</code>; with it as the context parent, run `code`. */
    default public SELECT _select(Runnable code){ return ContextParent.add(new SELECT(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;small&gt;</code>. */
    public static class SMALL extends HtmlParentElement implements Html4.ParentElement<SMALL>
    {
        /** HtmlElementType for <code>&lt;small&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("small", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;small&gt;</code>. */
    default public SMALL _small(){ return ContextParent.add(new SMALL()); }
    /** Build element <code>&lt;small&gt;</code> with children. */
    default public SMALL _small(Object... children){ return ContextParent.add(new SMALL(), children); }
    /** Build element <code>&lt;small&gt;</code>; with it as the context parent, run `code`. */
    default public SMALL _small(Runnable code){ return ContextParent.add(new SMALL(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;span&gt;</code>. */
    public static class SPAN extends HtmlParentElement implements Html4.ParentElement<SPAN>
    {
        /** HtmlElementType for <code>&lt;span&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("span", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;span&gt;</code>. */
    default public SPAN _span(){ return ContextParent.add(new SPAN()); }
    /** Build element <code>&lt;span&gt;</code> with children. */
    default public SPAN _span(Object... children){ return ContextParent.add(new SPAN(), children); }
    /** Build element <code>&lt;span&gt;</code>; with it as the context parent, run `code`. */
    default public SPAN _span(Runnable code){ return ContextParent.add(new SPAN(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;strong&gt;</code>. */
    public static class STRONG extends HtmlParentElement implements Html4.ParentElement<STRONG>
    {
        /** HtmlElementType for <code>&lt;strong&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("strong", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;strong&gt;</code>. */
    default public STRONG _strong(){ return ContextParent.add(new STRONG()); }
    /** Build element <code>&lt;strong&gt;</code> with children. */
    default public STRONG _strong(Object... children){ return ContextParent.add(new STRONG(), children); }
    /** Build element <code>&lt;strong&gt;</code>; with it as the context parent, run `code`. */
    default public STRONG _strong(Runnable code){ return ContextParent.add(new STRONG(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;style&gt;</code>. */
    public static class STYLE extends HtmlParentElement implements Html4.ParentElement<STYLE>
    {
        /** HtmlElementType for <code>&lt;style&gt;</code> (void=false, block=true, pre=true). */
        public static final HtmlElementType TYPE = new HtmlElementType("style", false, true, true);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>media="</b><i>{value}</i><b>"</b></code>. */
        public STYLE media(CharSequence value) { return attr("media", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>. */
        public STYLE type(CharSequence value) { return attr("type", value); }

    }
    /** Build element <code>&lt;style&gt;</code>. */
    default public STYLE _style(){ return ContextParent.add(new STYLE()); }
    /** Build element <code>&lt;style&gt;</code> with children. */
    default public STYLE _style(Object... children){ return ContextParent.add(new STYLE(), children); }
    /** Build element <code>&lt;style&gt;</code>; with it as the context parent, run `code`. */
    default public STYLE _style(Runnable code){ return ContextParent.add(new STYLE(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;sub&gt;</code>. */
    public static class SUB extends HtmlParentElement implements Html4.ParentElement<SUB>
    {
        /** HtmlElementType for <code>&lt;sub&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("sub", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;sub&gt;</code>. */
    default public SUB _sub(){ return ContextParent.add(new SUB()); }
    /** Build element <code>&lt;sub&gt;</code> with children. */
    default public SUB _sub(Object... children){ return ContextParent.add(new SUB(), children); }
    /** Build element <code>&lt;sub&gt;</code>; with it as the context parent, run `code`. */
    default public SUB _sub(Runnable code){ return ContextParent.add(new SUB(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;sup&gt;</code>. */
    public static class SUP extends HtmlParentElement implements Html4.ParentElement<SUP>
    {
        /** HtmlElementType for <code>&lt;sup&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("sup", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;sup&gt;</code>. */
    default public SUP _sup(){ return ContextParent.add(new SUP()); }
    /** Build element <code>&lt;sup&gt;</code> with children. */
    default public SUP _sup(Object... children){ return ContextParent.add(new SUP(), children); }
    /** Build element <code>&lt;sup&gt;</code>; with it as the context parent, run `code`. */
    default public SUP _sup(Runnable code){ return ContextParent.add(new SUP(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;table&gt;</code>. */
    public static class TABLE extends HtmlParentElement implements Html4.ParentElement<TABLE>
    {
        /** HtmlElementType for <code>&lt;table&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("table", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>border="</b><i>{value}</i><b>"</b></code>. */
        public TABLE border(int value) { return attr("border", value); }

        /** Set attribute <code><b>cellpadding="</b><i>{value}</i><b>"</b></code>. */
        public TABLE cellpadding(int value) { return attr("cellpadding", value); }

        /** Set attribute <code><b>cellpadding="</b><i>{value}</i><b>"</b></code>. */
        public TABLE cellpadding(CharSequence value) { return attr("cellpadding", value); }

        /** Set attribute <code><b>cellspacing="</b><i>{value}</i><b>"</b></code>. */
        public TABLE cellspacing(int value) { return attr("cellspacing", value); }

        /** Set attribute <code><b>cellspacing="</b><i>{value}</i><b>"</b></code>. */
        public TABLE cellspacing(CharSequence value) { return attr("cellspacing", value); }

        /** Set attribute <code><b>frame="</b><i>{value}</i><b>"</b></code>. */
        public TABLE frame(CharSequence value) { return attr("frame", value); }

        /** Set attribute <code><b>rules="</b><i>{value}</i><b>"</b></code>. */
        public TABLE rules(CharSequence value) { return attr("rules", value); }

        /** Set attribute <code><b>summary="</b><i>{value}</i><b>"</b></code>. */
        public TABLE summary(CharSequence value) { return attr("summary", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>. */
        public TABLE width(int value) { return attr("width", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>. */
        public TABLE width(CharSequence value) { return attr("width", value); }

    }
    /** Build element <code>&lt;table&gt;</code>. */
    default public TABLE _table(){ return ContextParent.add(new TABLE()); }
    /** Build element <code>&lt;table&gt;</code> with children. */
    default public TABLE _table(Object... children){ return ContextParent.add(new TABLE(), children); }
    /** Build element <code>&lt;table&gt;</code>; with it as the context parent, run `code`. */
    default public TABLE _table(Runnable code){ return ContextParent.add(new TABLE(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;tbody&gt;</code>. */
    public static class TBODY extends HtmlParentElement implements Html4.ParentElement<TBODY>
    {
        /** HtmlElementType for <code>&lt;tbody&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("tbody", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>align="</b><i>{value}</i><b>"</b></code>. */
        public TBODY align(CharSequence value) { return attr("align", value); }

        /** Set attribute <code><b>char="</b><i>{value}</i><b>"</b></code>. */
        public TBODY char_(CharSequence value) { return attr("char", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>. */
        public TBODY charoff(int value) { return attr("charoff", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>. */
        public TBODY charoff(CharSequence value) { return attr("charoff", value); }

        /** Set attribute <code><b>valign="</b><i>{value}</i><b>"</b></code>. */
        public TBODY valign(CharSequence value) { return attr("valign", value); }

    }
    /** Build element <code>&lt;tbody&gt;</code>. */
    default public TBODY _tbody(){ return ContextParent.add(new TBODY()); }
    /** Build element <code>&lt;tbody&gt;</code> with children. */
    default public TBODY _tbody(Object... children){ return ContextParent.add(new TBODY(), children); }
    /** Build element <code>&lt;tbody&gt;</code>; with it as the context parent, run `code`. */
    default public TBODY _tbody(Runnable code){ return ContextParent.add(new TBODY(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;td&gt;</code>. */
    public static class TD extends HtmlParentElement implements Html4.ParentElement<TD>
    {
        /** HtmlElementType for <code>&lt;td&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("td", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>abbr="</b><i>{value}</i><b>"</b></code>. */
        public TD abbr(CharSequence value) { return attr("abbr", value); }

        /** Set attribute <code><b>align="</b><i>{value}</i><b>"</b></code>. */
        public TD align(CharSequence value) { return attr("align", value); }

        /** Set attribute <code><b>axis="</b><i>{value}</i><b>"</b></code>. */
        public TD axis(CharSequence value) { return attr("axis", value); }

        /** Set attribute <code><b>char="</b><i>{value}</i><b>"</b></code>. */
        public TD char_(CharSequence value) { return attr("char", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>. */
        public TD charoff(int value) { return attr("charoff", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>. */
        public TD charoff(CharSequence value) { return attr("charoff", value); }

        /** Set attribute <code><b>colspan="</b><i>{value}</i><b>"</b></code>. */
        public TD colspan(int value) { return attr("colspan", value); }

        /** Set attribute <code><b>headers="</b><i>{value}</i><b>"</b></code>. */
        public TD headers(CharSequence value) { return attr("headers", value); }

        /** Set attribute <code><b>rowspan="</b><i>{value}</i><b>"</b></code>. */
        public TD rowspan(int value) { return attr("rowspan", value); }

        /** Set attribute <code><b>scope="</b><i>{value}</i><b>"</b></code>. */
        public TD scope(CharSequence value) { return attr("scope", value); }

        /** Set attribute <code><b>valign="</b><i>{value}</i><b>"</b></code>. */
        public TD valign(CharSequence value) { return attr("valign", value); }

    }
    /** Build element <code>&lt;td&gt;</code>. */
    default public TD _td(){ return ContextParent.add(new TD()); }
    /** Build element <code>&lt;td&gt;</code> with children. */
    default public TD _td(Object... children){ return ContextParent.add(new TD(), children); }
    /** Build element <code>&lt;td&gt;</code>; with it as the context parent, run `code`. */
    default public TD _td(Runnable code){ return ContextParent.add(new TD(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;textarea&gt;</code>. */
    public static class TEXTAREA extends HtmlParentElement implements Html4.ParentElement<TEXTAREA>
    {
        /** HtmlElementType for <code>&lt;textarea&gt;</code> (void=false, block=false, pre=true). */
        public static final HtmlElementType TYPE = new HtmlElementType("textarea", false, false, true);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>accesskey="</b><i>{value}</i><b>"</b></code>. */
        public TEXTAREA accesskey(CharSequence value) { return attr("accesskey", value); }

        /** Set attribute <code><b>cols="</b><i>{value}</i><b>"</b></code>. */
        public TEXTAREA cols(int value) { return attr("cols", value); }

        /** Set boolean attribute <code><b>disabled</b></code>. */
        public TEXTAREA disabled(boolean value) { return attr("disabled", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>. */
        public TEXTAREA name(CharSequence value) { return attr("name", value); }

        /** Set boolean attribute <code><b>readonly</b></code>. */
        public TEXTAREA readonly(boolean value) { return attr("readonly", value); }

        /** Set attribute <code><b>rows="</b><i>{value}</i><b>"</b></code>. */
        public TEXTAREA rows(int value) { return attr("rows", value); }

        /** Set attribute <code><b>tabindex="</b><i>{value}</i><b>"</b></code>. */
        public TEXTAREA tabindex(int value) { return attr("tabindex", value); }

    }
    /** Build element <code>&lt;textarea&gt;</code>. */
    default public TEXTAREA _textarea(){ return ContextParent.add(new TEXTAREA()); }
    /** Build element <code>&lt;textarea&gt;</code> with children. */
    default public TEXTAREA _textarea(Object... children){ return ContextParent.add(new TEXTAREA(), children); }
    /** Build element <code>&lt;textarea&gt;</code>; with it as the context parent, run `code`. */
    default public TEXTAREA _textarea(Runnable code){ return ContextParent.add(new TEXTAREA(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;tfoot&gt;</code>. */
    public static class TFOOT extends HtmlParentElement implements Html4.ParentElement<TFOOT>
    {
        /** HtmlElementType for <code>&lt;tfoot&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("tfoot", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>align="</b><i>{value}</i><b>"</b></code>. */
        public TFOOT align(CharSequence value) { return attr("align", value); }

        /** Set attribute <code><b>char="</b><i>{value}</i><b>"</b></code>. */
        public TFOOT char_(CharSequence value) { return attr("char", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>. */
        public TFOOT charoff(int value) { return attr("charoff", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>. */
        public TFOOT charoff(CharSequence value) { return attr("charoff", value); }

        /** Set attribute <code><b>valign="</b><i>{value}</i><b>"</b></code>. */
        public TFOOT valign(CharSequence value) { return attr("valign", value); }

    }
    /** Build element <code>&lt;tfoot&gt;</code>. */
    default public TFOOT _tfoot(){ return ContextParent.add(new TFOOT()); }
    /** Build element <code>&lt;tfoot&gt;</code> with children. */
    default public TFOOT _tfoot(Object... children){ return ContextParent.add(new TFOOT(), children); }
    /** Build element <code>&lt;tfoot&gt;</code>; with it as the context parent, run `code`. */
    default public TFOOT _tfoot(Runnable code){ return ContextParent.add(new TFOOT(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;th&gt;</code>. */
    public static class TH extends HtmlParentElement implements Html4.ParentElement<TH>
    {
        /** HtmlElementType for <code>&lt;th&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("th", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>abbr="</b><i>{value}</i><b>"</b></code>. */
        public TH abbr(CharSequence value) { return attr("abbr", value); }

        /** Set attribute <code><b>align="</b><i>{value}</i><b>"</b></code>. */
        public TH align(CharSequence value) { return attr("align", value); }

        /** Set attribute <code><b>axis="</b><i>{value}</i><b>"</b></code>. */
        public TH axis(CharSequence value) { return attr("axis", value); }

        /** Set attribute <code><b>char="</b><i>{value}</i><b>"</b></code>. */
        public TH char_(CharSequence value) { return attr("char", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>. */
        public TH charoff(int value) { return attr("charoff", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>. */
        public TH charoff(CharSequence value) { return attr("charoff", value); }

        /** Set attribute <code><b>colspan="</b><i>{value}</i><b>"</b></code>. */
        public TH colspan(int value) { return attr("colspan", value); }

        /** Set attribute <code><b>headers="</b><i>{value}</i><b>"</b></code>. */
        public TH headers(CharSequence value) { return attr("headers", value); }

        /** Set attribute <code><b>rowspan="</b><i>{value}</i><b>"</b></code>. */
        public TH rowspan(int value) { return attr("rowspan", value); }

        /** Set attribute <code><b>scope="</b><i>{value}</i><b>"</b></code>. */
        public TH scope(CharSequence value) { return attr("scope", value); }

        /** Set attribute <code><b>valign="</b><i>{value}</i><b>"</b></code>. */
        public TH valign(CharSequence value) { return attr("valign", value); }

    }
    /** Build element <code>&lt;th&gt;</code>. */
    default public TH _th(){ return ContextParent.add(new TH()); }
    /** Build element <code>&lt;th&gt;</code> with children. */
    default public TH _th(Object... children){ return ContextParent.add(new TH(), children); }
    /** Build element <code>&lt;th&gt;</code>; with it as the context parent, run `code`. */
    default public TH _th(Runnable code){ return ContextParent.add(new TH(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;thead&gt;</code>. */
    public static class THEAD extends HtmlParentElement implements Html4.ParentElement<THEAD>
    {
        /** HtmlElementType for <code>&lt;thead&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("thead", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>align="</b><i>{value}</i><b>"</b></code>. */
        public THEAD align(CharSequence value) { return attr("align", value); }

        /** Set attribute <code><b>char="</b><i>{value}</i><b>"</b></code>. */
        public THEAD char_(CharSequence value) { return attr("char", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>. */
        public THEAD charoff(int value) { return attr("charoff", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>. */
        public THEAD charoff(CharSequence value) { return attr("charoff", value); }

        /** Set attribute <code><b>valign="</b><i>{value}</i><b>"</b></code>. */
        public THEAD valign(CharSequence value) { return attr("valign", value); }

    }
    /** Build element <code>&lt;thead&gt;</code>. */
    default public THEAD _thead(){ return ContextParent.add(new THEAD()); }
    /** Build element <code>&lt;thead&gt;</code> with children. */
    default public THEAD _thead(Object... children){ return ContextParent.add(new THEAD(), children); }
    /** Build element <code>&lt;thead&gt;</code>; with it as the context parent, run `code`. */
    default public THEAD _thead(Runnable code){ return ContextParent.add(new THEAD(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;title&gt;</code>. */
    public static class TITLE extends HtmlParentElement implements Html4.ParentElement<TITLE>
    {
        /** HtmlElementType for <code>&lt;title&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("title", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;title&gt;</code>. */
    default public TITLE _title(){ return ContextParent.add(new TITLE()); }
    /** Build element <code>&lt;title&gt;</code> with children. */
    default public TITLE _title(Object... children){ return ContextParent.add(new TITLE(), children); }
    /** Build element <code>&lt;title&gt;</code>; with it as the context parent, run `code`. */
    default public TITLE _title(Runnable code){ return ContextParent.add(new TITLE(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;tr&gt;</code>. */
    public static class TR extends HtmlParentElement implements Html4.ParentElement<TR>
    {
        /** HtmlElementType for <code>&lt;tr&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("tr", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>align="</b><i>{value}</i><b>"</b></code>. */
        public TR align(CharSequence value) { return attr("align", value); }

        /** Set attribute <code><b>char="</b><i>{value}</i><b>"</b></code>. */
        public TR char_(CharSequence value) { return attr("char", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>. */
        public TR charoff(int value) { return attr("charoff", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>. */
        public TR charoff(CharSequence value) { return attr("charoff", value); }

        /** Set attribute <code><b>valign="</b><i>{value}</i><b>"</b></code>. */
        public TR valign(CharSequence value) { return attr("valign", value); }

    }
    /** Build element <code>&lt;tr&gt;</code>. */
    default public TR _tr(){ return ContextParent.add(new TR()); }
    /** Build element <code>&lt;tr&gt;</code> with children. */
    default public TR _tr(Object... children){ return ContextParent.add(new TR(), children); }
    /** Build element <code>&lt;tr&gt;</code>; with it as the context parent, run `code`. */
    default public TR _tr(Runnable code){ return ContextParent.add(new TR(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;tt&gt;</code>. */
    public static class TT extends HtmlParentElement implements Html4.ParentElement<TT>
    {
        /** HtmlElementType for <code>&lt;tt&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("tt", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;tt&gt;</code>. */
    default public TT _tt(){ return ContextParent.add(new TT()); }
    /** Build element <code>&lt;tt&gt;</code> with children. */
    default public TT _tt(Object... children){ return ContextParent.add(new TT(), children); }
    /** Build element <code>&lt;tt&gt;</code>; with it as the context parent, run `code`. */
    default public TT _tt(Runnable code){ return ContextParent.add(new TT(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;ul&gt;</code>. */
    public static class UL extends HtmlParentElement implements Html4.ParentElement<UL>
    {
        /** HtmlElementType for <code>&lt;ul&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("ul", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;ul&gt;</code>. */
    default public UL _ul(){ return ContextParent.add(new UL()); }
    /** Build element <code>&lt;ul&gt;</code> with children. */
    default public UL _ul(Object... children){ return ContextParent.add(new UL(), children); }
    /** Build element <code>&lt;ul&gt;</code>; with it as the context parent, run `code`. */
    default public UL _ul(Runnable code){ return ContextParent.add(new UL(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html4 element <code>&lt;var&gt;</code>. */
    public static class VAR extends HtmlParentElement implements Html4.ParentElement<VAR>
    {
        /** HtmlElementType for <code>&lt;var&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("var", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;var&gt;</code>. */
    default public VAR _var(){ return ContextParent.add(new VAR()); }
    /** Build element <code>&lt;var&gt;</code> with children. */
    default public VAR _var(Object... children){ return ContextParent.add(new VAR(), children); }
    /** Build element <code>&lt;var&gt;</code>; with it as the context parent, run `code`. */
    default public VAR _var(Runnable code){ return ContextParent.add(new VAR(), code); }

}
