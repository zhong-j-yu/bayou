package bayou.util.function;

import java.util.function.*;
/**
 * Like {@link Function},
 * except checked exceptions may be thrown.
 */
public interface FunctionX<T, R>
{
    /**
     * Like {@link Function#apply}, except checked exceptions may be thrown.
     */
    public R apply(T t) throws Exception;
}
