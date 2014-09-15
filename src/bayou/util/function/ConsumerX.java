package bayou.util.function;

import java.util.function.*;
/**
 * Like {@link Consumer},
 * except checked exceptions may be thrown.
 */
public interface ConsumerX<T>
{
    /**
     * Like {@link Consumer#accept}, except checked exceptions may be thrown.
     */
    public void accept(T t) throws Exception;


    /**
     * Similar to {@link Consumer#andThen(java.util.function.Consumer)}.
     */
    public default ConsumerX<T> then(ConsumerX<T> that)
    {
        return (T t) ->
        {
            this.accept(t);
            that.accept(t);
        };
    }

}
