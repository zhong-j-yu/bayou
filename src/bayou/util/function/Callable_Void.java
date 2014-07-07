package bayou.util.function;

import java.util.concurrent.Callable;

/**
 * An adapter of {@link Callable Callable&lt;Void&gt;}
 * for void-returning lambda expressions and method references.
 *
 * <p>
 *     <em>Intended usage:</em>
 *     Some APIs accept {@code Callable<Void>}, for example
 * </p>
 * <pre class=inline>
 *     public static &lt;T&gt; void foo(Callable&lt;T&gt; action)
 * </pre>
 * <p>
 *     which, unfortunately, is not compatible with
 *     void-returning lambda expressions and method references, for example
 * </p>
 * <pre class=inline>
 *    foo( ()-&gt;System.gc() );   // does NOT compile
 *    foo( System::gc );        // does NOT compile
 * </pre>
 * <p>
 *     This adapter interface provides a workaround:
 * </p>
 * <pre class=inline>
 *    foo( (Callable_Void) ()-&gt;System.gc() )
 *    foo( (Callable_Void) System::gc )
 * </pre>
 */

/* ------------------

    can also be used to convert any value-return lambda to Callable<Void>
        foo( (Callable_Void) ()->System.nanoTime() );
        foo( (Callable_Void) System::nanoTime );
    but not sure how often users need it; so it's not documented


    maybe a simpler name like ToVoid? AsVoid?
    probably not used often, so a more explicit name is better.

    adapter for Func: Func_Void<T> extends Func<T,Void>
    not as convenient, because of the type argument
        foo( (Func_Void<String>) System.out::println )
    it's better to provide another method
        foo2( System.out::println )

 */

public interface Callable_Void extends Callable<Void>
{
    /**
     * The single abstract method, which returns <code>void</code> (instead of <code>Void</code>).
     */
    public void call_void() throws Exception;

    /**
     * Implements {@code Callable<Void>.call()},
     * as: <code>{ call_void(); return null; }</code>
     *
     * @return <code>(Void)null</code>
     *
     */
    @Override
    default Void call() throws Exception
    {
        call_void();
        return null;
    }
}
