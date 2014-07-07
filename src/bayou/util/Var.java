package bayou.util;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A variable that can be read from and written to.
 *
 * <p>
 *     See {@link #get()} and {@link #set(Object) set(T)} methods.
 * </p>
 * <p>
 *     The set() method may opt to throw RuntimeException for whatever reason.
 * </p>
 *
 * <h4>hashCode(), equals(), toString()</h4>
 * <p>
 *     These methods on a subclass must have the following semantics
 * </p>
 * <pre>
    public int hashCode()
    {
        return Objects.hashCode(get());
    }
    public boolean equals(Object obj)
    {
        return (obj instanceof Var) &amp;&amp; Objects.equals(this.get(), ((Var&lt;?&gt;)obj).get());
    }
    public String toString()
    {
        return String.valueOf(get());
    }
 * </pre>
 * <p>
 *     A subclass can simply copy&amp;paste these lines.
 *     Unfortunately they cannot be default methods on this interface.
 * </p>
 *
 */
public interface Var<T>
{

    /**
     * Read the value of this Var.
     */
    public T get();

    /**
     * Set the value of this Var, then return the value.
     * <p>
     *     Note that the new value, not the old one, is returned.
     *     This is to be consistent with the semantics of Java assignment expression.
     * </p>
     * @return the same `value`
     * @throws RuntimeException if the value cannot be set, for whatever reason.
     */
    public T set(T value) throws RuntimeException;

    /**
     * Create a Var with the getter and setter.
     * <p>
     *     The get() and set() methods of the Var simply forward to the getter and setter.
     * </p>
     */
    public static <T> Var<T> of(Supplier<? extends T> getter, Consumer<? super T> setter)
    {
        return new Var2<>(getter, setter);
    }

    /**
     * Create a simple Var with the initial value.
     * <p>
     *     The get() and set() methods of the Var are just like read/write a non-volatile field.
     * </p>
     */
    public static <T> Var<T> init(T initValue)
    {
        return new Var0<>(initValue);
    }

    /**
     * Create a Var with a validator.
     * <p>
     *     The set(value) methods of the Var will first call `validator.accept(value)`;
     *     if the validator throws RuntimeException, set(value) throws the same exception.
     *     Other than that,
     *     the get() and set() methods are just like read/write a non-volatile field.
     * </p>
     * <p>
     *     The initValue is also checked by the validator;
     *     if the validator throws RuntimeException, this method throws the same exception.
     * </p>
     */
    public static <T> Var<T> init(T initValue, Consumer<? super T> validator) throws RuntimeException
    {
        return new Var1<>(validator, initValue);
    }


    // for volatile semantics, we may provide volatile_xxx(...) methods in future.
    // for now, app can DIY by using of(getter, setter) with volatile getter/setter


}
