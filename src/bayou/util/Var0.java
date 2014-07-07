package bayou.util;

class Var0<T> extends VarAbs<T>
{
    T value;

    Var0(T value)
    {
        this.value = value;
    }

    @Override
    public T get()
    {
        return value;
    }

    @Override
    public T set(T value)
    {
        this.value = value;
        return value;
    }

}
