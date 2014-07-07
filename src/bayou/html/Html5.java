package bayou.html;


// mostly based on w3c html5.1 draft
// probably unstable; elements/attributes may be changed in future.

/**
 * Html5 builder.
 * <p>
 *     This interface contains html5 element types (e.g. {@link Html5.DIV}),
 *     and element builder methods (e.g. {@link Html5#_div()}).
 * </p>
 * <h4>Element Type</h4>
 * <p>
 *     Each element type contains methods to set element-specific attributes,
 *     for example {@link A#href A.href(value)}.
 * </p>
 * <p>
 *     For common attributes or non-standard attributes, see methods in {@link Html5.Element}.
 * </p>
 * <p>
 *     If the element is a parent element, it has methods to add children, see {@link Html5.ParentElement}.
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
 *     <a href="http://www.w3.org/html/wg/drafts/html/master/">W3C HTML 5.1 draft</a>.
 * </p>
 * <h4>Relation to Html4</h4>
 * <p>
 *     Each Html5 element type is a subclass of the corresponding {@link Html4} element type
 *     (if it exists in html4).
 *     For example, <br> {@link Html5.DIV} {@code extends Html4.DIV}.
 * </p>
 * <h4>Deprecation</h4>
 * <p>
 *     Three elements in html4, <code>&lt;acronym/big/tt&gt;</code>, are deprecated in html5;
 *     they don't exist in this interface as element types or builder methods.
 * </p>
 * <p>
 *     Many attributes in html4 are deprecated in html5;
 *     the corresponding methods are marked as deprecated, for example see
 *     {@link Html5.A#name Html5.A.name()}.
 * </p>
 */

// 3 elements in html4 are deprecated in html5: acronym/big/tt
// we don't have them in this interface, like BIG or _big()

@SuppressWarnings({"UnusedDeclaration"})
public interface Html5 extends HtmlBuilder
{

    /**
     * An instance of Html5.
     * <p>
     *     Usually members of Html5 are accessed through inheritance.
     *     Note that Html5 is an interface with no abstract methods.
     * </p>
     * <p>
     *     However, if inheritance is not suitable in some situations,
     *     application code can access Html5 methods through this instance, e.g.
     * </p>
     * <pre>
     *     import static bayou.html.Html5.*;
     *
     *         html5._div();
     * </pre>
     */
    public static final Html5 html5 = new Html5(){};

    /**
     * The DOCTYPE of HTML 5.
     */
    public static final String DOCTYPE ="<!DOCTYPE html>";

    /**
     * Html5 element.
     * <p>
     *     This interface contains convenience method to set common attributes or non-standard attributes.
     * </p>
     * <p>
     *     Most methods return `this` for method chaining.
     * </p>
     */
    @SuppressWarnings("unchecked")
    public interface Element<This extends T4, T4> extends Html4.Element<T4>
    {
        // redefine methods with covariant return type
        // CAUTION: must override all methods in Html4 that return This

        // inherit javadocs

        default public This attr(String name, CharSequence value)
        {
            setAttribute(name, value);
            return (This)this;
        }
        default public This attr(String name, boolean value)
        {
            setAttribute(name, value?"":null);
            return (This)this;
        }
        default public This attr(String name, int value)
        {
            setAttribute(name, Integer.toString(value));
            return (This)this;
        }
        default public This on(String event, CharSequence script)
        {
            setAttribute("on"+event, script);
            return (This)this;
        }

        /**
         * Set a data attribute. Equivalent to `attr("data-"+name, String.valueOf(value))`.
         * <p>For example,</p>
         * <pre>
         *     _div().data("taste", "chicken"); // &lt;div data-taste="chicken"&gt;
         * </pre>
         */
        // data is arbitrary Object
        default This data(String name, Object value)
        {
            return attr("data-"+name, String.valueOf(value));
        }

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

        /** Set attribute <code><b>accesskey="</b><i>{value}</i><b>"</b></code>.  */
        default public This accesskey(CharSequence value) { return attr("accesskey", value); }

        /** Set attribute <code><b>class="</b><i>{value}</i><b>"</b></code>.  */
        default public This class_(CharSequence value) { return attr("class", value); }

        /** Set attribute <code><b>contenteditable="</b><i>true/false</i><b>"</b></code>. */
        default public This contenteditable(boolean value) { return attr("contenteditable", value?"true":"false"); }

        /** Set attribute <code><b>contextmenu="</b><i>{value}</i><b>"</b></code>.  */
        default public This contextmenu(CharSequence value) { return attr("contextmenu", value); }

        /** Set attribute <code><b>dir="</b><i>{value}</i><b>"</b></code>.  */
        default public This dir(CharSequence value) { return attr("dir", value); }

        /** Set attribute <code><b>draggable="</b><i>true/false</i><b>"</b></code>. */
        default public This draggable(boolean value) { return attr("draggable", value?"true":"false"); }

        /** Set attribute <code><b>dropzone="</b><i>{value}</i><b>"</b></code>.  */
        default public This dropzone(CharSequence value) { return attr("dropzone", value); }

        /** Set boolean attribute <code><b>hidden</b></code>.  */
        default public This hidden(boolean value) { return attr("hidden", value); }

        /** Set attribute <code><b>id="</b><i>{value}</i><b>"</b></code>.  */
        default public This id(CharSequence value) { return attr("id", value); }

        /** Set boolean attribute <code><b>inert</b></code>.  */
        default public This inert(boolean value) { return attr("inert", value); }

        /** Set attribute <code><b>itemid="</b><i>{value}</i><b>"</b></code>.  */
        default public This itemid(CharSequence value) { return attr("itemid", value); }

        /** Set attribute <code><b>itemprop="</b><i>{value}</i><b>"</b></code>.  */
        default public This itemprop(CharSequence value) { return attr("itemprop", value); }

        /** Set attribute <code><b>itemref="</b><i>{value}</i><b>"</b></code>.  */
        default public This itemref(CharSequence value) { return attr("itemref", value); }

        /** Set boolean attribute <code><b>itemscope</b></code>.  */
        default public This itemscope(boolean value) { return attr("itemscope", value); }

        /** Set attribute <code><b>itemtype="</b><i>{value}</i><b>"</b></code>.  */
        default public This itemtype(CharSequence value) { return attr("itemtype", value); }

        /** Set attribute <code><b>lang="</b><i>{value}</i><b>"</b></code>.  */
        default public This lang(CharSequence value) { return attr("lang", value); }

        /** Set attribute <code><b>spellcheck="</b><i>true/false</i><b>"</b></code>. */
        default public This spellcheck(boolean value) { return attr("spellcheck", value?"true":"false"); }

        /** Set attribute <code><b>style="</b><i>{value}</i><b>"</b></code>.  */
        default public This style(CharSequence value) { return attr("style", value); }

        /** Set attribute <code><b>tabindex="</b><i>{value}</i><b>"</b></code>.  */
        default public This tabindex(int value) { return attr("tabindex", value); }

        /** Set attribute <code><b>title="</b><i>{value}</i><b>"</b></code>.  */
        default public This title(CharSequence value) { return attr("title", value); }

        /** Set attribute <code><b>translate="</b><i>yes/no</i><b>"</b></code>. */
        default public This translate(boolean value) { return attr("translate", value?"yes":"no"); }

    }

    /**
     * Html5 parent element.
     * <p>
     *     This interface contains methods to add children to this parent.
     * </p>
     */
    @SuppressWarnings("unchecked")
    public interface ParentElement<This extends T4, T4> extends Html4.ParentElement<T4>, Element<This, T4>
    {
        // redefine methods with covariant return type
        // CAUTION: must override all methods in Html4 that return This

        // inherit javadocs

        default public This add(Object... children)
        {
            ContextParent.detachThenAddTo(children, this);
            return (This)this;
        }
        default public This add(Runnable code)
        {
            ContextParent.with(this, code);
            return (This)this;
        }
    }



    // generated code =============================================================================================


    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;a&gt;</code>. */
    public static class A extends Html4.A implements Html5.ParentElement<A, Html4.A>
    {
        /** Set attribute <code><b>download="</b><i>{value}</i><b>"</b></code>.  */
        public A download(CharSequence value) { return attr("download", value); }

        /** Set attribute <code><b>href="</b><i>{value}</i><b>"</b></code>.  */
        public A href(CharSequence value) { return attr("href", value); }

        /** Set attribute <code><b>hreflang="</b><i>{value}</i><b>"</b></code>.  */
        public A hreflang(CharSequence value) { return attr("hreflang", value); }

        /** Set attribute <code><b>rel="</b><i>{value}</i><b>"</b></code>.  */
        public A rel(CharSequence value) { return attr("rel", value); }

