package bayou.http;

import _bayou._async._Asyncs;
import _bayou._async._WithThreadLocalFiber;
import _bayou._tmp._Exec;
import _bayou._tmp._Util;
import bayou.async.*;
import bayou.reload.HotReloader;
import bayou.util.End;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Hot-reloadable HttpHandler.
 * <p>
 *     A hot handler is a "shell" that forwards <code>handle(request)</code> calls to
 *     the app handler that does the actual work. When source files are changed,
 *     a new app handler instance is created in a new class loader.
 *     Reloading is done by a {@link HotReloader}.
 * </p>
 * <p>Example Usage:</p>
 * <pre>
 *     // HttpHandler handler = new MyHandler();
 *     HotHttpHandler handler = new HotHttpHandler(MyHandler.class).onJavaFiles(SRC_DIR);
 *
 *     HttpServer server = new HttpServer(handler);
 * </pre>
 */

// where there's no source change, the overhead is very small, there's no detectable throughput degrading.
// so no problem to use it for test server as well as dev server.
// it's even ok for production server, although, if (HttpHandler)reloader.getAppInstance() throws,
//    which is unlikely, error detail will be reported to end user. subclass can override handleReloadError().
//    a hot-reload-able production server might be useful.

public class HotHttpHandler implements HttpHandler
{
    static final Class<?>[] minSharedClasses = {

        // HttpHandler and classes it depends on
        HttpHandler.class,

        // these need to be shared, tho not obviously so from public API
        End.class,
        Fiber.class, _WithThreadLocalFiber.class, FiberLocal.class,
        CookieJar.class,

        // not necessary, but nice to share them anyway
        AsyncIterator.class, Promise.class, _Asyncs.class,
        _Exec.class, // share threads

    };



    final HotReloader reloader;
    final String appHandlerClassName;

    /**
     * Create a hot handler.
     * <p>
     *     This constructor is equivalent to
     *     {@link #HotHttpHandler(HotReloader, String) HotHttpHandler( new HotReloader(), appHandlerClass.getName() )}.
     * </p>
     */
    public HotHttpHandler(Class appHandlerClass)
    {
        // use class literal, instead of string literal for the class. may be convenient.
        // note this requires loading the class in memory. may not be too expensive.
        // user should prefer the String version.
        this(new HotReloader(),appHandlerClass.getName());
    }

    /**
     * Create a hot handler.
     * <p>
     *     This constructor is equivalent to
     *     {@link #HotHttpHandler(HotReloader, String) HotHttpHandler( reloader, appHandlerClass.getName() )}.
     * </p>
     */
    public HotHttpHandler(HotReloader reloader, Class appHandlerClass)
    {
        this(reloader, appHandlerClass.getName());
    }

    /**
     * Create a hot handler.
     * <p>
     *     The app handler class must implement HttpHandler and have a public 0-arg constructor.
     * </p>
     */
    // args order: why put `appHandlerClassName` after `reloader`:
    //   in future we may want to support (reloader, appHandlerClassName, constructorArgs...)
    public HotHttpHandler(HotReloader reloader, String appHandlerClassName)
    {
        this.reloader = reloader;
        this.appHandlerClassName = appHandlerClassName;

        reloader.addSharedClasses(minSharedClasses);

        reloader.getMessageOut().accept("HotHttpHandler created for " + appHandlerClassName);
    }

    String getAppHandlerClassName()
    {
        return appHandlerClassName;
    }

    /**
     * Handle an http request.
     * <p>
     *     This method obtains the app handler by
     *     <code>reloader().getAppInstance(appHandlerClassName)</code>,
     *     then forwards the call to the app handler.
     * </p>
     */
    @Override
    public Async<HttpResponse> handle(HttpRequest request)
    {
        HttpHandler appHandler;

        try
        {
            appHandler = (HttpHandler)reloader().getAppInstance(appHandlerClassName); // synchronized
            // if there's no source change, the overhead is negligible in the req-resp cycle
        }
        catch (Exception e)
        {
            return Async.success(handleReloadError(e));
        }

        return appHandler.handle(request); // throws unchecked exceptions
    }

    /**
     * Create a response in case the reloader encounters an error.
     * <p>
     *     For example, the reloader may fail due to compilation errors;
     *     then the {@link #handle(HttpRequest)} method invokes this method for the response.
     * </p>
     * <p>
     *     This implementation creates a response with the error stacktrace;
     *     a subclass may override this method to provide a different response.
     * </p>
     */
    // no need to return Async<HttpResponse>
    protected HttpResponse handleReloadError(Exception error)
    {
        _Util.printStackTrace(error, reloader.getMessageOut());

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("== Hot Reload Error ==");
        pw.println();
        error.printStackTrace(pw);

        return HttpResponse.text(500, sw.toString());
    }


    /**
     * Get the HotReloader.
     */
    // app may need this to do more config on reloader
    public HotReloader reloader()
    {
        return reloader;
    }

    /**
     * Reload on java file changes.
     * <p>
     *     This method is equivalent to <code>reloader().</code>{@link HotReloader#onJavaFiles onJavaFiles(srcDirs)}.
     * </p>
     * @return this
     */
    public HotHttpHandler onJavaFiles(String... srcDirs) throws Exception
    {
        reloader().onJavaFiles(srcDirs);
        return this;
    }


    /**
     * Reload on class file changes.
     * <p>
     *     This method is equivalent to <code>reloader().</code>{@link HotReloader#onClassFiles onClassFiles(srcDirs)}.
     * </p>
     * @return this
     */
    public HotHttpHandler onClassFiles()
    {
        reloader().onClassFiles();
        return this;
    }

    /**
     * Reload on file changes.
     * <p>
     *     This method is equivalent to <code>reloader().</code>{@link HotReloader#onFiles onFiles(srcDirs)}.
     * </p>
     * @return this
     */
    public HotHttpHandler onFiles(String fileDesc, String filePattern, String... srcDirs)
    {
        reloader().onFiles(fileDesc, filePattern, srcDirs);
        return this;
    }

}
