package bayou.util;

import java.util.Objects;

abstract class VarAbs<T> implements Var<T>
{
    @Override
    public int hashCode()
    {
        return Objects.hashCode(get());
    }

    @Override
    public boolean equals(Object obj)
    {
        return (obj instanceof Var) && Objects.equals(this.get(), ((Var<?>)obj).get());
    }

    @Override
    public String toString()
    {
        return String.valueOf(get());
    }
}
