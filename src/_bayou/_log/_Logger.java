package _bayou._log;

import java.util.function.Function;

// so we created yet another logging abstraction and implementation, which is ridiculous.
// our excuses:
//   - we don't like java's logging. we don't want to have a dependency on 3rd party libs.
//   - it's critical for us that logging must be async. we can't trust other libs for that.
//   - it's important that errors are printed to console, if user didn't do any logging config

// this is for bayou internal use, not intended for the public.

// we only log unexpected errors that are not supposed to happen.
// we don't really expect that in normal operations (of http server) there will be such errors.
// so most likely users won't worry about our logging outputs, there's no need to config.
// we do have a setProvider(), mostly so that users may temporarily enable DEBUG for diagnosis during development.
// and if someone really want to manage our logs (e.g. with SLF4J), they can do it without too much hassle.

// hmm... but apps using our framework also *need* async logging, coz they are async apps.
// wait for user opinions. maybe publish this thing later. as bayou.util.AsyncLogger?

public interface _Logger
{
    public enum _Level
    {
        TRACE, DEBUG, INFO, WARN, ERROR
    }

    /**
     * The logging level.
     */
    public abstract _Level level();

    /**
     * Print the message at the logging level; print the stacktrace if throwable!=null.
     * <p>
     *     The application should not use this method.
     *     The logging framework will invoke this method;
     *     invocations will be serialized, i.e. this method will not be invoked concurrently.
     * </p>
     * <p>
     *     The implementation of this method can perform blocking IO operations.
     * </p>
     */
    public abstract void impl_log(_Level level, CharSequence message, Throwable throwable) throws Exception;


    // -----------------------------------------------------------------------------------------------------------


    // do not override
    public default void log(_Level level, String format, Object... args)
    {
        if(level.ordinal()<level().ordinal())
            return;

        String message;
        try
        {
            message = String.format(format, args); // do this on caller thread, not async-ly
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return;
        }

        Throwable throwable = null;
        for(Object arg : args)
            if(arg instanceof Throwable)
                throwable = (Throwable)arg;

        _LoggerS.asyncPrint(this, level, message, throwable);

    }

    // do not override
    public default void trace(String format, Object... args)
    {
        log(_Level.TRACE, format, args);
    }

    // do not override
    public default void debug(String format, Object... args)
    {
        log(_Level.DEBUG, format, args);
    }

    // do not override
    public default void info(String format, Object... args)
    {
        log(_Level.INFO, format, args);
    }

    // do not override
    public default void warn(String format, Object... args)
    {
        log(_Level.WARN, format, args);
    }

    // do not override
    public default void error(String format, Object... args)
    {
        log(_Level.ERROR, format, args);
    }


    // ----------------------------------------------------------------------------------------------------------

    /**
     * Get a logger for the name.
     */
    public static _Logger of(String name)
    {
        return _LoggerS.getLogger(name);
    }

    /**
     * Get a logger for the class name.
     */
    public static _Logger of(Class clazz)
    {
        return _LoggerS.getLogger(clazz.getName());
    }

    /**
     * Set the logger provider.
     * <p>
     *     The provider returns a _Logger for a given name.
     *     For example
     * </p>
     * <pre>
     *     // enable DEBUG for all
     *     _Logger.setProvider( (name) -&gt;
     *         new _SimpleLogger(name, _Logger._Level.DEBUG, System.err)
     *     );
     * </pre>
     * <p>
     *     This method should be called in the very beginning of the application,
     *     before other classes are initialized.
     * </p>
     */
    public static void setProvider(Function<String, _Logger> loggerProvider)
    {
        _LoggerS.loggerProvider = loggerProvider; // not volatile
    }
}
