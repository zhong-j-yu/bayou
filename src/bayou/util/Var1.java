package bayou.util;

import java.util.function.Consumer;
import java.util.function.Supplier;

class Var1<T> extends VarAbs<T>
{
    final Consumer<? super T> validator;
    T value;

    Var1(Consumer<? super T> validator, T value)
    {
        this.validator = validator;
        set(value);
    }

    @Override
    public T get()
    {
        return value;
    }

    @Override
    public T set(T value)
    {
        validator.accept(value); // may throw
        this.value = value;
        return value;
    }

}