        /** Set attribute <code><b>target="</b><i>{value}</i><b>"</b></code>.  */
        public A target(CharSequence value) { return attr("target", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>.  */
        public A type(CharSequence value) { return attr("type", value); }

        /** Set attribute <code><b>accesskey="</b><i>{value}</i><b>"</b></code>.  */
        public A accesskey(CharSequence value) { return attr("accesskey", value); }

        /** Set attribute <code><b>charset="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public A charset(CharSequence value) { return attr("charset", value); }

        /** Set attribute <code><b>coords="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public A coords(CharSequence value) { return attr("coords", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public A name(CharSequence value) { return attr("name", value); }

        /** Set attribute <code><b>rev="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public A rev(CharSequence value) { return attr("rev", value); }

        /** Set attribute <code><b>shape="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public A shape(CharSequence value) { return attr("shape", value); }

        /** Set attribute <code><b>tabindex="</b><i>{value}</i><b>"</b></code>.  */
        public A tabindex(int value) { return attr("tabindex", value); }

    }
    /** Build element <code>&lt;a&gt;</code>. */
    default public A _a(){ return ContextParent.add(new A()); }
    /** Build element <code>&lt;a&gt;</code> with children. */
    default public A _a(Object... children){ return ContextParent.add(new A(), children); }
    /** Build element <code>&lt;a&gt;</code>; with it as the context parent, run `code`. */
    default public A _a(Runnable code){ return ContextParent.add(new A(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;abbr&gt;</code>. */
    public static class ABBR extends Html4.ABBR implements Html5.ParentElement<ABBR, Html4.ABBR>
    {
    }
    /** Build element <code>&lt;abbr&gt;</code>. */
    default public ABBR _abbr(){ return ContextParent.add(new ABBR()); }
    /** Build element <code>&lt;abbr&gt;</code> with children. */
    default public ABBR _abbr(Object... children){ return ContextParent.add(new ABBR(), children); }
    /** Build element <code>&lt;abbr&gt;</code>; with it as the context parent, run `code`. */
    default public ABBR _abbr(Runnable code){ return ContextParent.add(new ABBR(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;address&gt;</code>. */
    public static class ADDRESS extends Html4.ADDRESS implements Html5.ParentElement<ADDRESS, Html4.ADDRESS>
    {
    }
    /** Build element <code>&lt;address&gt;</code>. */
    default public ADDRESS _address(){ return ContextParent.add(new ADDRESS()); }
    /** Build element <code>&lt;address&gt;</code> with children. */
    default public ADDRESS _address(Object... children){ return ContextParent.add(new ADDRESS(), children); }
    /** Build element <code>&lt;address&gt;</code>; with it as the context parent, run `code`. */
    default public ADDRESS _address(Runnable code){ return ContextParent.add(new ADDRESS(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;area&gt;</code>. */
    public static class AREA extends Html4.AREA implements Html5.Element<AREA, Html4.AREA>
    {
        /** Set attribute <code><b>alt="</b><i>{value}</i><b>"</b></code>.  */
        public AREA alt(CharSequence value) { return attr("alt", value); }

        /** Set attribute <code><b>coords="</b><i>{value}</i><b>"</b></code>.  */
        public AREA coords(CharSequence value) { return attr("coords", value); }

        /** Set attribute <code><b>download="</b><i>{value}</i><b>"</b></code>.  */
        public AREA download(CharSequence value) { return attr("download", value); }

        /** Set attribute <code><b>href="</b><i>{value}</i><b>"</b></code>.  */
        public AREA href(CharSequence value) { return attr("href", value); }

        /** Set attribute <code><b>hreflang="</b><i>{value}</i><b>"</b></code>.  */
        public AREA hreflang(CharSequence value) { return attr("hreflang", value); }

        /** Set attribute <code><b>rel="</b><i>{value}</i><b>"</b></code>.  */
        public AREA rel(CharSequence value) { return attr("rel", value); }

        /** Set attribute <code><b>shape="</b><i>{value}</i><b>"</b></code>.  */
        public AREA shape(CharSequence value) { return attr("shape", value); }

        /** Set attribute <code><b>target="</b><i>{value}</i><b>"</b></code>.  */
        public AREA target(CharSequence value) { return attr("target", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>.  */
        public AREA type(CharSequence value) { return attr("type", value); }

        /** Set attribute <code><b>accesskey="</b><i>{value}</i><b>"</b></code>.  */
        public AREA accesskey(CharSequence value) { return attr("accesskey", value); }

        /** Set boolean attribute <code><b>nohref</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public AREA nohref(boolean value) { return attr("nohref", value); }

        /** Set attribute <code><b>tabindex="</b><i>{value}</i><b>"</b></code>.  */
        public AREA tabindex(int value) { return attr("tabindex", value); }

    }
    /** Build element <code>&lt;area&gt;</code>. */
    default public AREA _area(){ return ContextParent.add(new AREA()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;article&gt;</code>. */
    public static class ARTICLE extends HtmlParentElement implements Html5.ParentElement<ARTICLE, ARTICLE>
    {
        /** HtmlElementType for <code>&lt;article&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("article", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;article&gt;</code>. */
    default public ARTICLE _article(){ return ContextParent.add(new ARTICLE()); }
    /** Build element <code>&lt;article&gt;</code> with children. */
    default public ARTICLE _article(Object... children){ return ContextParent.add(new ARTICLE(), children); }
    /** Build element <code>&lt;article&gt;</code>; with it as the context parent, run `code`. */
    default public ARTICLE _article(Runnable code){ return ContextParent.add(new ARTICLE(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;aside&gt;</code>. */
    public static class ASIDE extends HtmlParentElement implements Html5.ParentElement<ASIDE, ASIDE>
    {
        /** HtmlElementType for <code>&lt;aside&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("aside", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;aside&gt;</code>. */
    default public ASIDE _aside(){ return ContextParent.add(new ASIDE()); }
    /** Build element <code>&lt;aside&gt;</code> with children. */
    default public ASIDE _aside(Object... children){ return ContextParent.add(new ASIDE(), children); }
    /** Build element <code>&lt;aside&gt;</code>; with it as the context parent, run `code`. */
    default public ASIDE _aside(Runnable code){ return ContextParent.add(new ASIDE(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;audio&gt;</code>. */
    public static class AUDIO extends HtmlParentElement implements Html5.ParentElement<AUDIO, AUDIO>
    {
        /** HtmlElementType for <code>&lt;audio&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("audio", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set boolean attribute <code><b>autoplay</b></code>.  */
        public AUDIO autoplay(boolean value) { return attr("autoplay", value); }

        /** Set boolean attribute <code><b>controls</b></code>.  */
        public AUDIO controls(boolean value) { return attr("controls", value); }

        /** Set attribute <code><b>crossorigin="</b><i>{value}</i><b>"</b></code>.  */
        public AUDIO crossorigin(CharSequence value) { return attr("crossorigin", value); }

        /** Set boolean attribute <code><b>loop</b></code>.  */
        public AUDIO loop(boolean value) { return attr("loop", value); }

        /** Set attribute <code><b>mediagroup="</b><i>{value}</i><b>"</b></code>.  */
        public AUDIO mediagroup(CharSequence value) { return attr("mediagroup", value); }

        /** Set boolean attribute <code><b>muted</b></code>.  */
        public AUDIO muted(boolean value) { return attr("muted", value); }

        /** Set attribute <code><b>preload="</b><i>{value}</i><b>"</b></code>.  */
        public AUDIO preload(CharSequence value) { return attr("preload", value); }

        /** Set attribute <code><b>src="</b><i>{value}</i><b>"</b></code>.  */
        public AUDIO src(CharSequence value) { return attr("src", value); }

    }
    /** Build element <code>&lt;audio&gt;</code>. */
    default public AUDIO _audio(){ return ContextParent.add(new AUDIO()); }
    /** Build element <code>&lt;audio&gt;</code> with children. */
    default public AUDIO _audio(Object... children){ return ContextParent.add(new AUDIO(), children); }
    /** Build element <code>&lt;audio&gt;</code>; with it as the context parent, run `code`. */
    default public AUDIO _audio(Runnable code){ return ContextParent.add(new AUDIO(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;b&gt;</code>. */
    public static class B extends Html4.B implements Html5.ParentElement<B, Html4.B>
    {
    }
    /** Build element <code>&lt;b&gt;</code>. */
    default public B _b(){ return ContextParent.add(new B()); }
    /** Build element <code>&lt;b&gt;</code> with children. */
    default public B _b(Object... children){ return ContextParent.add(new B(), children); }
    /** Build element <code>&lt;b&gt;</code>; with it as the context parent, run `code`. */
    default public B _b(Runnable code){ return ContextParent.add(new B(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;base&gt;</code>. */
    public static class BASE extends Html4.BASE implements Html5.Element<BASE, Html4.BASE>
    {
        /** Set attribute <code><b>href="</b><i>{value}</i><b>"</b></code>.  */
        public BASE href(CharSequence value) { return attr("href", value); }

        /** Set attribute <code><b>target="</b><i>{value}</i><b>"</b></code>.  */
        public BASE target(CharSequence value) { return attr("target", value); }

    }
    /** Build element <code>&lt;base&gt;</code>. */
    default public BASE _base(){ return ContextParent.add(new BASE()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;bdi&gt;</code>. */
    public static class BDI extends HtmlParentElement implements Html5.ParentElement<BDI, BDI>
    {
        /** HtmlElementType for <code>&lt;bdi&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("bdi", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;bdi&gt;</code>. */
    default public BDI _bdi(){ return ContextParent.add(new BDI()); }
    /** Build element <code>&lt;bdi&gt;</code> with children. */
    default public BDI _bdi(Object... children){ return ContextParent.add(new BDI(), children); }
    /** Build element <code>&lt;bdi&gt;</code>; with it as the context parent, run `code`. */
    default public BDI _bdi(Runnable code){ return ContextParent.add(new BDI(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;bdo&gt;</code>. */
    public static class BDO extends Html4.BDO implements Html5.ParentElement<BDO, Html4.BDO>
    {
    }
    /** Build element <code>&lt;bdo&gt;</code>. */
    default public BDO _bdo(){ return ContextParent.add(new BDO()); }
    /** Build element <code>&lt;bdo&gt;</code> with children. */
    default public BDO _bdo(Object... children){ return ContextParent.add(new BDO(), children); }
    /** Build element <code>&lt;bdo&gt;</code>; with it as the context parent, run `code`. */
    default public BDO _bdo(Runnable code){ return ContextParent.add(new BDO(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;blockquote&gt;</code>. */
    public static class BLOCKQUOTE extends Html4.BLOCKQUOTE implements Html5.ParentElement<BLOCKQUOTE, Html4.BLOCKQUOTE>
    {
        /** Set attribute <code><b>cite="</b><i>{value}</i><b>"</b></code>.  */
        public BLOCKQUOTE cite(CharSequence value) { return attr("cite", value); }

    }
    /** Build element <code>&lt;blockquote&gt;</code>. */
    default public BLOCKQUOTE _blockquote(){ return ContextParent.add(new BLOCKQUOTE()); }
    /** Build element <code>&lt;blockquote&gt;</code> with children. */
    default public BLOCKQUOTE _blockquote(Object... children){ return ContextParent.add(new BLOCKQUOTE(), children); }
    /** Build element <code>&lt;blockquote&gt;</code>; with it as the context parent, run `code`. */
    default public BLOCKQUOTE _blockquote(Runnable code){ return ContextParent.add(new BLOCKQUOTE(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;body&gt;</code>. */
    public static class BODY extends Html4.BODY implements Html5.ParentElement<BODY, Html4.BODY>
    {
    }
    /** Build element <code>&lt;body&gt;</code>. */
    default public BODY _body(){ return ContextParent.add(new BODY()); }
    /** Build element <code>&lt;body&gt;</code> with children. */
    default public BODY _body(Object... children){ return ContextParent.add(new BODY(), children); }
    /** Build element <code>&lt;body&gt;</code>; with it as the context parent, run `code`. */
    default public BODY _body(Runnable code){ return ContextParent.add(new BODY(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;br&gt;</code>. */
    public static class BR extends Html4.BR implements Html5.Element<BR, Html4.BR>
    {
    }
    /** Build element <code>&lt;br&gt;</code>. */
    default public BR _br(){ return ContextParent.add(new BR()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;button&gt;</code>. */
    public static class BUTTON extends Html4.BUTTON implements Html5.ParentElement<BUTTON, Html4.BUTTON>
    {
        /** Set boolean attribute <code><b>autofocus</b></code>.  */
        public BUTTON autofocus(boolean value) { return attr("autofocus", value); }

        /** Set boolean attribute <code><b>disabled</b></code>.  */
        public BUTTON disabled(boolean value) { return attr("disabled", value); }

        /** Set attribute <code><b>form="</b><i>{value}</i><b>"</b></code>.  */
        public BUTTON form(CharSequence value) { return attr("form", value); }

        /** Set attribute <code><b>formaction="</b><i>{value}</i><b>"</b></code>.  */
        public BUTTON formaction(CharSequence value) { return attr("formaction", value); }

        /** Set attribute <code><b>formenctype="</b><i>{value}</i><b>"</b></code>.  */
        public BUTTON formenctype(CharSequence value) { return attr("formenctype", value); }

        /** Set attribute <code><b>formmethod="</b><i>{value}</i><b>"</b></code>.  */
        public BUTTON formmethod(CharSequence value) { return attr("formmethod", value); }

        /** Set boolean attribute <code><b>formnovalidate</b></code>.  */
        public BUTTON formnovalidate(boolean value) { return attr("formnovalidate", value); }

        /** Set attribute <code><b>formtarget="</b><i>{value}</i><b>"</b></code>.  */
        public BUTTON formtarget(CharSequence value) { return attr("formtarget", value); }

        /** Set attribute <code><b>menu="</b><i>{value}</i><b>"</b></code>.  */
        public BUTTON menu(CharSequence value) { return attr("menu", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>.  */
        public BUTTON name(CharSequence value) { return attr("name", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>.  */
        public BUTTON type(CharSequence value) { return attr("type", value); }

        /** Set attribute <code><b>value="</b><i>{value}</i><b>"</b></code>.  */
        public BUTTON value(CharSequence value) { return attr("value", value); }

        /** Set attribute <code><b>accesskey="</b><i>{value}</i><b>"</b></code>.  */
        public BUTTON accesskey(CharSequence value) { return attr("accesskey", value); }

        /** Set attribute <code><b>tabindex="</b><i>{value}</i><b>"</b></code>.  */
        public BUTTON tabindex(int value) { return attr("tabindex", value); }

    }
    /** Build element <code>&lt;button&gt;</code>. */
    default public BUTTON _button(){ return ContextParent.add(new BUTTON()); }
    /** Build element <code>&lt;button&gt;</code> with children. */
    default public BUTTON _button(Object... children){ return ContextParent.add(new BUTTON(), children); }
    /** Build element <code>&lt;button&gt;</code>; with it as the context parent, run `code`. */
    default public BUTTON _button(Runnable code){ return ContextParent.add(new BUTTON(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;canvas&gt;</code>. */
    public static class CANVAS extends HtmlParentElement implements Html5.ParentElement<CANVAS, CANVAS>
    {
        /** HtmlElementType for <code>&lt;canvas&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("canvas", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>height="</b><i>{value}</i><b>"</b></code>.  */
        public CANVAS height(int value) { return attr("height", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>.  */
        public CANVAS width(int value) { return attr("width", value); }

    }
    /** Build element <code>&lt;canvas&gt;</code>. */
    default public CANVAS _canvas(){ return ContextParent.add(new CANVAS()); }
    /** Build element <code>&lt;canvas&gt;</code> with children. */
    default public CANVAS _canvas(Object... children){ return ContextParent.add(new CANVAS(), children); }
    /** Build element <code>&lt;canvas&gt;</code>; with it as the context parent, run `code`. */
    default public CANVAS _canvas(Runnable code){ return ContextParent.add(new CANVAS(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;caption&gt;</code>. */
    public static class CAPTION extends Html4.CAPTION implements Html5.ParentElement<CAPTION, Html4.CAPTION>
    {
    }
    /** Build element <code>&lt;caption&gt;</code>. */
    default public CAPTION _caption(){ return ContextParent.add(new CAPTION()); }
    /** Build element <code>&lt;caption&gt;</code> with children. */
    default public CAPTION _caption(Object... children){ return ContextParent.add(new CAPTION(), children); }
    /** Build element <code>&lt;caption&gt;</code>; with it as the context parent, run `code`. */
    default public CAPTION _caption(Runnable code){ return ContextParent.add(new CAPTION(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;cite&gt;</code>. */
    public static class CITE extends Html4.CITE implements Html5.ParentElement<CITE, Html4.CITE>
    {
    }
    /** Build element <code>&lt;cite&gt;</code>. */
    default public CITE _cite(){ return ContextParent.add(new CITE()); }
    /** Build element <code>&lt;cite&gt;</code> with children. */
    default public CITE _cite(Object... children){ return ContextParent.add(new CITE(), children); }
    /** Build element <code>&lt;cite&gt;</code>; with it as the context parent, run `code`. */
    default public CITE _cite(Runnable code){ return ContextParent.add(new CITE(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;code&gt;</code>. */
    public static class CODE extends Html4.CODE implements Html5.ParentElement<CODE, Html4.CODE>
    {
    }
    /** Build element <code>&lt;code&gt;</code>. */
    default public CODE _code(){ return ContextParent.add(new CODE()); }
    /** Build element <code>&lt;code&gt;</code> with children. */
    default public CODE _code(Object... children){ return ContextParent.add(new CODE(), children); }
    /** Build element <code>&lt;code&gt;</code>; with it as the context parent, run `code`. */
    default public CODE _code(Runnable code){ return ContextParent.add(new CODE(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;col&gt;</code>. */
    public static class COL extends Html4.COL implements Html5.Element<COL, Html4.COL>
    {
        /** Set attribute <code><b>span="</b><i>{value}</i><b>"</b></code>.  */
        public COL span(int value) { return attr("span", value); }

        /** Set attribute <code><b>align="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public COL align(CharSequence value) { return attr("align", value); }

        /** Set attribute <code><b>char="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public COL char_(CharSequence value) { return attr("char", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public COL charoff(int value) { return attr("charoff", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public COL charoff(CharSequence value) { return attr("charoff", value); }

        /** Set attribute <code><b>valign="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public COL valign(CharSequence value) { return attr("valign", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public COL width(int value) { return attr("width", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public COL width(CharSequence value) { return attr("width", value); }

    }
    /** Build element <code>&lt;col&gt;</code>. */
    default public COL _col(){ return ContextParent.add(new COL()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;colgroup&gt;</code>. */
    public static class COLGROUP extends Html4.COLGROUP implements Html5.ParentElement<COLGROUP, Html4.COLGROUP>
    {
        /** Set attribute <code><b>span="</b><i>{value}</i><b>"</b></code>.  */
        public COLGROUP span(int value) { return attr("span", value); }

        /** Set attribute <code><b>align="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public COLGROUP align(CharSequence value) { return attr("align", value); }

        /** Set attribute <code><b>char="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public COLGROUP char_(CharSequence value) { return attr("char", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public COLGROUP charoff(int value) { return attr("charoff", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public COLGROUP charoff(CharSequence value) { return attr("charoff", value); }

        /** Set attribute <code><b>valign="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public COLGROUP valign(CharSequence value) { return attr("valign", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public COLGROUP width(int value) { return attr("width", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public COLGROUP width(CharSequence value) { return attr("width", value); }

    }
    /** Build element <code>&lt;colgroup&gt;</code>. */
    default public COLGROUP _colgroup(){ return ContextParent.add(new COLGROUP()); }
    /** Build element <code>&lt;colgroup&gt;</code> with children. */
    default public COLGROUP _colgroup(Object... children){ return ContextParent.add(new COLGROUP(), children); }
    /** Build element <code>&lt;colgroup&gt;</code>; with it as the context parent, run `code`. */
    default public COLGROUP _colgroup(Runnable code){ return ContextParent.add(new COLGROUP(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;data&gt;</code>. */
    public static class DATA extends HtmlParentElement implements Html5.ParentElement<DATA, DATA>
    {
        /** HtmlElementType for <code>&lt;data&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("data", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>value="</b><i>{value}</i><b>"</b></code>.  */
        public DATA value(CharSequence value) { return attr("value", value); }

    }
    /** Build element <code>&lt;data&gt;</code>. */
    default public DATA _data(){ return ContextParent.add(new DATA()); }
    /** Build element <code>&lt;data&gt;</code> with children. */
    default public DATA _data(Object... children){ return ContextParent.add(new DATA(), children); }
    /** Build element <code>&lt;data&gt;</code>; with it as the context parent, run `code`. */
    default public DATA _data(Runnable code){ return ContextParent.add(new DATA(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;datalist&gt;</code>. */
    public static class DATALIST extends HtmlParentElement implements Html5.ParentElement<DATALIST, DATALIST>
    {
        /** HtmlElementType for <code>&lt;datalist&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("datalist", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;datalist&gt;</code>. */
    default public DATALIST _datalist(){ return ContextParent.add(new DATALIST()); }
    /** Build element <code>&lt;datalist&gt;</code> with children. */
    default public DATALIST _datalist(Object... children){ return ContextParent.add(new DATALIST(), children); }
    /** Build element <code>&lt;datalist&gt;</code>; with it as the context parent, run `code`. */
    default public DATALIST _datalist(Runnable code){ return ContextParent.add(new DATALIST(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;dd&gt;</code>. */
    public static class DD extends Html4.DD implements Html5.ParentElement<DD, Html4.DD>
    {
    }
    /** Build element <code>&lt;dd&gt;</code>. */
    default public DD _dd(){ return ContextParent.add(new DD()); }
    /** Build element <code>&lt;dd&gt;</code> with children. */
    default public DD _dd(Object... children){ return ContextParent.add(new DD(), children); }
    /** Build element <code>&lt;dd&gt;</code>; with it as the context parent, run `code`. */
    default public DD _dd(Runnable code){ return ContextParent.add(new DD(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;del&gt;</code>. */
    public static class DEL extends Html4.DEL implements Html5.ParentElement<DEL, Html4.DEL>
    {
        /** Set attribute <code><b>cite="</b><i>{value}</i><b>"</b></code>.  */
        public DEL cite(CharSequence value) { return attr("cite", value); }

        /** Set attribute <code><b>datetime="</b><i>{value}</i><b>"</b></code>.  */
        public DEL datetime(CharSequence value) { return attr("datetime", value); }

    }
    /** Build element <code>&lt;del&gt;</code>. */
    default public DEL _del(){ return ContextParent.add(new DEL()); }
    /** Build element <code>&lt;del&gt;</code> with children. */
    default public DEL _del(Object... children){ return ContextParent.add(new DEL(), children); }
    /** Build element <code>&lt;del&gt;</code>; with it as the context parent, run `code`. */
    default public DEL _del(Runnable code){ return ContextParent.add(new DEL(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;details&gt;</code>. */
    public static class DETAILS extends HtmlParentElement implements Html5.ParentElement<DETAILS, DETAILS>
    {
        /** HtmlElementType for <code>&lt;details&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("details", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set boolean attribute <code><b>open</b></code>.  */
        public DETAILS open(boolean value) { return attr("open", value); }

    }
    /** Build element <code>&lt;details&gt;</code>. */
    default public DETAILS _details(){ return ContextParent.add(new DETAILS()); }
    /** Build element <code>&lt;details&gt;</code> with children. */
    default public DETAILS _details(Object... children){ return ContextParent.add(new DETAILS(), children); }
    /** Build element <code>&lt;details&gt;</code>; with it as the context parent, run `code`. */
    default public DETAILS _details(Runnable code){ return ContextParent.add(new DETAILS(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;dfn&gt;</code>. */
    public static class DFN extends Html4.DFN implements Html5.ParentElement<DFN, Html4.DFN>
    {
    }
    /** Build element <code>&lt;dfn&gt;</code>. */
    default public DFN _dfn(){ return ContextParent.add(new DFN()); }
    /** Build element <code>&lt;dfn&gt;</code> with children. */
    default public DFN _dfn(Object... children){ return ContextParent.add(new DFN(), children); }
    /** Build element <code>&lt;dfn&gt;</code>; with it as the context parent, run `code`. */
    default public DFN _dfn(Runnable code){ return ContextParent.add(new DFN(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;dialog&gt;</code>. */
    public static class DIALOG extends HtmlParentElement implements Html5.ParentElement<DIALOG, DIALOG>
    {
        /** HtmlElementType for <code>&lt;dialog&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("dialog", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set boolean attribute <code><b>open</b></code>.  */
        public DIALOG open(boolean value) { return attr("open", value); }

    }
    /** Build element <code>&lt;dialog&gt;</code>. */
    default public DIALOG _dialog(){ return ContextParent.add(new DIALOG()); }
    /** Build element <code>&lt;dialog&gt;</code> with children. */
    default public DIALOG _dialog(Object... children){ return ContextParent.add(new DIALOG(), children); }
    /** Build element <code>&lt;dialog&gt;</code>; with it as the context parent, run `code`. */
    default public DIALOG _dialog(Runnable code){ return ContextParent.add(new DIALOG(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;div&gt;</code>. */
    public static class DIV extends Html4.DIV implements Html5.ParentElement<DIV, Html4.DIV>
    {
    }
    /** Build element <code>&lt;div&gt;</code>. */
    default public DIV _div(){ return ContextParent.add(new DIV()); }
    /** Build element <code>&lt;div&gt;</code> with children. */
    default public DIV _div(Object... children){ return ContextParent.add(new DIV(), children); }
    /** Build element <code>&lt;div&gt;</code>; with it as the context parent, run `code`. */
    default public DIV _div(Runnable code){ return ContextParent.add(new DIV(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;dl&gt;</code>. */
    public static class DL extends Html4.DL implements Html5.ParentElement<DL, Html4.DL>
    {
    }
    /** Build element <code>&lt;dl&gt;</code>. */
    default public DL _dl(){ return ContextParent.add(new DL()); }
    /** Build element <code>&lt;dl&gt;</code> with children. */
    default public DL _dl(Object... children){ return ContextParent.add(new DL(), children); }
    /** Build element <code>&lt;dl&gt;</code>; with it as the context parent, run `code`. */
    default public DL _dl(Runnable code){ return ContextParent.add(new DL(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;dt&gt;</code>. */
    public static class DT extends Html4.DT implements Html5.ParentElement<DT, Html4.DT>
    {
    }
    /** Build element <code>&lt;dt&gt;</code>. */
    default public DT _dt(){ return ContextParent.add(new DT()); }
    /** Build element <code>&lt;dt&gt;</code> with children. */
    default public DT _dt(Object... children){ return ContextParent.add(new DT(), children); }
    /** Build element <code>&lt;dt&gt;</code>; with it as the context parent, run `code`. */
    default public DT _dt(Runnable code){ return ContextParent.add(new DT(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;em&gt;</code>. */
    public static class EM extends Html4.EM implements Html5.ParentElement<EM, Html4.EM>
    {
    }
    /** Build element <code>&lt;em&gt;</code>. */
    default public EM _em(){ return ContextParent.add(new EM()); }
    /** Build element <code>&lt;em&gt;</code> with children. */
    default public EM _em(Object... children){ return ContextParent.add(new EM(), children); }
    /** Build element <code>&lt;em&gt;</code>; with it as the context parent, run `code`. */
    default public EM _em(Runnable code){ return ContextParent.add(new EM(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;embed&gt;</code>. */
    public static class EMBED extends HtmlElement implements Html5.Element<EMBED, EMBED>
    {
        /** HtmlElementType for <code>&lt;embed&gt;</code> (void=true, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("embed", true, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>any="</b><i>{value}</i><b>"</b></code>.  */
        public EMBED any(CharSequence value) { return attr("any", value); }

        /** Set attribute <code><b>height="</b><i>{value}</i><b>"</b></code>.  */
        public EMBED height(int value) { return attr("height", value); }

        /** Set attribute <code><b>src="</b><i>{value}</i><b>"</b></code>.  */
        public EMBED src(CharSequence value) { return attr("src", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>.  */
        public EMBED type(CharSequence value) { return attr("type", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>.  */
        public EMBED width(int value) { return attr("width", value); }

    }
    /** Build element <code>&lt;embed&gt;</code>. */
    default public EMBED _embed(){ return ContextParent.add(new EMBED()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;fieldset&gt;</code>. */
    public static class FIELDSET extends Html4.FIELDSET implements Html5.ParentElement<FIELDSET, Html4.FIELDSET>
    {
        /** Set boolean attribute <code><b>disabled</b></code>.  */
        public FIELDSET disabled(boolean value) { return attr("disabled", value); }

        /** Set attribute <code><b>form="</b><i>{value}</i><b>"</b></code>.  */
        public FIELDSET form(CharSequence value) { return attr("form", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>.  */
        public FIELDSET name(CharSequence value) { return attr("name", value); }

    }
    /** Build element <code>&lt;fieldset&gt;</code>. */
    default public FIELDSET _fieldset(){ return ContextParent.add(new FIELDSET()); }
    /** Build element <code>&lt;fieldset&gt;</code> with children. */
    default public FIELDSET _fieldset(Object... children){ return ContextParent.add(new FIELDSET(), children); }
    /** Build element <code>&lt;fieldset&gt;</code>; with it as the context parent, run `code`. */
    default public FIELDSET _fieldset(Runnable code){ return ContextParent.add(new FIELDSET(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;figcaption&gt;</code>. */
    public static class FIGCAPTION extends HtmlParentElement implements Html5.ParentElement<FIGCAPTION, FIGCAPTION>
    {
        /** HtmlElementType for <code>&lt;figcaption&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("figcaption", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;figcaption&gt;</code>. */
    default public FIGCAPTION _figcaption(){ return ContextParent.add(new FIGCAPTION()); }
    /** Build element <code>&lt;figcaption&gt;</code> with children. */
    default public FIGCAPTION _figcaption(Object... children){ return ContextParent.add(new FIGCAPTION(), children); }
    /** Build element <code>&lt;figcaption&gt;</code>; with it as the context parent, run `code`. */
    default public FIGCAPTION _figcaption(Runnable code){ return ContextParent.add(new FIGCAPTION(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;figure&gt;</code>. */
    public static class FIGURE extends HtmlParentElement implements Html5.ParentElement<FIGURE, FIGURE>
    {
        /** HtmlElementType for <code>&lt;figure&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("figure", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;figure&gt;</code>. */
    default public FIGURE _figure(){ return ContextParent.add(new FIGURE()); }
    /** Build element <code>&lt;figure&gt;</code> with children. */
    default public FIGURE _figure(Object... children){ return ContextParent.add(new FIGURE(), children); }
    /** Build element <code>&lt;figure&gt;</code>; with it as the context parent, run `code`. */
    default public FIGURE _figure(Runnable code){ return ContextParent.add(new FIGURE(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;footer&gt;</code>. */
    public static class FOOTER extends HtmlParentElement implements Html5.ParentElement<FOOTER, FOOTER>
    {
        /** HtmlElementType for <code>&lt;footer&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("footer", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;footer&gt;</code>. */
    default public FOOTER _footer(){ return ContextParent.add(new FOOTER()); }
    /** Build element <code>&lt;footer&gt;</code> with children. */
    default public FOOTER _footer(Object... children){ return ContextParent.add(new FOOTER(), children); }
    /** Build element <code>&lt;footer&gt;</code>; with it as the context parent, run `code`. */
    default public FOOTER _footer(Runnable code){ return ContextParent.add(new FOOTER(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;form&gt;</code>. */
    public static class FORM extends Html4.FORM implements Html5.ParentElement<FORM, Html4.FORM>
    {
        /** Set attribute <code><b>accept-charset="</b><i>{value}</i><b>"</b></code>.  */
        public FORM accept_charset(CharSequence value) { return attr("accept-charset", value); }

        /** Set attribute <code><b>action="</b><i>{value}</i><b>"</b></code>.  */
        public FORM action(CharSequence value) { return attr("action", value); }

        /** Set attribute <code><b>autocomplete="</b><i>on/off</i><b>"</b></code>. */
        public FORM autocomplete(boolean value) { return attr("autocomplete", value?"on":"off"); }

        /** Set attribute <code><b>enctype="</b><i>{value}</i><b>"</b></code>.  */
        public FORM enctype(CharSequence value) { return attr("enctype", value); }

        /** Set attribute <code><b>method="</b><i>{value}</i><b>"</b></code>.  */
        public FORM method(CharSequence value) { return attr("method", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>.  */
        public FORM name(CharSequence value) { return attr("name", value); }

        /** Set boolean attribute <code><b>novalidate</b></code>.  */
        public FORM novalidate(boolean value) { return attr("novalidate", value); }

        /** Set attribute <code><b>target="</b><i>{value}</i><b>"</b></code>.  */
        public FORM target(CharSequence value) { return attr("target", value); }

        /** Set attribute <code><b>accept="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public FORM accept(CharSequence value) { return attr("accept", value); }

    }
    /** Build element <code>&lt;form&gt;</code>. */
    default public FORM _form(){ return ContextParent.add(new FORM()); }
    /** Build element <code>&lt;form&gt;</code> with children. */
    default public FORM _form(Object... children){ return ContextParent.add(new FORM(), children); }
    /** Build element <code>&lt;form&gt;</code>; with it as the context parent, run `code`. */
    default public FORM _form(Runnable code){ return ContextParent.add(new FORM(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;h1&gt;</code>. */
    public static class H1 extends Html4.H1 implements Html5.ParentElement<H1, Html4.H1>
    {
    }
    /** Build element <code>&lt;h1&gt;</code>. */
    default public H1 _h1(){ return ContextParent.add(new H1()); }
    /** Build element <code>&lt;h1&gt;</code> with children. */
    default public H1 _h1(Object... children){ return ContextParent.add(new H1(), children); }
    /** Build element <code>&lt;h1&gt;</code>; with it as the context parent, run `code`. */
    default public H1 _h1(Runnable code){ return ContextParent.add(new H1(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;h2&gt;</code>. */
    public static class H2 extends Html4.H2 implements Html5.ParentElement<H2, Html4.H2>
    {
    }
    /** Build element <code>&lt;h2&gt;</code>. */
    default public H2 _h2(){ return ContextParent.add(new H2()); }
    /** Build element <code>&lt;h2&gt;</code> with children. */
    default public H2 _h2(Object... children){ return ContextParent.add(new H2(), children); }
    /** Build element <code>&lt;h2&gt;</code>; with it as the context parent, run `code`. */
    default public H2 _h2(Runnable code){ return ContextParent.add(new H2(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;h3&gt;</code>. */
    public static class H3 extends Html4.H3 implements Html5.ParentElement<H3, Html4.H3>
    {
    }
    /** Build element <code>&lt;h3&gt;</code>. */
    default public H3 _h3(){ return ContextParent.add(new H3()); }
    /** Build element <code>&lt;h3&gt;</code> with children. */
    default public H3 _h3(Object... children){ return ContextParent.add(new H3(), children); }
    /** Build element <code>&lt;h3&gt;</code>; with it as the context parent, run `code`. */
    default public H3 _h3(Runnable code){ return ContextParent.add(new H3(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;h4&gt;</code>. */
    public static class H4 extends Html4.H4 implements Html5.ParentElement<H4, Html4.H4>
    {
    }
    /** Build element <code>&lt;h4&gt;</code>. */
    default public H4 _h4(){ return ContextParent.add(new H4()); }
    /** Build element <code>&lt;h4&gt;</code> with children. */
    default public H4 _h4(Object... children){ return ContextParent.add(new H4(), children); }
    /** Build element <code>&lt;h4&gt;</code>; with it as the context parent, run `code`. */
    default public H4 _h4(Runnable code){ return ContextParent.add(new H4(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;h5&gt;</code>. */
    public static class H5 extends Html4.H5 implements Html5.ParentElement<H5, Html4.H5>
    {
    }
    /** Build element <code>&lt;h5&gt;</code>. */
    default public H5 _h5(){ return ContextParent.add(new H5()); }
    /** Build element <code>&lt;h5&gt;</code> with children. */
    default public H5 _h5(Object... children){ return ContextParent.add(new H5(), children); }
    /** Build element <code>&lt;h5&gt;</code>; with it as the context parent, run `code`. */
    default public H5 _h5(Runnable code){ return ContextParent.add(new H5(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;h6&gt;</code>. */
    public static class H6 extends Html4.H6 implements Html5.ParentElement<H6, Html4.H6>
    {
    }
    /** Build element <code>&lt;h6&gt;</code>. */
    default public H6 _h6(){ return ContextParent.add(new H6()); }
    /** Build element <code>&lt;h6&gt;</code> with children. */
    default public H6 _h6(Object... children){ return ContextParent.add(new H6(), children); }
    /** Build element <code>&lt;h6&gt;</code>; with it as the context parent, run `code`. */
    default public H6 _h6(Runnable code){ return ContextParent.add(new H6(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;head&gt;</code>. */
    public static class HEAD extends Html4.HEAD implements Html5.ParentElement<HEAD, Html4.HEAD>
    {
        /** Set attribute <code><b>profile="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public HEAD profile(CharSequence value) { return attr("profile", value); }

    }
    /** Build element <code>&lt;head&gt;</code>. */
    default public HEAD _head(){ return ContextParent.add(new HEAD()); }
    /** Build element <code>&lt;head&gt;</code> with children. */
    default public HEAD _head(Object... children){ return ContextParent.add(new HEAD(), children); }
    /** Build element <code>&lt;head&gt;</code>; with it as the context parent, run `code`. */
    default public HEAD _head(Runnable code){ return ContextParent.add(new HEAD(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;header&gt;</code>. */
    public static class HEADER extends HtmlParentElement implements Html5.ParentElement<HEADER, HEADER>
    {
        /** HtmlElementType for <code>&lt;header&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("header", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;header&gt;</code>. */
    default public HEADER _header(){ return ContextParent.add(new HEADER()); }
    /** Build element <code>&lt;header&gt;</code> with children. */
    default public HEADER _header(Object... children){ return ContextParent.add(new HEADER(), children); }
    /** Build element <code>&lt;header&gt;</code>; with it as the context parent, run `code`. */
    default public HEADER _header(Runnable code){ return ContextParent.add(new HEADER(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;hr&gt;</code>. */
    public static class HR extends Html4.HR implements Html5.Element<HR, Html4.HR>
    {
    }
    /** Build element <code>&lt;hr&gt;</code>. */
    default public HR _hr(){ return ContextParent.add(new HR()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;html&gt;</code>. */
    public static class HTML extends Html4.HTML implements Html5.ParentElement<HTML, Html4.HTML>
    {
        /** Set attribute <code><b>manifest="</b><i>{value}</i><b>"</b></code>.  */
        public HTML manifest(CharSequence value) { return attr("manifest", value); }

    }
    /** Build element <code>&lt;html&gt;</code>. */
    default public HTML _html(){ return ContextParent.add(new HTML()); }
    /** Build element <code>&lt;html&gt;</code> with children. */
    default public HTML _html(Object... children){ return ContextParent.add(new HTML(), children); }
    /** Build element <code>&lt;html&gt;</code>; with it as the context parent, run `code`. */
    default public HTML _html(Runnable code){ return ContextParent.add(new HTML(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;i&gt;</code>. */
    public static class I extends Html4.I implements Html5.ParentElement<I, Html4.I>
    {
    }
    /** Build element <code>&lt;i&gt;</code>. */
    default public I _i(){ return ContextParent.add(new I()); }
    /** Build element <code>&lt;i&gt;</code> with children. */
    default public I _i(Object... children){ return ContextParent.add(new I(), children); }
    /** Build element <code>&lt;i&gt;</code>; with it as the context parent, run `code`. */
    default public I _i(Runnable code){ return ContextParent.add(new I(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;iframe&gt;</code>. */
    public static class IFRAME extends Html4.IFRAME implements Html5.ParentElement<IFRAME, Html4.IFRAME>
    {
        /** Set boolean attribute <code><b>allowfullscreen</b></code>.  */
        public IFRAME allowfullscreen(boolean value) { return attr("allowfullscreen", value); }

        /** Set attribute <code><b>height="</b><i>{value}</i><b>"</b></code>.  */
        public IFRAME height(int value) { return attr("height", value); }

        /** Set attribute <code><b>height="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute can only be `int` in html5; use method height(int) instead.*/
        public IFRAME height(CharSequence value) { return attr("height", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>.  */
        public IFRAME name(CharSequence value) { return attr("name", value); }

        /** Set attribute <code><b>sandbox="</b><i>{value}</i><b>"</b></code>.  */
        public IFRAME sandbox(CharSequence value) { return attr("sandbox", value); }

        /** Set boolean attribute <code><b>seamless</b></code>.  */
        public IFRAME seamless(boolean value) { return attr("seamless", value); }

        /** Set attribute <code><b>src="</b><i>{value}</i><b>"</b></code>.  */
        public IFRAME src(CharSequence value) { return attr("src", value); }

        /** Set attribute <code><b>srcdoc="</b><i>{value}</i><b>"</b></code>.  */
        public IFRAME srcdoc(CharSequence value) { return attr("srcdoc", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>.  */
        public IFRAME width(int value) { return attr("width", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute can only be `int` in html5; use method width(int) instead.*/
        public IFRAME width(CharSequence value) { return attr("width", value); }

        /** Set attribute <code><b>frameborder="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public IFRAME frameborder(CharSequence value) { return attr("frameborder", value); }

        /** Set attribute <code><b>longdesc="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public IFRAME longdesc(CharSequence value) { return attr("longdesc", value); }

        /** Set attribute <code><b>scrolling="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public IFRAME scrolling(CharSequence value) { return attr("scrolling", value); }

    }
    /** Build element <code>&lt;iframe&gt;</code>. */
    default public IFRAME _iframe(){ return ContextParent.add(new IFRAME()); }
    /** Build element <code>&lt;iframe&gt;</code> with children. */
    default public IFRAME _iframe(Object... children){ return ContextParent.add(new IFRAME(), children); }
    /** Build element <code>&lt;iframe&gt;</code>; with it as the context parent, run `code`. */
    default public IFRAME _iframe(Runnable code){ return ContextParent.add(new IFRAME(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;img&gt;</code>. */
    public static class IMG extends Html4.IMG implements Html5.Element<IMG, Html4.IMG>
    {
        /** Set attribute <code><b>alt="</b><i>{value}</i><b>"</b></code>.  */
        public IMG alt(CharSequence value) { return attr("alt", value); }

        /** Set attribute <code><b>crossorigin="</b><i>{value}</i><b>"</b></code>.  */
        public IMG crossorigin(CharSequence value) { return attr("crossorigin", value); }

        /** Set attribute <code><b>height="</b><i>{value}</i><b>"</b></code>.  */
        public IMG height(int value) { return attr("height", value); }

        /** Set attribute <code><b>height="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute can only be `int` in html5; use method height(int) instead.*/
        public IMG height(CharSequence value) { return attr("height", value); }

        /** Set boolean attribute <code><b>ismap</b></code>.  */
        public IMG ismap(boolean value) { return attr("ismap", value); }

        /** Set attribute <code><b>src="</b><i>{value}</i><b>"</b></code>.  */
        public IMG src(CharSequence value) { return attr("src", value); }

        /** Set attribute <code><b>usemap="</b><i>{value}</i><b>"</b></code>.  */
        public IMG usemap(CharSequence value) { return attr("usemap", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>.  */
        public IMG width(int value) { return attr("width", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute can only be `int` in html5; use method width(int) instead.*/
        public IMG width(CharSequence value) { return attr("width", value); }

        /** Set attribute <code><b>longdesc="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public IMG longdesc(CharSequence value) { return attr("longdesc", value); }

    }
    /** Build element <code>&lt;img&gt;</code>. */
    default public IMG _img(){ return ContextParent.add(new IMG()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;input&gt;</code>. */
    public static class INPUT extends Html4.INPUT implements Html5.Element<INPUT, Html4.INPUT>
    {
        /** Set attribute <code><b>accept="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT accept(CharSequence value) { return attr("accept", value); }

        /** Set attribute <code><b>alt="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT alt(CharSequence value) { return attr("alt", value); }

        /** Set attribute <code><b>autocomplete="</b><i>on/off</i><b>"</b></code>. */
        public INPUT autocomplete(boolean value) { return attr("autocomplete", value?"on":"off"); }

        /** Set boolean attribute <code><b>autofocus</b></code>.  */
        public INPUT autofocus(boolean value) { return attr("autofocus", value); }

        /** Set boolean attribute <code><b>checked</b></code>.  */
        public INPUT checked(boolean value) { return attr("checked", value); }

        /** Set attribute <code><b>dirname="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT dirname(CharSequence value) { return attr("dirname", value); }

        /** Set boolean attribute <code><b>disabled</b></code>.  */
        public INPUT disabled(boolean value) { return attr("disabled", value); }

        /** Set attribute <code><b>form="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT form(CharSequence value) { return attr("form", value); }

        /** Set attribute <code><b>formaction="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT formaction(CharSequence value) { return attr("formaction", value); }

        /** Set attribute <code><b>formenctype="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT formenctype(CharSequence value) { return attr("formenctype", value); }

        /** Set attribute <code><b>formmethod="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT formmethod(CharSequence value) { return attr("formmethod", value); }

        /** Set boolean attribute <code><b>formnovalidate</b></code>.  */
        public INPUT formnovalidate(boolean value) { return attr("formnovalidate", value); }

        /** Set attribute <code><b>formtarget="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT formtarget(CharSequence value) { return attr("formtarget", value); }

        /** Set attribute <code><b>height="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT height(int value) { return attr("height", value); }

        /** Set attribute <code><b>list="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT list(CharSequence value) { return attr("list", value); }

        /** Set attribute <code><b>max="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT max(CharSequence value) { return attr("max", value); }

        /** Set attribute <code><b>maxlength="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT maxlength(int value) { return attr("maxlength", value); }

        /** Set attribute <code><b>min="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT min(CharSequence value) { return attr("min", value); }

        /** Set boolean attribute <code><b>multiple</b></code>.  */
        public INPUT multiple(boolean value) { return attr("multiple", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT name(CharSequence value) { return attr("name", value); }

        /** Set attribute <code><b>pattern="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT pattern(CharSequence value) { return attr("pattern", value); }

        /** Set attribute <code><b>placeholder="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT placeholder(CharSequence value) { return attr("placeholder", value); }

        /** Set boolean attribute <code><b>readonly</b></code>.  */
        public INPUT readonly(boolean value) { return attr("readonly", value); }

        /** Set boolean attribute <code><b>required</b></code>.  */
        public INPUT required(boolean value) { return attr("required", value); }

        /** Set attribute <code><b>size="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT size(int value) { return attr("size", value); }

        /** Set attribute <code><b>src="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT src(CharSequence value) { return attr("src", value); }

        /** Set attribute <code><b>step="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT step(CharSequence value) { return attr("step", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT type(CharSequence value) { return attr("type", value); }

        /** Set attribute <code><b>value="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT value(CharSequence value) { return attr("value", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT width(int value) { return attr("width", value); }

        /** Set attribute <code><b>accesskey="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT accesskey(CharSequence value) { return attr("accesskey", value); }

        /** Set attribute <code><b>tabindex="</b><i>{value}</i><b>"</b></code>.  */
        public INPUT tabindex(int value) { return attr("tabindex", value); }

        /** Set attribute <code><b>usemap="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public INPUT usemap(CharSequence value) { return attr("usemap", value); }

    }
    /** Build element <code>&lt;input&gt;</code>. */
    default public INPUT _input(){ return ContextParent.add(new INPUT()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;ins&gt;</code>. */
    public static class INS extends Html4.INS implements Html5.ParentElement<INS, Html4.INS>
    {
        /** Set attribute <code><b>cite="</b><i>{value}</i><b>"</b></code>.  */
        public INS cite(CharSequence value) { return attr("cite", value); }

        /** Set attribute <code><b>datetime="</b><i>{value}</i><b>"</b></code>.  */
        public INS datetime(CharSequence value) { return attr("datetime", value); }

    }
    /** Build element <code>&lt;ins&gt;</code>. */
    default public INS _ins(){ return ContextParent.add(new INS()); }
    /** Build element <code>&lt;ins&gt;</code> with children. */
    default public INS _ins(Object... children){ return ContextParent.add(new INS(), children); }
    /** Build element <code>&lt;ins&gt;</code>; with it as the context parent, run `code`. */
    default public INS _ins(Runnable code){ return ContextParent.add(new INS(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;kbd&gt;</code>. */
    public static class KBD extends Html4.KBD implements Html5.ParentElement<KBD, Html4.KBD>
    {
    }
    /** Build element <code>&lt;kbd&gt;</code>. */
    default public KBD _kbd(){ return ContextParent.add(new KBD()); }
    /** Build element <code>&lt;kbd&gt;</code> with children. */
    default public KBD _kbd(Object... children){ return ContextParent.add(new KBD(), children); }
    /** Build element <code>&lt;kbd&gt;</code>; with it as the context parent, run `code`. */
    default public KBD _kbd(Runnable code){ return ContextParent.add(new KBD(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;keygen&gt;</code>. */
    public static class KEYGEN extends HtmlElement implements Html5.Element<KEYGEN, KEYGEN>
    {
        /** HtmlElementType for <code>&lt;keygen&gt;</code> (void=true, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("keygen", true, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set boolean attribute <code><b>autofocus</b></code>.  */
        public KEYGEN autofocus(boolean value) { return attr("autofocus", value); }

        /** Set attribute <code><b>challenge="</b><i>{value}</i><b>"</b></code>.  */
        public KEYGEN challenge(CharSequence value) { return attr("challenge", value); }

        /** Set boolean attribute <code><b>disabled</b></code>.  */
        public KEYGEN disabled(boolean value) { return attr("disabled", value); }

        /** Set attribute <code><b>form="</b><i>{value}</i><b>"</b></code>.  */
        public KEYGEN form(CharSequence value) { return attr("form", value); }

        /** Set attribute <code><b>keytype="</b><i>{value}</i><b>"</b></code>.  */
        public KEYGEN keytype(CharSequence value) { return attr("keytype", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>.  */
        public KEYGEN name(CharSequence value) { return attr("name", value); }

    }
    /** Build element <code>&lt;keygen&gt;</code>. */
    default public KEYGEN _keygen(){ return ContextParent.add(new KEYGEN()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;label&gt;</code>. */
    public static class LABEL extends Html4.LABEL implements Html5.ParentElement<LABEL, Html4.LABEL>
    {
        /** Set attribute <code><b>for="</b><i>{value}</i><b>"</b></code>.  */
        public LABEL for_(CharSequence value) { return attr("for", value); }

        /** Set attribute <code><b>form="</b><i>{value}</i><b>"</b></code>.  */
        public LABEL form(CharSequence value) { return attr("form", value); }

        /** Set attribute <code><b>accesskey="</b><i>{value}</i><b>"</b></code>.  */
        public LABEL accesskey(CharSequence value) { return attr("accesskey", value); }

    }
    /** Build element <code>&lt;label&gt;</code>. */
    default public LABEL _label(){ return ContextParent.add(new LABEL()); }
    /** Build element <code>&lt;label&gt;</code> with children. */
    default public LABEL _label(Object... children){ return ContextParent.add(new LABEL(), children); }
    /** Build element <code>&lt;label&gt;</code>; with it as the context parent, run `code`. */
    default public LABEL _label(Runnable code){ return ContextParent.add(new LABEL(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;legend&gt;</code>. */
    public static class LEGEND extends Html4.LEGEND implements Html5.ParentElement<LEGEND, Html4.LEGEND>
    {
        /** Set attribute <code><b>accesskey="</b><i>{value}</i><b>"</b></code>.  */
        public LEGEND accesskey(CharSequence value) { return attr("accesskey", value); }

    }
    /** Build element <code>&lt;legend&gt;</code>. */
    default public LEGEND _legend(){ return ContextParent.add(new LEGEND()); }
    /** Build element <code>&lt;legend&gt;</code> with children. */
    default public LEGEND _legend(Object... children){ return ContextParent.add(new LEGEND(), children); }
    /** Build element <code>&lt;legend&gt;</code>; with it as the context parent, run `code`. */
    default public LEGEND _legend(Runnable code){ return ContextParent.add(new LEGEND(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;li&gt;</code>. */
    public static class LI extends Html4.LI implements Html5.ParentElement<LI, Html4.LI>
    {
        /** Set attribute <code><b>value="</b><i>{value}</i><b>"</b></code>.  */
        public LI value(CharSequence value) { return attr("value", value); }

    }
    /** Build element <code>&lt;li&gt;</code>. */
    default public LI _li(){ return ContextParent.add(new LI()); }
    /** Build element <code>&lt;li&gt;</code> with children. */
    default public LI _li(Object... children){ return ContextParent.add(new LI(), children); }
    /** Build element <code>&lt;li&gt;</code>; with it as the context parent, run `code`. */
    default public LI _li(Runnable code){ return ContextParent.add(new LI(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;link&gt;</code>. */
    public static class LINK extends Html4.LINK implements Html5.Element<LINK, Html4.LINK>
    {
        /** Set attribute <code><b>crossorigin="</b><i>{value}</i><b>"</b></code>.  */
        public LINK crossorigin(CharSequence value) { return attr("crossorigin", value); }

        /** Set attribute <code><b>href="</b><i>{value}</i><b>"</b></code>.  */
        public LINK href(CharSequence value) { return attr("href", value); }

        /** Set attribute <code><b>hreflang="</b><i>{value}</i><b>"</b></code>.  */
        public LINK hreflang(CharSequence value) { return attr("hreflang", value); }

        /** Set attribute <code><b>media="</b><i>{value}</i><b>"</b></code>.  */
        public LINK media(CharSequence value) { return attr("media", value); }

        /** Set attribute <code><b>rel="</b><i>{value}</i><b>"</b></code>.  */
        public LINK rel(CharSequence value) { return attr("rel", value); }

        /** Set attribute <code><b>sizes="</b><i>{value}</i><b>"</b></code>.  */
        public LINK sizes(CharSequence value) { return attr("sizes", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>.  */
        public LINK type(CharSequence value) { return attr("type", value); }

        /** Set attribute <code><b>charset="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public LINK charset(CharSequence value) { return attr("charset", value); }

        /** Set attribute <code><b>rev="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public LINK rev(CharSequence value) { return attr("rev", value); }

        /** Set attribute <code><b>target="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public LINK target(CharSequence value) { return attr("target", value); }

    }
    /** Build element <code>&lt;link&gt;</code>. */
    default public LINK _link(){ return ContextParent.add(new LINK()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;main&gt;</code>. */
    public static class MAIN extends HtmlParentElement implements Html5.ParentElement<MAIN, MAIN>
    {
        /** HtmlElementType for <code>&lt;main&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("main", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;main&gt;</code>. */
    default public MAIN _main(){ return ContextParent.add(new MAIN()); }
    /** Build element <code>&lt;main&gt;</code> with children. */
    default public MAIN _main(Object... children){ return ContextParent.add(new MAIN(), children); }
    /** Build element <code>&lt;main&gt;</code>; with it as the context parent, run `code`. */
    default public MAIN _main(Runnable code){ return ContextParent.add(new MAIN(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;map&gt;</code>. */
    public static class MAP extends Html4.MAP implements Html5.ParentElement<MAP, Html4.MAP>
    {
        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>.  */
        public MAP name(CharSequence value) { return attr("name", value); }

    }
    /** Build element <code>&lt;map&gt;</code>. */
    default public MAP _map(){ return ContextParent.add(new MAP()); }
    /** Build element <code>&lt;map&gt;</code> with children. */
    default public MAP _map(Object... children){ return ContextParent.add(new MAP(), children); }
    /** Build element <code>&lt;map&gt;</code>; with it as the context parent, run `code`. */
    default public MAP _map(Runnable code){ return ContextParent.add(new MAP(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;mark&gt;</code>. */
    public static class MARK extends HtmlParentElement implements Html5.ParentElement<MARK, MARK>
    {
        /** HtmlElementType for <code>&lt;mark&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("mark", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;mark&gt;</code>. */
    default public MARK _mark(){ return ContextParent.add(new MARK()); }
    /** Build element <code>&lt;mark&gt;</code> with children. */
    default public MARK _mark(Object... children){ return ContextParent.add(new MARK(), children); }
    /** Build element <code>&lt;mark&gt;</code>; with it as the context parent, run `code`. */
    default public MARK _mark(Runnable code){ return ContextParent.add(new MARK(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;menu&gt;</code>. */
    public static class MENU extends HtmlParentElement implements Html5.ParentElement<MENU, MENU>
    {
        /** HtmlElementType for <code>&lt;menu&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("menu", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>label="</b><i>{value}</i><b>"</b></code>.  */
        public MENU label(CharSequence value) { return attr("label", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>.  */
        public MENU type(CharSequence value) { return attr("type", value); }

    }
    /** Build element <code>&lt;menu&gt;</code>. */
    default public MENU _menu(){ return ContextParent.add(new MENU()); }
    /** Build element <code>&lt;menu&gt;</code> with children. */
    default public MENU _menu(Object... children){ return ContextParent.add(new MENU(), children); }
    /** Build element <code>&lt;menu&gt;</code>; with it as the context parent, run `code`. */
    default public MENU _menu(Runnable code){ return ContextParent.add(new MENU(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;menuitem&gt;</code>. */
    public static class MENUITEM extends HtmlElement implements Html5.Element<MENUITEM, MENUITEM>
    {
        /** HtmlElementType for <code>&lt;menuitem&gt;</code> (void=true, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("menuitem", true, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set boolean attribute <code><b>checked</b></code>.  */
        public MENUITEM checked(boolean value) { return attr("checked", value); }

        /** Set attribute <code><b>command="</b><i>{value}</i><b>"</b></code>.  */
        public MENUITEM command(CharSequence value) { return attr("command", value); }

        /** Set boolean attribute <code><b>default</b></code>.  */
        public MENUITEM default_(boolean value) { return attr("default", value); }

        /** Set boolean attribute <code><b>disabled</b></code>.  */
        public MENUITEM disabled(boolean value) { return attr("disabled", value); }

        /** Set attribute <code><b>icon="</b><i>{value}</i><b>"</b></code>.  */
        public MENUITEM icon(CharSequence value) { return attr("icon", value); }

        /** Set attribute <code><b>label="</b><i>{value}</i><b>"</b></code>.  */
        public MENUITEM label(CharSequence value) { return attr("label", value); }

        /** Set attribute <code><b>radiogroup="</b><i>{value}</i><b>"</b></code>.  */
        public MENUITEM radiogroup(CharSequence value) { return attr("radiogroup", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>.  */
        public MENUITEM type(CharSequence value) { return attr("type", value); }

    }
    /** Build element <code>&lt;menuitem&gt;</code>. */
    default public MENUITEM _menuitem(){ return ContextParent.add(new MENUITEM()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;meta&gt;</code>. */
    public static class META extends Html4.META implements Html5.Element<META, Html4.META>
    {
        /** Set attribute <code><b>charset="</b><i>{value}</i><b>"</b></code>.  */
        public META charset(CharSequence value) { return attr("charset", value); }

        /** Set attribute <code><b>content="</b><i>{value}</i><b>"</b></code>.  */
        public META content(CharSequence value) { return attr("content", value); }

        /** Set attribute <code><b>http-equiv="</b><i>{value}</i><b>"</b></code>.  */
        public META http_equiv(CharSequence value) { return attr("http-equiv", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>.  */
        public META name(CharSequence value) { return attr("name", value); }

        /** Set attribute <code><b>scheme="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public META scheme(CharSequence value) { return attr("scheme", value); }

    }
    /** Build element <code>&lt;meta&gt;</code>. */
    default public META _meta(){ return ContextParent.add(new META()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;meter&gt;</code>. */
    public static class METER extends HtmlParentElement implements Html5.ParentElement<METER, METER>
    {
        /** HtmlElementType for <code>&lt;meter&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("meter", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>high="</b><i>{value}</i><b>"</b></code>.  */
        public METER high(CharSequence value) { return attr("high", value); }

        /** Set attribute <code><b>low="</b><i>{value}</i><b>"</b></code>.  */
        public METER low(CharSequence value) { return attr("low", value); }

        /** Set attribute <code><b>max="</b><i>{value}</i><b>"</b></code>.  */
        public METER max(CharSequence value) { return attr("max", value); }

        /** Set attribute <code><b>min="</b><i>{value}</i><b>"</b></code>.  */
        public METER min(CharSequence value) { return attr("min", value); }

        /** Set attribute <code><b>optimum="</b><i>{value}</i><b>"</b></code>.  */
        public METER optimum(CharSequence value) { return attr("optimum", value); }

        /** Set attribute <code><b>value="</b><i>{value}</i><b>"</b></code>.  */
        public METER value(CharSequence value) { return attr("value", value); }

    }
    /** Build element <code>&lt;meter&gt;</code>. */
    default public METER _meter(){ return ContextParent.add(new METER()); }
    /** Build element <code>&lt;meter&gt;</code> with children. */
    default public METER _meter(Object... children){ return ContextParent.add(new METER(), children); }
    /** Build element <code>&lt;meter&gt;</code>; with it as the context parent, run `code`. */
    default public METER _meter(Runnable code){ return ContextParent.add(new METER(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;nav&gt;</code>. */
    public static class NAV extends HtmlParentElement implements Html5.ParentElement<NAV, NAV>
    {
        /** HtmlElementType for <code>&lt;nav&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("nav", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;nav&gt;</code>. */
    default public NAV _nav(){ return ContextParent.add(new NAV()); }
    /** Build element <code>&lt;nav&gt;</code> with children. */
    default public NAV _nav(Object... children){ return ContextParent.add(new NAV(), children); }
    /** Build element <code>&lt;nav&gt;</code>; with it as the context parent, run `code`. */
    default public NAV _nav(Runnable code){ return ContextParent.add(new NAV(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;noscript&gt;</code>. */
    public static class NOSCRIPT extends Html4.NOSCRIPT implements Html5.ParentElement<NOSCRIPT, Html4.NOSCRIPT>
    {
    }
    /** Build element <code>&lt;noscript&gt;</code>. */
    default public NOSCRIPT _noscript(){ return ContextParent.add(new NOSCRIPT()); }
    /** Build element <code>&lt;noscript&gt;</code> with children. */
    default public NOSCRIPT _noscript(Object... children){ return ContextParent.add(new NOSCRIPT(), children); }
    /** Build element <code>&lt;noscript&gt;</code>; with it as the context parent, run `code`. */
    default public NOSCRIPT _noscript(Runnable code){ return ContextParent.add(new NOSCRIPT(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;object&gt;</code>. */
    public static class OBJECT extends Html4.OBJECT implements Html5.ParentElement<OBJECT, Html4.OBJECT>
    {
        /** Set attribute <code><b>data="</b><i>{value}</i><b>"</b></code>.  */
        public OBJECT data(CharSequence value) { return attr("data", value); }

        /** Set attribute <code><b>form="</b><i>{value}</i><b>"</b></code>.  */
        public OBJECT form(CharSequence value) { return attr("form", value); }

        /** Set attribute <code><b>height="</b><i>{value}</i><b>"</b></code>.  */
        public OBJECT height(int value) { return attr("height", value); }

        /** Set attribute <code><b>height="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute can only be `int` in html5; use method height(int) instead.*/
        public OBJECT height(CharSequence value) { return attr("height", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>.  */
        public OBJECT name(CharSequence value) { return attr("name", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>.  */
        public OBJECT type(CharSequence value) { return attr("type", value); }

        /** Set boolean attribute <code><b>typemustmatch</b></code>.  */
        public OBJECT typemustmatch(boolean value) { return attr("typemustmatch", value); }

        /** Set attribute <code><b>usemap="</b><i>{value}</i><b>"</b></code>.  */
        public OBJECT usemap(CharSequence value) { return attr("usemap", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>.  */
        public OBJECT width(int value) { return attr("width", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute can only be `int` in html5; use method width(int) instead.*/
        public OBJECT width(CharSequence value) { return attr("width", value); }

        /** Set attribute <code><b>archive="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public OBJECT archive(CharSequence value) { return attr("archive", value); }

        /** Set attribute <code><b>classid="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public OBJECT classid(CharSequence value) { return attr("classid", value); }

        /** Set attribute <code><b>codebase="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public OBJECT codebase(CharSequence value) { return attr("codebase", value); }

        /** Set attribute <code><b>codetype="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public OBJECT codetype(CharSequence value) { return attr("codetype", value); }

        /** Set boolean attribute <code><b>declare</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public OBJECT declare(boolean value) { return attr("declare", value); }

        /** Set attribute <code><b>standby="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public OBJECT standby(CharSequence value) { return attr("standby", value); }

        /** Set attribute <code><b>tabindex="</b><i>{value}</i><b>"</b></code>.  */
        public OBJECT tabindex(int value) { return attr("tabindex", value); }

    }
    /** Build element <code>&lt;object&gt;</code>. */
    default public OBJECT _object(){ return ContextParent.add(new OBJECT()); }
    /** Build element <code>&lt;object&gt;</code> with children. */
    default public OBJECT _object(Object... children){ return ContextParent.add(new OBJECT(), children); }
    /** Build element <code>&lt;object&gt;</code>; with it as the context parent, run `code`. */
    default public OBJECT _object(Runnable code){ return ContextParent.add(new OBJECT(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;ol&gt;</code>. */
    public static class OL extends Html4.OL implements Html5.ParentElement<OL, Html4.OL>
    {
        /** Set boolean attribute <code><b>reversed</b></code>.  */
        public OL reversed(boolean value) { return attr("reversed", value); }

        /** Set attribute <code><b>start="</b><i>{value}</i><b>"</b></code>.  */
        public OL start(int value) { return attr("start", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>.  */
        public OL type(CharSequence value) { return attr("type", value); }

    }
    /** Build element <code>&lt;ol&gt;</code>. */
    default public OL _ol(){ return ContextParent.add(new OL()); }
    /** Build element <code>&lt;ol&gt;</code> with children. */
    default public OL _ol(Object... children){ return ContextParent.add(new OL(), children); }
    /** Build element <code>&lt;ol&gt;</code>; with it as the context parent, run `code`. */
    default public OL _ol(Runnable code){ return ContextParent.add(new OL(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;optgroup&gt;</code>. */
    public static class OPTGROUP extends Html4.OPTGROUP implements Html5.ParentElement<OPTGROUP, Html4.OPTGROUP>
    {
        /** Set boolean attribute <code><b>disabled</b></code>.  */
        public OPTGROUP disabled(boolean value) { return attr("disabled", value); }

        /** Set attribute <code><b>label="</b><i>{value}</i><b>"</b></code>.  */
        public OPTGROUP label(CharSequence value) { return attr("label", value); }

    }
    /** Build element <code>&lt;optgroup&gt;</code>. */
    default public OPTGROUP _optgroup(){ return ContextParent.add(new OPTGROUP()); }
    /** Build element <code>&lt;optgroup&gt;</code> with children. */
    default public OPTGROUP _optgroup(Object... children){ return ContextParent.add(new OPTGROUP(), children); }
    /** Build element <code>&lt;optgroup&gt;</code>; with it as the context parent, run `code`. */
    default public OPTGROUP _optgroup(Runnable code){ return ContextParent.add(new OPTGROUP(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;option&gt;</code>. */
    public static class OPTION extends Html4.OPTION implements Html5.ParentElement<OPTION, Html4.OPTION>
    {
        /** Set boolean attribute <code><b>disabled</b></code>.  */
        public OPTION disabled(boolean value) { return attr("disabled", value); }

        /** Set attribute <code><b>label="</b><i>{value}</i><b>"</b></code>.  */
        public OPTION label(CharSequence value) { return attr("label", value); }

        /** Set boolean attribute <code><b>selected</b></code>.  */
        public OPTION selected(boolean value) { return attr("selected", value); }

        /** Set attribute <code><b>value="</b><i>{value}</i><b>"</b></code>.  */
        public OPTION value(CharSequence value) { return attr("value", value); }

    }
    /** Build element <code>&lt;option&gt;</code>. */
    default public OPTION _option(){ return ContextParent.add(new OPTION()); }
    /** Build element <code>&lt;option&gt;</code> with children. */
    default public OPTION _option(Object... children){ return ContextParent.add(new OPTION(), children); }
    /** Build element <code>&lt;option&gt;</code>; with it as the context parent, run `code`. */
    default public OPTION _option(Runnable code){ return ContextParent.add(new OPTION(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;output&gt;</code>. */
    public static class OUTPUT extends HtmlParentElement implements Html5.ParentElement<OUTPUT, OUTPUT>
    {
        /** HtmlElementType for <code>&lt;output&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("output", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>for="</b><i>{value}</i><b>"</b></code>.  */
        public OUTPUT for_(CharSequence value) { return attr("for", value); }

        /** Set attribute <code><b>form="</b><i>{value}</i><b>"</b></code>.  */
        public OUTPUT form(CharSequence value) { return attr("form", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>.  */
        public OUTPUT name(CharSequence value) { return attr("name", value); }

    }
    /** Build element <code>&lt;output&gt;</code>. */
    default public OUTPUT _output(){ return ContextParent.add(new OUTPUT()); }
    /** Build element <code>&lt;output&gt;</code> with children. */
    default public OUTPUT _output(Object... children){ return ContextParent.add(new OUTPUT(), children); }
    /** Build element <code>&lt;output&gt;</code>; with it as the context parent, run `code`. */
    default public OUTPUT _output(Runnable code){ return ContextParent.add(new OUTPUT(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;p&gt;</code>. */
    public static class P extends Html4.P implements Html5.ParentElement<P, Html4.P>
    {
    }
    /** Build element <code>&lt;p&gt;</code>. */
    default public P _p(){ return ContextParent.add(new P()); }
    /** Build element <code>&lt;p&gt;</code> with children. */
    default public P _p(Object... children){ return ContextParent.add(new P(), children); }
    /** Build element <code>&lt;p&gt;</code>; with it as the context parent, run `code`. */
    default public P _p(Runnable code){ return ContextParent.add(new P(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;param&gt;</code>. */
    public static class PARAM extends Html4.PARAM implements Html5.Element<PARAM, Html4.PARAM>
    {
        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>.  */
        public PARAM name(CharSequence value) { return attr("name", value); }

        /** Set attribute <code><b>value="</b><i>{value}</i><b>"</b></code>.  */
        public PARAM value(CharSequence value) { return attr("value", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public PARAM type(CharSequence value) { return attr("type", value); }

        /** Set attribute <code><b>valuetype="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public PARAM valuetype(CharSequence value) { return attr("valuetype", value); }

    }
    /** Build element <code>&lt;param&gt;</code>. */
    default public PARAM _param(){ return ContextParent.add(new PARAM()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;pre&gt;</code>. */
    public static class PRE extends Html4.PRE implements Html5.ParentElement<PRE, Html4.PRE>
    {
    }
    /** Build element <code>&lt;pre&gt;</code>. */
    default public PRE _pre(){ return ContextParent.add(new PRE()); }
    /** Build element <code>&lt;pre&gt;</code> with children. */
    default public PRE _pre(Object... children){ return ContextParent.add(new PRE(), children); }
    /** Build element <code>&lt;pre&gt;</code>; with it as the context parent, run `code`. */
    default public PRE _pre(Runnable code){ return ContextParent.add(new PRE(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;progress&gt;</code>. */
    public static class PROGRESS extends HtmlParentElement implements Html5.ParentElement<PROGRESS, PROGRESS>
    {
        /** HtmlElementType for <code>&lt;progress&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("progress", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>max="</b><i>{value}</i><b>"</b></code>.  */
        public PROGRESS max(CharSequence value) { return attr("max", value); }

        /** Set attribute <code><b>value="</b><i>{value}</i><b>"</b></code>.  */
        public PROGRESS value(CharSequence value) { return attr("value", value); }

    }
    /** Build element <code>&lt;progress&gt;</code>. */
    default public PROGRESS _progress(){ return ContextParent.add(new PROGRESS()); }
    /** Build element <code>&lt;progress&gt;</code> with children. */
    default public PROGRESS _progress(Object... children){ return ContextParent.add(new PROGRESS(), children); }
    /** Build element <code>&lt;progress&gt;</code>; with it as the context parent, run `code`. */
    default public PROGRESS _progress(Runnable code){ return ContextParent.add(new PROGRESS(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;q&gt;</code>. */
    public static class Q extends Html4.Q implements Html5.ParentElement<Q, Html4.Q>
    {
        /** Set attribute <code><b>cite="</b><i>{value}</i><b>"</b></code>.  */
        public Q cite(CharSequence value) { return attr("cite", value); }

    }
    /** Build element <code>&lt;q&gt;</code>. */
    default public Q _q(){ return ContextParent.add(new Q()); }
    /** Build element <code>&lt;q&gt;</code> with children. */
    default public Q _q(Object... children){ return ContextParent.add(new Q(), children); }
    /** Build element <code>&lt;q&gt;</code>; with it as the context parent, run `code`. */
    default public Q _q(Runnable code){ return ContextParent.add(new Q(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;rp&gt;</code>. */
    public static class RP extends HtmlParentElement implements Html5.ParentElement<RP, RP>
    {
        /** HtmlElementType for <code>&lt;rp&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("rp", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;rp&gt;</code>. */
    default public RP _rp(){ return ContextParent.add(new RP()); }
    /** Build element <code>&lt;rp&gt;</code> with children. */
    default public RP _rp(Object... children){ return ContextParent.add(new RP(), children); }
    /** Build element <code>&lt;rp&gt;</code>; with it as the context parent, run `code`. */
    default public RP _rp(Runnable code){ return ContextParent.add(new RP(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;rt&gt;</code>. */
    public static class RT extends HtmlParentElement implements Html5.ParentElement<RT, RT>
    {
        /** HtmlElementType for <code>&lt;rt&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("rt", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;rt&gt;</code>. */
    default public RT _rt(){ return ContextParent.add(new RT()); }
    /** Build element <code>&lt;rt&gt;</code> with children. */
    default public RT _rt(Object... children){ return ContextParent.add(new RT(), children); }
    /** Build element <code>&lt;rt&gt;</code>; with it as the context parent, run `code`. */
    default public RT _rt(Runnable code){ return ContextParent.add(new RT(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;ruby&gt;</code>. */
    public static class RUBY extends HtmlParentElement implements Html5.ParentElement<RUBY, RUBY>
    {
        /** HtmlElementType for <code>&lt;ruby&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("ruby", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;ruby&gt;</code>. */
    default public RUBY _ruby(){ return ContextParent.add(new RUBY()); }
    /** Build element <code>&lt;ruby&gt;</code> with children. */
    default public RUBY _ruby(Object... children){ return ContextParent.add(new RUBY(), children); }
    /** Build element <code>&lt;ruby&gt;</code>; with it as the context parent, run `code`. */
    default public RUBY _ruby(Runnable code){ return ContextParent.add(new RUBY(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;s&gt;</code>. */
    public static class S extends HtmlParentElement implements Html5.ParentElement<S, S>
    {
        /** HtmlElementType for <code>&lt;s&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("s", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;s&gt;</code>. */
    default public S _s(){ return ContextParent.add(new S()); }
    /** Build element <code>&lt;s&gt;</code> with children. */
    default public S _s(Object... children){ return ContextParent.add(new S(), children); }
    /** Build element <code>&lt;s&gt;</code>; with it as the context parent, run `code`. */
    default public S _s(Runnable code){ return ContextParent.add(new S(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;samp&gt;</code>. */
    public static class SAMP extends Html4.SAMP implements Html5.ParentElement<SAMP, Html4.SAMP>
    {
    }
    /** Build element <code>&lt;samp&gt;</code>. */
    default public SAMP _samp(){ return ContextParent.add(new SAMP()); }
    /** Build element <code>&lt;samp&gt;</code> with children. */
    default public SAMP _samp(Object... children){ return ContextParent.add(new SAMP(), children); }
    /** Build element <code>&lt;samp&gt;</code>; with it as the context parent, run `code`. */
    default public SAMP _samp(Runnable code){ return ContextParent.add(new SAMP(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;script&gt;</code>. */
    public static class SCRIPT extends Html4.SCRIPT implements Html5.ParentElement<SCRIPT, Html4.SCRIPT>
    {
        /** Set boolean attribute <code><b>async</b></code>.  */
        public SCRIPT async(boolean value) { return attr("async", value); }

        /** Set attribute <code><b>charset="</b><i>{value}</i><b>"</b></code>.  */
        public SCRIPT charset(CharSequence value) { return attr("charset", value); }

        /** Set attribute <code><b>crossorigin="</b><i>{value}</i><b>"</b></code>.  */
        public SCRIPT crossorigin(CharSequence value) { return attr("crossorigin", value); }

        /** Set boolean attribute <code><b>defer</b></code>.  */
        public SCRIPT defer(boolean value) { return attr("defer", value); }

        /** Set attribute <code><b>src="</b><i>{value}</i><b>"</b></code>.  */
        public SCRIPT src(CharSequence value) { return attr("src", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>.  */
        public SCRIPT type(CharSequence value) { return attr("type", value); }

    }
    /** Build element <code>&lt;script&gt;</code>. */
    default public SCRIPT _script(){ return ContextParent.add(new SCRIPT()); }
    /** Build element <code>&lt;script&gt;</code> with children. */
    default public SCRIPT _script(Object... children){ return ContextParent.add(new SCRIPT(), children); }
    /** Build element <code>&lt;script&gt;</code>; with it as the context parent, run `code`. */
    default public SCRIPT _script(Runnable code){ return ContextParent.add(new SCRIPT(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;section&gt;</code>. */
    public static class SECTION extends HtmlParentElement implements Html5.ParentElement<SECTION, SECTION>
    {
        /** HtmlElementType for <code>&lt;section&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("section", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;section&gt;</code>. */
    default public SECTION _section(){ return ContextParent.add(new SECTION()); }
    /** Build element <code>&lt;section&gt;</code> with children. */
    default public SECTION _section(Object... children){ return ContextParent.add(new SECTION(), children); }
    /** Build element <code>&lt;section&gt;</code>; with it as the context parent, run `code`. */
    default public SECTION _section(Runnable code){ return ContextParent.add(new SECTION(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;select&gt;</code>. */
    public static class SELECT extends Html4.SELECT implements Html5.ParentElement<SELECT, Html4.SELECT>
    {
        /** Set boolean attribute <code><b>autofocus</b></code>.  */
        public SELECT autofocus(boolean value) { return attr("autofocus", value); }

        /** Set boolean attribute <code><b>disabled</b></code>.  */
        public SELECT disabled(boolean value) { return attr("disabled", value); }

        /** Set attribute <code><b>form="</b><i>{value}</i><b>"</b></code>.  */
        public SELECT form(CharSequence value) { return attr("form", value); }

        /** Set boolean attribute <code><b>multiple</b></code>.  */
        public SELECT multiple(boolean value) { return attr("multiple", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>.  */
        public SELECT name(CharSequence value) { return attr("name", value); }

        /** Set boolean attribute <code><b>required</b></code>.  */
        public SELECT required(boolean value) { return attr("required", value); }

        /** Set attribute <code><b>size="</b><i>{value}</i><b>"</b></code>.  */
        public SELECT size(int value) { return attr("size", value); }

        /** Set attribute <code><b>tabindex="</b><i>{value}</i><b>"</b></code>.  */
        public SELECT tabindex(int value) { return attr("tabindex", value); }

    }
    /** Build element <code>&lt;select&gt;</code>. */
    default public SELECT _select(){ return ContextParent.add(new SELECT()); }
    /** Build element <code>&lt;select&gt;</code> with children. */
    default public SELECT _select(Object... children){ return ContextParent.add(new SELECT(), children); }
    /** Build element <code>&lt;select&gt;</code>; with it as the context parent, run `code`. */
    default public SELECT _select(Runnable code){ return ContextParent.add(new SELECT(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;small&gt;</code>. */
    public static class SMALL extends Html4.SMALL implements Html5.ParentElement<SMALL, Html4.SMALL>
    {
    }
    /** Build element <code>&lt;small&gt;</code>. */
    default public SMALL _small(){ return ContextParent.add(new SMALL()); }
    /** Build element <code>&lt;small&gt;</code> with children. */
    default public SMALL _small(Object... children){ return ContextParent.add(new SMALL(), children); }
    /** Build element <code>&lt;small&gt;</code>; with it as the context parent, run `code`. */
    default public SMALL _small(Runnable code){ return ContextParent.add(new SMALL(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;source&gt;</code>. */
    public static class SOURCE extends HtmlElement implements Html5.Element<SOURCE, SOURCE>
    {
        /** HtmlElementType for <code>&lt;source&gt;</code> (void=true, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("source", true, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>media="</b><i>{value}</i><b>"</b></code>.  */
        public SOURCE media(CharSequence value) { return attr("media", value); }

        /** Set attribute <code><b>src="</b><i>{value}</i><b>"</b></code>.  */
        public SOURCE src(CharSequence value) { return attr("src", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>.  */
        public SOURCE type(CharSequence value) { return attr("type", value); }

    }
    /** Build element <code>&lt;source&gt;</code>. */
    default public SOURCE _source(){ return ContextParent.add(new SOURCE()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;span&gt;</code>. */
    public static class SPAN extends Html4.SPAN implements Html5.ParentElement<SPAN, Html4.SPAN>
    {
    }
    /** Build element <code>&lt;span&gt;</code>. */
    default public SPAN _span(){ return ContextParent.add(new SPAN()); }
    /** Build element <code>&lt;span&gt;</code> with children. */
    default public SPAN _span(Object... children){ return ContextParent.add(new SPAN(), children); }
    /** Build element <code>&lt;span&gt;</code>; with it as the context parent, run `code`. */
    default public SPAN _span(Runnable code){ return ContextParent.add(new SPAN(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;strong&gt;</code>. */
    public static class STRONG extends Html4.STRONG implements Html5.ParentElement<STRONG, Html4.STRONG>
    {
    }
    /** Build element <code>&lt;strong&gt;</code>. */
    default public STRONG _strong(){ return ContextParent.add(new STRONG()); }
    /** Build element <code>&lt;strong&gt;</code> with children. */
    default public STRONG _strong(Object... children){ return ContextParent.add(new STRONG(), children); }
    /** Build element <code>&lt;strong&gt;</code>; with it as the context parent, run `code`. */
    default public STRONG _strong(Runnable code){ return ContextParent.add(new STRONG(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;style&gt;</code>. */
    public static class STYLE extends Html4.STYLE implements Html5.ParentElement<STYLE, Html4.STYLE>
    {
        /** Set attribute <code><b>media="</b><i>{value}</i><b>"</b></code>.  */
        public STYLE media(CharSequence value) { return attr("media", value); }

        /** Set boolean attribute <code><b>scoped</b></code>.  */
        public STYLE scoped(boolean value) { return attr("scoped", value); }

        /** Set attribute <code><b>type="</b><i>{value}</i><b>"</b></code>.  */
        public STYLE type(CharSequence value) { return attr("type", value); }

    }
    /** Build element <code>&lt;style&gt;</code>. */
    default public STYLE _style(){ return ContextParent.add(new STYLE()); }
    /** Build element <code>&lt;style&gt;</code> with children. */
    default public STYLE _style(Object... children){ return ContextParent.add(new STYLE(), children); }
    /** Build element <code>&lt;style&gt;</code>; with it as the context parent, run `code`. */
    default public STYLE _style(Runnable code){ return ContextParent.add(new STYLE(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;sub&gt;</code>. */
    public static class SUB extends Html4.SUB implements Html5.ParentElement<SUB, Html4.SUB>
    {
    }
    /** Build element <code>&lt;sub&gt;</code>. */
    default public SUB _sub(){ return ContextParent.add(new SUB()); }
    /** Build element <code>&lt;sub&gt;</code> with children. */
    default public SUB _sub(Object... children){ return ContextParent.add(new SUB(), children); }
    /** Build element <code>&lt;sub&gt;</code>; with it as the context parent, run `code`. */
    default public SUB _sub(Runnable code){ return ContextParent.add(new SUB(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;summary&gt;</code>. */
    public static class SUMMARY extends HtmlParentElement implements Html5.ParentElement<SUMMARY, SUMMARY>
    {
        /** HtmlElementType for <code>&lt;summary&gt;</code> (void=false, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("summary", false, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;summary&gt;</code>. */
    default public SUMMARY _summary(){ return ContextParent.add(new SUMMARY()); }
    /** Build element <code>&lt;summary&gt;</code> with children. */
    default public SUMMARY _summary(Object... children){ return ContextParent.add(new SUMMARY(), children); }
    /** Build element <code>&lt;summary&gt;</code>; with it as the context parent, run `code`. */
    default public SUMMARY _summary(Runnable code){ return ContextParent.add(new SUMMARY(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;sup&gt;</code>. */
    public static class SUP extends Html4.SUP implements Html5.ParentElement<SUP, Html4.SUP>
    {
    }
    /** Build element <code>&lt;sup&gt;</code>. */
    default public SUP _sup(){ return ContextParent.add(new SUP()); }
    /** Build element <code>&lt;sup&gt;</code> with children. */
    default public SUP _sup(Object... children){ return ContextParent.add(new SUP(), children); }
    /** Build element <code>&lt;sup&gt;</code>; with it as the context parent, run `code`. */
    default public SUP _sup(Runnable code){ return ContextParent.add(new SUP(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;table&gt;</code>. */
    public static class TABLE extends Html4.TABLE implements Html5.ParentElement<TABLE, Html4.TABLE>
    {
        /** Set attribute <code><b>border="</b><i>{value}</i><b>"</b></code>.  */
        public TABLE border(int value) { return attr("border", value); }

        /** Set attribute <code><b>cellpadding="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TABLE cellpadding(int value) { return attr("cellpadding", value); }

        /** Set attribute <code><b>cellpadding="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TABLE cellpadding(CharSequence value) { return attr("cellpadding", value); }

        /** Set attribute <code><b>cellspacing="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TABLE cellspacing(int value) { return attr("cellspacing", value); }

        /** Set attribute <code><b>cellspacing="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TABLE cellspacing(CharSequence value) { return attr("cellspacing", value); }

        /** Set attribute <code><b>frame="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TABLE frame(CharSequence value) { return attr("frame", value); }

        /** Set attribute <code><b>rules="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TABLE rules(CharSequence value) { return attr("rules", value); }

        /** Set attribute <code><b>summary="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TABLE summary(CharSequence value) { return attr("summary", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TABLE width(int value) { return attr("width", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TABLE width(CharSequence value) { return attr("width", value); }

    }
    /** Build element <code>&lt;table&gt;</code>. */
    default public TABLE _table(){ return ContextParent.add(new TABLE()); }
    /** Build element <code>&lt;table&gt;</code> with children. */
    default public TABLE _table(Object... children){ return ContextParent.add(new TABLE(), children); }
    /** Build element <code>&lt;table&gt;</code>; with it as the context parent, run `code`. */
    default public TABLE _table(Runnable code){ return ContextParent.add(new TABLE(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;tbody&gt;</code>. */
    public static class TBODY extends Html4.TBODY implements Html5.ParentElement<TBODY, Html4.TBODY>
    {
        /** Set attribute <code><b>align="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TBODY align(CharSequence value) { return attr("align", value); }

        /** Set attribute <code><b>char="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TBODY char_(CharSequence value) { return attr("char", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TBODY charoff(int value) { return attr("charoff", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TBODY charoff(CharSequence value) { return attr("charoff", value); }

        /** Set attribute <code><b>valign="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TBODY valign(CharSequence value) { return attr("valign", value); }

    }
    /** Build element <code>&lt;tbody&gt;</code>. */
    default public TBODY _tbody(){ return ContextParent.add(new TBODY()); }
    /** Build element <code>&lt;tbody&gt;</code> with children. */
    default public TBODY _tbody(Object... children){ return ContextParent.add(new TBODY(), children); }
    /** Build element <code>&lt;tbody&gt;</code>; with it as the context parent, run `code`. */
    default public TBODY _tbody(Runnable code){ return ContextParent.add(new TBODY(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;td&gt;</code>. */
    public static class TD extends Html4.TD implements Html5.ParentElement<TD, Html4.TD>
    {
        /** Set attribute <code><b>colspan="</b><i>{value}</i><b>"</b></code>.  */
        public TD colspan(int value) { return attr("colspan", value); }

        /** Set attribute <code><b>headers="</b><i>{value}</i><b>"</b></code>.  */
        public TD headers(CharSequence value) { return attr("headers", value); }

        /** Set attribute <code><b>rowspan="</b><i>{value}</i><b>"</b></code>.  */
        public TD rowspan(int value) { return attr("rowspan", value); }

        /** Set attribute <code><b>abbr="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TD abbr(CharSequence value) { return attr("abbr", value); }

        /** Set attribute <code><b>align="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TD align(CharSequence value) { return attr("align", value); }

        /** Set attribute <code><b>axis="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TD axis(CharSequence value) { return attr("axis", value); }

        /** Set attribute <code><b>char="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TD char_(CharSequence value) { return attr("char", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TD charoff(int value) { return attr("charoff", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TD charoff(CharSequence value) { return attr("charoff", value); }

        /** Set attribute <code><b>scope="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TD scope(CharSequence value) { return attr("scope", value); }

        /** Set attribute <code><b>valign="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TD valign(CharSequence value) { return attr("valign", value); }

    }
    /** Build element <code>&lt;td&gt;</code>. */
    default public TD _td(){ return ContextParent.add(new TD()); }
    /** Build element <code>&lt;td&gt;</code> with children. */
    default public TD _td(Object... children){ return ContextParent.add(new TD(), children); }
    /** Build element <code>&lt;td&gt;</code>; with it as the context parent, run `code`. */
    default public TD _td(Runnable code){ return ContextParent.add(new TD(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;template&gt;</code>. */
    public static class TEMPLATE extends HtmlParentElement implements Html5.ParentElement<TEMPLATE, TEMPLATE>
    {
        /** HtmlElementType for <code>&lt;template&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("template", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;template&gt;</code>. */
    default public TEMPLATE _template(){ return ContextParent.add(new TEMPLATE()); }
    /** Build element <code>&lt;template&gt;</code> with children. */
    default public TEMPLATE _template(Object... children){ return ContextParent.add(new TEMPLATE(), children); }
    /** Build element <code>&lt;template&gt;</code>; with it as the context parent, run `code`. */
    default public TEMPLATE _template(Runnable code){ return ContextParent.add(new TEMPLATE(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;textarea&gt;</code>. */
    public static class TEXTAREA extends Html4.TEXTAREA implements Html5.ParentElement<TEXTAREA, Html4.TEXTAREA>
    {
        /** Set boolean attribute <code><b>autofocus</b></code>.  */
        public TEXTAREA autofocus(boolean value) { return attr("autofocus", value); }

        /** Set attribute <code><b>cols="</b><i>{value}</i><b>"</b></code>.  */
        public TEXTAREA cols(int value) { return attr("cols", value); }

        /** Set attribute <code><b>dirname="</b><i>{value}</i><b>"</b></code>.  */
        public TEXTAREA dirname(CharSequence value) { return attr("dirname", value); }

        /** Set boolean attribute <code><b>disabled</b></code>.  */
        public TEXTAREA disabled(boolean value) { return attr("disabled", value); }

        /** Set attribute <code><b>form="</b><i>{value}</i><b>"</b></code>.  */
        public TEXTAREA form(CharSequence value) { return attr("form", value); }

        /** Set attribute <code><b>maxlength="</b><i>{value}</i><b>"</b></code>.  */
        public TEXTAREA maxlength(int value) { return attr("maxlength", value); }

        /** Set attribute <code><b>name="</b><i>{value}</i><b>"</b></code>.  */
        public TEXTAREA name(CharSequence value) { return attr("name", value); }

        /** Set attribute <code><b>placeholder="</b><i>{value}</i><b>"</b></code>.  */
        public TEXTAREA placeholder(CharSequence value) { return attr("placeholder", value); }

        /** Set boolean attribute <code><b>readonly</b></code>.  */
        public TEXTAREA readonly(boolean value) { return attr("readonly", value); }

        /** Set boolean attribute <code><b>required</b></code>.  */
        public TEXTAREA required(boolean value) { return attr("required", value); }

        /** Set attribute <code><b>rows="</b><i>{value}</i><b>"</b></code>.  */
        public TEXTAREA rows(int value) { return attr("rows", value); }

        /** Set attribute <code><b>wrap="</b><i>{value}</i><b>"</b></code>.  */
        public TEXTAREA wrap(CharSequence value) { return attr("wrap", value); }

        /** Set attribute <code><b>accesskey="</b><i>{value}</i><b>"</b></code>.  */
        public TEXTAREA accesskey(CharSequence value) { return attr("accesskey", value); }

        /** Set attribute <code><b>tabindex="</b><i>{value}</i><b>"</b></code>.  */
        public TEXTAREA tabindex(int value) { return attr("tabindex", value); }

    }
    /** Build element <code>&lt;textarea&gt;</code>. */
    default public TEXTAREA _textarea(){ return ContextParent.add(new TEXTAREA()); }
    /** Build element <code>&lt;textarea&gt;</code> with children. */
    default public TEXTAREA _textarea(Object... children){ return ContextParent.add(new TEXTAREA(), children); }
    /** Build element <code>&lt;textarea&gt;</code>; with it as the context parent, run `code`. */
    default public TEXTAREA _textarea(Runnable code){ return ContextParent.add(new TEXTAREA(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;tfoot&gt;</code>. */
    public static class TFOOT extends Html4.TFOOT implements Html5.ParentElement<TFOOT, Html4.TFOOT>
    {
        /** Set attribute <code><b>align="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TFOOT align(CharSequence value) { return attr("align", value); }

        /** Set attribute <code><b>char="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TFOOT char_(CharSequence value) { return attr("char", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TFOOT charoff(int value) { return attr("charoff", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TFOOT charoff(CharSequence value) { return attr("charoff", value); }

        /** Set attribute <code><b>valign="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TFOOT valign(CharSequence value) { return attr("valign", value); }

    }
    /** Build element <code>&lt;tfoot&gt;</code>. */
    default public TFOOT _tfoot(){ return ContextParent.add(new TFOOT()); }
    /** Build element <code>&lt;tfoot&gt;</code> with children. */
    default public TFOOT _tfoot(Object... children){ return ContextParent.add(new TFOOT(), children); }
    /** Build element <code>&lt;tfoot&gt;</code>; with it as the context parent, run `code`. */
    default public TFOOT _tfoot(Runnable code){ return ContextParent.add(new TFOOT(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;th&gt;</code>. */
    public static class TH extends Html4.TH implements Html5.ParentElement<TH, Html4.TH>
    {
        /** Set attribute <code><b>abbr="</b><i>{value}</i><b>"</b></code>.  */
        public TH abbr(CharSequence value) { return attr("abbr", value); }

        /** Set attribute <code><b>colspan="</b><i>{value}</i><b>"</b></code>.  */
        public TH colspan(int value) { return attr("colspan", value); }

        /** Set attribute <code><b>headers="</b><i>{value}</i><b>"</b></code>.  */
        public TH headers(CharSequence value) { return attr("headers", value); }

        /** Set attribute <code><b>rowspan="</b><i>{value}</i><b>"</b></code>.  */
        public TH rowspan(int value) { return attr("rowspan", value); }

        /** Set attribute <code><b>scope="</b><i>{value}</i><b>"</b></code>.  */
        public TH scope(CharSequence value) { return attr("scope", value); }

        /** Set attribute <code><b>align="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TH align(CharSequence value) { return attr("align", value); }

        /** Set attribute <code><b>axis="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TH axis(CharSequence value) { return attr("axis", value); }

        /** Set attribute <code><b>char="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TH char_(CharSequence value) { return attr("char", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TH charoff(int value) { return attr("charoff", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TH charoff(CharSequence value) { return attr("charoff", value); }

        /** Set attribute <code><b>valign="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TH valign(CharSequence value) { return attr("valign", value); }

    }
    /** Build element <code>&lt;th&gt;</code>. */
    default public TH _th(){ return ContextParent.add(new TH()); }
    /** Build element <code>&lt;th&gt;</code> with children. */
    default public TH _th(Object... children){ return ContextParent.add(new TH(), children); }
    /** Build element <code>&lt;th&gt;</code>; with it as the context parent, run `code`. */
    default public TH _th(Runnable code){ return ContextParent.add(new TH(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;thead&gt;</code>. */
    public static class THEAD extends Html4.THEAD implements Html5.ParentElement<THEAD, Html4.THEAD>
    {
        /** Set attribute <code><b>align="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public THEAD align(CharSequence value) { return attr("align", value); }

        /** Set attribute <code><b>char="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public THEAD char_(CharSequence value) { return attr("char", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public THEAD charoff(int value) { return attr("charoff", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public THEAD charoff(CharSequence value) { return attr("charoff", value); }

        /** Set attribute <code><b>valign="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public THEAD valign(CharSequence value) { return attr("valign", value); }

    }
    /** Build element <code>&lt;thead&gt;</code>. */
    default public THEAD _thead(){ return ContextParent.add(new THEAD()); }
    /** Build element <code>&lt;thead&gt;</code> with children. */
    default public THEAD _thead(Object... children){ return ContextParent.add(new THEAD(), children); }
    /** Build element <code>&lt;thead&gt;</code>; with it as the context parent, run `code`. */
    default public THEAD _thead(Runnable code){ return ContextParent.add(new THEAD(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;time&gt;</code>. */
    public static class TIME extends HtmlParentElement implements Html5.ParentElement<TIME, TIME>
    {
        /** HtmlElementType for <code>&lt;time&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("time", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set attribute <code><b>datetime="</b><i>{value}</i><b>"</b></code>.  */
        public TIME datetime(CharSequence value) { return attr("datetime", value); }

    }
    /** Build element <code>&lt;time&gt;</code>. */
    default public TIME _time(){ return ContextParent.add(new TIME()); }
    /** Build element <code>&lt;time&gt;</code> with children. */
    default public TIME _time(Object... children){ return ContextParent.add(new TIME(), children); }
    /** Build element <code>&lt;time&gt;</code>; with it as the context parent, run `code`. */
    default public TIME _time(Runnable code){ return ContextParent.add(new TIME(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;title&gt;</code>. */
    public static class TITLE extends Html4.TITLE implements Html5.ParentElement<TITLE, Html4.TITLE>
    {
    }
    /** Build element <code>&lt;title&gt;</code>. */
    default public TITLE _title(){ return ContextParent.add(new TITLE()); }
    /** Build element <code>&lt;title&gt;</code> with children. */
    default public TITLE _title(Object... children){ return ContextParent.add(new TITLE(), children); }
    /** Build element <code>&lt;title&gt;</code>; with it as the context parent, run `code`. */
    default public TITLE _title(Runnable code){ return ContextParent.add(new TITLE(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;tr&gt;</code>. */
    public static class TR extends Html4.TR implements Html5.ParentElement<TR, Html4.TR>
    {
        /** Set attribute <code><b>align="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TR align(CharSequence value) { return attr("align", value); }

        /** Set attribute <code><b>char="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TR char_(CharSequence value) { return attr("char", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TR charoff(int value) { return attr("charoff", value); }

        /** Set attribute <code><b>charoff="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TR charoff(CharSequence value) { return attr("charoff", value); }

        /** Set attribute <code><b>valign="</b><i>{value}</i><b>"</b></code>.
         * @deprecated This attribute is deprecated in html5. */
        public TR valign(CharSequence value) { return attr("valign", value); }

    }
    /** Build element <code>&lt;tr&gt;</code>. */
    default public TR _tr(){ return ContextParent.add(new TR()); }
    /** Build element <code>&lt;tr&gt;</code> with children. */
    default public TR _tr(Object... children){ return ContextParent.add(new TR(), children); }
    /** Build element <code>&lt;tr&gt;</code>; with it as the context parent, run `code`. */
    default public TR _tr(Runnable code){ return ContextParent.add(new TR(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;track&gt;</code>. */
    public static class TRACK extends HtmlElement implements Html5.Element<TRACK, TRACK>
    {
        /** HtmlElementType for <code>&lt;track&gt;</code> (void=true, block=true, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("track", true, true, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set boolean attribute <code><b>default</b></code>.  */
        public TRACK default_(boolean value) { return attr("default", value); }

        /** Set attribute <code><b>kind="</b><i>{value}</i><b>"</b></code>.  */
        public TRACK kind(CharSequence value) { return attr("kind", value); }

        /** Set attribute <code><b>label="</b><i>{value}</i><b>"</b></code>.  */
        public TRACK label(CharSequence value) { return attr("label", value); }

        /** Set attribute <code><b>src="</b><i>{value}</i><b>"</b></code>.  */
        public TRACK src(CharSequence value) { return attr("src", value); }

        /** Set attribute <code><b>srclang="</b><i>{value}</i><b>"</b></code>.  */
        public TRACK srclang(CharSequence value) { return attr("srclang", value); }

    }
    /** Build element <code>&lt;track&gt;</code>. */
    default public TRACK _track(){ return ContextParent.add(new TRACK()); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;u&gt;</code>. */
    public static class U extends HtmlParentElement implements Html5.ParentElement<U, U>
    {
        /** HtmlElementType for <code>&lt;u&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("u", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;u&gt;</code>. */
    default public U _u(){ return ContextParent.add(new U()); }
    /** Build element <code>&lt;u&gt;</code> with children. */
    default public U _u(Object... children){ return ContextParent.add(new U(), children); }
    /** Build element <code>&lt;u&gt;</code>; with it as the context parent, run `code`. */
    default public U _u(Runnable code){ return ContextParent.add(new U(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;ul&gt;</code>. */
    public static class UL extends Html4.UL implements Html5.ParentElement<UL, Html4.UL>
    {
    }
    /** Build element <code>&lt;ul&gt;</code>. */
    default public UL _ul(){ return ContextParent.add(new UL()); }
    /** Build element <code>&lt;ul&gt;</code> with children. */
    default public UL _ul(Object... children){ return ContextParent.add(new UL(), children); }
    /** Build element <code>&lt;ul&gt;</code>; with it as the context parent, run `code`. */
    default public UL _ul(Runnable code){ return ContextParent.add(new UL(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;var&gt;</code>. */
    public static class VAR extends Html4.VAR implements Html5.ParentElement<VAR, Html4.VAR>
    {
    }
    /** Build element <code>&lt;var&gt;</code>. */
    default public VAR _var(){ return ContextParent.add(new VAR()); }
    /** Build element <code>&lt;var&gt;</code> with children. */
    default public VAR _var(Object... children){ return ContextParent.add(new VAR(), children); }
    /** Build element <code>&lt;var&gt;</code>; with it as the context parent, run `code`. */
    default public VAR _var(Runnable code){ return ContextParent.add(new VAR(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;video&gt;</code>. */
    public static class VIDEO extends HtmlParentElement implements Html5.ParentElement<VIDEO, VIDEO>
    {
        /** HtmlElementType for <code>&lt;video&gt;</code> (void=false, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("video", false, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

        /** Set boolean attribute <code><b>autoplay</b></code>.  */
        public VIDEO autoplay(boolean value) { return attr("autoplay", value); }

        /** Set boolean attribute <code><b>controls</b></code>.  */
        public VIDEO controls(boolean value) { return attr("controls", value); }

        /** Set attribute <code><b>crossorigin="</b><i>{value}</i><b>"</b></code>.  */
        public VIDEO crossorigin(CharSequence value) { return attr("crossorigin", value); }

        /** Set attribute <code><b>height="</b><i>{value}</i><b>"</b></code>.  */
        public VIDEO height(int value) { return attr("height", value); }

        /** Set boolean attribute <code><b>loop</b></code>.  */
        public VIDEO loop(boolean value) { return attr("loop", value); }

        /** Set attribute <code><b>mediagroup="</b><i>{value}</i><b>"</b></code>.  */
        public VIDEO mediagroup(CharSequence value) { return attr("mediagroup", value); }

        /** Set boolean attribute <code><b>muted</b></code>.  */
        public VIDEO muted(boolean value) { return attr("muted", value); }

        /** Set attribute <code><b>poster="</b><i>{value}</i><b>"</b></code>.  */
        public VIDEO poster(CharSequence value) { return attr("poster", value); }

        /** Set attribute <code><b>preload="</b><i>{value}</i><b>"</b></code>.  */
        public VIDEO preload(CharSequence value) { return attr("preload", value); }

        /** Set attribute <code><b>src="</b><i>{value}</i><b>"</b></code>.  */
        public VIDEO src(CharSequence value) { return attr("src", value); }

        /** Set attribute <code><b>width="</b><i>{value}</i><b>"</b></code>.  */
        public VIDEO width(int value) { return attr("width", value); }

    }
    /** Build element <code>&lt;video&gt;</code>. */
    default public VIDEO _video(){ return ContextParent.add(new VIDEO()); }
    /** Build element <code>&lt;video&gt;</code> with children. */
    default public VIDEO _video(Object... children){ return ContextParent.add(new VIDEO(), children); }
    /** Build element <code>&lt;video&gt;</code>; with it as the context parent, run `code`. */
    default public VIDEO _video(Runnable code){ return ContextParent.add(new VIDEO(), code); }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Html5 element <code>&lt;wbr&gt;</code>. */
    public static class WBR extends HtmlElement implements Html5.Element<WBR, WBR>
    {
        /** HtmlElementType for <code>&lt;wbr&gt;</code> (void=true, block=false, pre=false). */
        public static final HtmlElementType TYPE = new HtmlElementType("wbr", true, false, false);

        @Override public HtmlElementType getElementType() { return TYPE; }

    }
    /** Build element <code>&lt;wbr&gt;</code>. */
    default public WBR _wbr(){ return ContextParent.add(new WBR()); }

}
