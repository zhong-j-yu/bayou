package bayou.websocket;

import _bayou._async._Asyncs;
import _bayou._async._WithThreadLocalFiber;
import _bayou._tmp._Exec;
import _bayou._tmp._Util;
import bayou.async.*;
import bayou.http.*;
import bayou.reload.HotReloader;
import bayou.util.End;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Hot-reloadable WebSocketHandler.
 * <p>
 *     A hot handler is a "shell" that forwards <code>handle(request)</code> calls to
 *     the app handler that does the actual work. When source files are changed,
 *     a new app handler instance is created. Reloading is done by a {@link HotReloader}.
 * </p>
 * <p>
 *     Typically a Hot<u>WebSocket</u>Handler works together with a Hot<u>Http</u>Handler,
 *     and they should share the same HotReloader. For example:
 * </p>
 * <pre>
 *     HotReloader reloader = new HotReloader().onJavaFiles(SRC_DIR);
 *
 *     HotHttpHandler httpHandler = new HotHttpHandler(reloader, MyHttpHandler.class);
 *
 *     HotWebSocketHandler wsHandler = new HotWebSocketHandler(reloader, MyWsHandler.class);
 * </pre>
 */
public class HotWebSocketHandler implements WebSocketHandler
{
    static final Class<?>[] minSharedClasses = {

        // WebSocketHandler and classes it depends on
        WebSocketHandler.class,

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
     *     {@link #HotWebSocketHandler(HotReloader, String) HotWebSocketHandler( new HotReloader(), appHandlerClass.getName() )}.
     * </p>
     */
    public HotWebSocketHandler(Class appHandlerClass)
    {
        this(new HotReloader(), appHandlerClass.getName());
    }

    /**
     * Create a hot handler.
     * <p>
     *     This constructor is equivalent to
     *     {@link #HotWebSocketHandler(HotReloader, String) HotWebSocketHandler( reloader, appHandlerClass.getName() )}.
     * </p>
     */
    public HotWebSocketHandler(HotReloader reloader, Class appHandlerClass)
    {
        this(reloader, appHandlerClass.getName());
    }

    /**
     * Create a hot handler.
     * <p>
     *     The app handler class must implement WebSocketHandler and have a public 0-arg constructor.
     * </p>
     */
    public HotWebSocketHandler(HotReloader reloader, String appHandlerClassName)
    {
        this.reloader = reloader;
        this.appHandlerClassName = appHandlerClassName;

        reloader.addSharedClasses(minSharedClasses);

        reloader.getMessageOut().accept("HotWebSocketHandler created for " + appHandlerClassName);
    }

    String getAppHandlerClassName()
    {
        return appHandlerClassName;
    }


    /**
     * Handle a WebSocket request.
     * <p>
     *     This method obtains the app handler by
     *     <code>reloader().getAppInstance(appHandlerClassName)</code>,
     *     then forwards the call to the app handler.
     * </p>
     */
    @Override
    public Async<WebSocketResponse> handle(WebSocketRequest request)
    {
        WebSocketHandler appHandler;

        try
        {
            appHandler = (WebSocketHandler)reloader().getAppInstance(appHandlerClassName); // synchronized
            // if there's no source change, the overhead is negligible in the req-resp cycle
        }
        catch (Exception e)
        {
            // there's no point to allow app to customize the response on reload error;
            // the browser doesn't display it anyway. just dump the exception locally.
            _Util.printStackTrace(e, reloader.getMessageOut());
            return WebSocketResponse.reject(500, e.toString());
        }

        return appHandler.handle(request); // throws
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
    public HotWebSocketHandler onJavaFiles(String... srcDirs) throws Exception
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
    public HotWebSocketHandler onClassFiles()
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
    public HotWebSocketHandler onFiles(String fileDesc, String filePattern, String... srcDirs)
    {
        reloader().onFiles(fileDesc, filePattern, srcDirs);
        return this;
    }

}
