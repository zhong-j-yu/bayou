package bayou.async;

import _bayou._tmp._Util;

/**
 * A fiber-local variable. This class is an analogue of `ThreadLocal`.
 * <p>
 *     Example usage:
 * </p>
 * <pre>
     static final FiberLocal&lt;User&gt; fiberLocalUser = new FiberLocal&lt;&gt;();

     Async&lt;Void&gt; action1()
     {
         fiberLocalUser.set(user);
         return Fiber.sleep(Duration.ofSeconds(1))
             .then( v-&gt;action2() );
     }
     Async&lt;Void&gt; action2()
     {
         User user = fiberLocalUser.get();
         ...
     }
 * </pre>
 * <p>
 *     The initial fiber-local value of a FiberLocal is always null.
 * </p>
 */
public class FiberLocal<T>
{
    /**
     * Create a FiberLocal variable.
     * The result object is usually assigned to a static final variable.
     */
    public FiberLocal()
    {

    }

    /**
     * Get the fiber-local value for this variable.
     *
     * <p>
     *     The initial fiber-local value, before any {@link #set set()} calls, is null.
     * </p>
     *
     * @throws IllegalStateException
     *         if `Fiber.current()==null`
     */
    public T get() throws IllegalStateException
    {
        Fiber<?> fiber = Fiber.current();
        if(fiber==null)
            throw new IllegalStateException("Fiber.current()==null");
        Object value = fiber.fiberLocalMap.get(this);
        return _Util.cast(value);
    }

    /**
     * Set the fiber-local value for this variable.
     *
     * @throws IllegalStateException
     *         if `Fiber.current()==null`
     */
    // return void - don't know whether to return prev value or the new value.
    public void set(T value) throws IllegalStateException
    {
        Fiber<?> fiber = Fiber.current();
        if(fiber==null)
            throw new IllegalStateException("Fiber.current()==null");
        if(value==null)
            fiber.fiberLocalMap.remove(this);
        else
            fiber.fiberLocalMap.put(this, value);
    }
}
