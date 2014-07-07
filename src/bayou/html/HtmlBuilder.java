package bayou.html;

/**
 * Supertype of html builders.
 * <p>
 *     An html builder interface/class (e.g. {@link Html4})
 *     contains piece types (e.g. {@link Html4.DIV})
 *     and builder methods (e.g. {@link Html4#_div()}).
 *     Application code usually accesses them through inheritance,
 *     for example,
 * </p>
 * <pre>
 *     class MyDoc extends Html4Doc//which implements Html4
 *     {{
 *         _body( ()-&gt;
 *         {
 *             DIV div = _div();
 *         });
 *     }}
 * </pre>
 * <p>
 *     A piece type (e.g. {@link Html4.DIV}) describes a specific type of html pieces.
 *     It contains state changing methods (e.g. to add attributes/children).
 * </p>
 * <p>
 *     A builder method (e.g. {@link Html4#_div()})
 *     creates a piece and add it to the {@link ContextParent context parent}.
 *     A builder method should be an instance method.
 *     Its name should start with an underscore (to visually distinguish it from "normal" methods).
 * </p>
 * <p>
 *     This interface (HtmlBuilder) contains the class {@link ContextParent ContextParent},
 *     and some basic builder methods like {@link #_text(Object...)}.
 * </p>
 */

// underscore prefix: _div()
// it may be a little difficult to type; but it's more important that they are easy to read
//
// subtype may provide a singleton, e.g. Html4.html4, to access instance methods without inheritance.

public interface HtmlBuilder
{

    /**
     * A utility class dealing with context parents.
     * <p>
     *     The context parent is an {@link HtmlParent} which builder methods
     *     add pieces to. In the following example
     * </p>
     * <pre>
         _form( ()-&gt;{
            _input(); // add an &lt;input&gt; to the context parent
         });
     * </pre>
     * <p>
     *     when the _input() methods is invoked, the context parent is the &lt;form&gt; element.
     *     The _input() method creates an &lt;input&gt; element and adds it
     *     to the &lt;form&gt; context parent.
     * </p>
     * <p>
     *     The context parent is a thread local value, initially null.
     *     For conciseness, we consider null a valid context parent;
     *     operations on a null context parent has no effect.
     *     For example, adding children to a null context parent is a no-op.
     * </p>
     * <p>
     *     This class contains only static methods that builder methods use
     *     to operate with context parents.
     * </p>
     */
    public static final class ContextParent
    {
        private ContextParent(){}

        static final ThreadLocal<HtmlParent> threadLocal = new ThreadLocal<>();

        // two essential methods: get/set context parent ======================================

        /**
         * Get the context parent; may return null.
         */
        // we don't say "the current context parent". simply "the context parent"
        public static HtmlParent get()
        {
            return threadLocal.get();
            // though this method is invoked very frequently, ThreadLocal is amazingly fast.
            // I tried to replace it with a simple static field (works only for single thread)
            // and there's no difference in performance! ??
        }

        /**
         * Run `code` with `parent` as the context parent.
         * <p>
         *     When `code` is executed, it'll see `ContextParent.get()==parent`, for example
         * </p>
         * <pre>
             DIV div = new DIV();
             ContextParent.with(div, ()-&gt;
             {
                 assert ContextParent.get()==div;
             });
         * </pre>
         * <p>
         *     `parent` can be null, which might be useful in some cases.
         * </p>
         * <p>
         *     Example Usage:
         * </p>
         * <pre>
            class Foo implements HtmlParent
            {
                public Foo add(Runnable code)
                {
                    ContextParent.with(this, code);
                    return this;
                }
                ...
         * </pre>
         */
        // it may seem better to have the parent as the arg to the code,
        // so that lambda is shorter, _div(p->...), and code has easy access to the parent.
        // but unfortunately, lambda parameter name cannot clash with local var names,
        // so that will force users to pick different names which can become frustrating.
        //     _div(p1->{ ... _div(p2->{...}) ... } )
        public static void with(HtmlParent parent, Runnable code)
        {
            HtmlParent prevParent = threadLocal.get();
            threadLocal.set(parent);
            try
            {
                code.run();
            }
            finally
            {
                threadLocal.set(prevParent);
                // if prevParent==null, ideally we should threadLocal.remove(). no big deal.
                // set(null) to keep the entry; likely we'll need the entry again.
            }
        }

