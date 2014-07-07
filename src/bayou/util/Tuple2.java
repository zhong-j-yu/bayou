package bayou.util;

import java.util.Objects;

class Tuple2<T1, T2>
{
    public final T1 v1;
    public final T2 v2;

    public Tuple2(T1 v1, T2 v2)
    {
        this.v1 = v1;
        this.v2 = v2;
    }

    public static <T1,T2> Tuple2<T1, T2> of(T1 v1, T2 v2)
    {
        return new Tuple2<>(v1, v2);
    }

    public T1 getV1()
    {
        return v1;
    }

    public T2 getV2()
    {
        return v2;
    }

    @Override
    public int hashCode()
    {
        // see List.hashCode
        return 31*31 + 31*Objects.hashCode(v1) + Objects.hashCode(v2);
    }

    @Override
    public boolean equals(Object obj)
    {
        if(!(obj instanceof Tuple2))
            return false;

        Tuple2<?,?> that = (Tuple2<?,?>)obj;
        return Objects.equals(this.v1, that.v1)
            && Objects.equals(this.v2, that.v2);
    }

    @Override
    public String toString()
    {
        return "Pair["+v1+","+v2+"]";
    }

}
