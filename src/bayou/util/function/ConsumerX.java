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
}
