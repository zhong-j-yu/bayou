package bayou.util;

import java.util.function.Consumer;
import java.util.function.Supplier;

class Var2<T> extends VarAbs<T>
{
    final Supplier<? extends T> getter;
    final Consumer<? super   T> setter;

    Var2(Supplier<? extends T> getter, Consumer<? super T> setter)
    {
        this.getter = getter;
        this.setter = setter;
    }

    @Override
    public T get()
    {
        return getter.get();
    }

    @Override
    public T set(T value)
    {
        setter.accept(value); // may throw
        return value;
    }

}