        // convenience methods used by builder methods.
        // all these method can be implemented by anyone based on get()/with()
        // our impls have some internal optimizations.

        /**
         * Add `piece` to the context parent.
         * <p>
         *     Example Usage:
         * </p>
         * <pre>
         *     DIV _div()
         *     {
         *         return ContextParent.add(new DIV());
         *     }
         * </pre>
         * <p>
         *     `piece` cannot be null.
         * </p>
         * @return `piece`
         */
        public static <P extends HtmlPiece> P add(P piece)
        {
            HtmlParent context = get();
            if(context!=null)
            {
                context.addChild(piece);
            }
            return piece;
        }

        /**
         * Detach child pieces from the context parent.
         * <p>
         *     <em>Purpose of this method:</em> In the following example
         * </p>
         * <pre>
         *     _p( "abc", _img() );
         * </pre>
         * <p>
         *     per Java's evaluation order, the code is executed as
         * </p>
         * <pre>
         *     IMG img = _img();
         *     _p( "abc", img );
         * </pre>
         * <p>
         *     But _img() adds a &lt;img&gt; to the context parent, so wouldn't the above code
         *     build a tree like
         * </p>
         * <pre>
         *     &lt;img&gt;
         *     &lt;p&gt;
         *         abc&lt;img&gt;
         *     &lt;/p&gt;
         * </pre>
         * <p>
         *     which is clearly not intended by the original code?
         * </p>
         * <p>
         *     Our solution: when adding children to a piece, if they
         *     are at the tail of the context parent's child list, they are detached from
         *     the context parent first, because it is most likely that they were
         *     intended for the piece, not for the context parent.
         * </p>
         * <p>
         *     The specification of `detach(args)` in pseudo code:
         * </p>
         * <pre>
         *     List children = children of the context parent
         *     while children is not empty
         *         find the max i that args[i]==children.getLast()
         *         if not found, break
         *         children.removeLast()
         *         args = {args[0], ..., args[i-1]} // drop args[i] and the rest
         * </pre>
         * <p>
         *     It is a little complicated since `args` may contain non-HtmlPiece objects.
         *     <!-- or contain pieces created elsewhere. -->
         *     <!-- e.g.  div( "text", p(), aCachedPiece, form() ) -->
         *     <!-- args may contain null -->
         * </p>
         * <p>
         *     This method simply invokes {@link HtmlParent#detachChildren(Object...)}
         *     which actually implements the specification.
         * </p>
         */
        // internally we don't call this method. publish it to explain the concept.
        // customer builders might also find it useful.
        public static void detach(Object... args)
        {
            HtmlParent context = get();
            if(context!=null)
            {
                context.detachChildren(args);
            }
        }

        /**
         * Add `piece` to the context parent, and add `children` to `piece`.
         * <p>
         *     The `children` are first {@link #detach(Object...) detached} from the context parent.
         *     This method is equivalent to
         * </p>
         * <pre>
         *     ContextParent.detach(children);
         *     piece.addChildren(children);
         *     ContextParent.add(piece);
         * </pre>
         * <p>
         *     Example Usage:
         * </p>
         * <pre>
         *     DIV _div(Object... children)
         *     {
         *         return ContextParent.add(new DIV(), children);
         *     }
         * </pre>
         * @return `piece`
         */
        public static <P extends HtmlParent> P add(P piece, Object... children)
        {
            HtmlParent context = get();
            if(context!=null)
            {
                context.detachChildren(children);
                context.addChild(piece);
            }
            piece.addChildren(children);
            return piece;
        }

        /**
         * Add `piece` to the context parent, and run `code` with `piece` as the context parent.
         * <p>
         *     This method is equivalent to
         * </p>
         * <pre>
         *     ContextParent.add(piece);
         *     ContextParent.with(piece, code);
         * </pre>
         * <p>
         *     Example Usage:
         * </p>
         * <pre>
         *     DIV _div(Runnable code)
         *     {
         *         return ContextParent.add(new DIV(), code);
         *     }
         * </pre>
         * @return `piece`
         */
        public static <P extends HtmlParent> P add(P piece, Runnable code)
        {
            HtmlParent prevParent = threadLocal.get();
            if(prevParent!=null)
                prevParent.addChild(piece);
            threadLocal.set(piece);
            try
            {
                code.run();
            }
            finally
            {
                threadLocal.set(prevParent);
                // if prevParent==null, ideally we should threadLocal.remove(). no big deal.
                // set(null) to keep the entry; likely we'll need the entry again.
            }
            return piece;
        }

