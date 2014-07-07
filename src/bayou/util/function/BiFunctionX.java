package bayou.util.function;

import java.util.function.*;
/**
 * Like {@link BiFunction},
 * except checked exceptions may be thrown.
 */
public interface BiFunctionX<T, U, R>
{
    /**
     * Like {@link BiFunction#apply}, except checked exceptions may be thrown.
     */
    R apply(T t, U u) throws Exception;
}
