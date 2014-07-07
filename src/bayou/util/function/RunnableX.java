package bayou.util.function;


/**
 * Like {@link Runnable},
 * except checked exceptions may be thrown.
 */
public interface RunnableX
{
    /**
     * Like {@link Runnable#run()}, except checked exceptions may be thrown.
     */
    public void run() throws Exception;
}