        /**
         * Detach `children` from the context parent, then add `children` to `piece`.
         * <p>
         *     This method is equivalent to
         * </p>
         * <pre>
         *     ContextParent.detach(children);
         *     piece.addChildren(children);
         * </pre>
         * <p>
         *     Note that this method does not add `piece` to the context parent -
         *     presumably it was already added.
         * </p>
         * <p>
         *     Example Usage:
         * </p>
         * <pre>
            class Foo implements HtmlParent
            {
                public Foo add(Object... children)
                {
                    ContextParent.detachThenAddTo(children, this);
                    return this;
                }
                ...
         * </pre>
         */
        public static void detachThenAddTo(Object[] children, HtmlParent piece)
        {
            HtmlParent context = get();
            if(context!=null)
            {
                context.detachChildren(children);
            }
            piece.addChildren(children);
        }

    }

    /**
     * Add textual content.
     * <p>
     *     This method is equivalent to `ContextParent.add(new HtmlText(args))`.
     * </p>
     * <p>
     *     Example:
     * </p>
     * <pre>
     *     _text("there are ", 5, " apples.");
     * </pre>
     */
    default public HtmlText _text(Object... args)
    {
        return ContextParent.add(new HtmlText(args));
    }

    /**
     * Add formatted textual content.
     * <p>
     *     This method is equivalent to `_text( String.format(format, args) )`.
     *     See {@link String#format(String, Object...) String.format(format, args)}.
     * </p>
     * <p>
     *     Example:
     * </p>
     * <pre>
     *     _textf("there are %d apples.", 5);
     * </pre>
     */
    default public HtmlText _textf(String format, Object... args)
    {
        return ContextParent.add(new HtmlText(String.format(format, args)));
    }

    /**
     * Add an html comment.
     * <p>
     *     This method is equivalent to `ContextParent.add(new HtmlComment(args))`.
     * </p>
     * <p>
     *     Example:
     * </p>
     * <pre>
     *     _comment("begin article #", 5);
     *     //   &lt;!-- begin article #5 --&gt;
     * </pre>
     */
    default public HtmlComment _comment(Object... args)
    {
        return ContextParent.add(new HtmlComment(args));
    }

    /**
     * Add raw content.
     * <p>
     *     This method is equivalent to `ContextParent.add(new HtmlRaw(args))`.
     * </p>
     * <p>
     *     Example:
     * </p>
     * <pre>
     *     _raw("&lt;span&gt;abc&lt;/span&gt;");
     * </pre>
     * <p>
     *     <b>Raw content is dangerous,</b> since no escaping is done.
     *     If it contains strings from end user inputs, make sure they are sanitized or escaped first.
     * </p>
     */
    default public HtmlRaw _raw(Object... args)
    {
        return ContextParent.add(new HtmlRaw(args));
    }


    /**
     * Add a new line ("\n").
     * <p>
     *     This method is equivalent to `ContextParent.add(HtmlNewLine.instance)`.
     * </p>
     * <p>
     *     A new line can be inserted between two inline elements for formatting purpose
     *     (if it's ok to insert spaces between them).
     *     For example
     * </p>
     * <pre>
         _div( _input(), _newline(), _input() )
         //    &lt;div&gt;
         //        &lt;input&gt;
         //        &lt;input&gt;
         //    &lt;/div&gt;
     * </pre>
     * <p>
     *     Note that a new line corresponds to a "\n", not a "&lt;br&gt;".
     * </p>
     * <p>
     *     This method is usually not needed before/after a block element.
     * </p>
     */
    default public HtmlNewLine _newline()
    {
        return ContextParent.add(HtmlNewLine.instance);
        // we are using a shared instance here.
        // it should not cause problems for ContextParent.detach().
    }


}
