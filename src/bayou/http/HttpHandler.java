package bayou.http;

import bayou.async.Async;

/**
 * Generates a response for each http request.
 * <p>
 *     See {@link HttpServer}.
 * </p>
 * <h4>Convention of filters</h4>
 * <p>
 *     A filter is an HttpHandler that may forward the request to another handler,
 *     possibly transforming the request beforehand, and/or the response afterwards.
 * </p>
 * <p>
 *     We recommend that a filter is created by either a constructor or a factory method
 *     that accepts the target handler. For example:
 * </p>
 * <pre>
 *     HttpHandler handler;
 *     handler = new MyAppHandler();
 *     handler = new FooFilter(handler);
 *     handler = BarFilter.create(handler, params);
 *     ...
 *     HttpServer server = new HttpServer(handler);
 * </pre>
 */

// we could add some default methods here for generating common responses, like text(code, string),
// which subclasses often need. it doesn't feel very right though.
// leave it to users to add whatever convenience methods in their class hierarchy.

public interface HttpHandler
{
    /**
     * Generate a response for the request.
     * <p>
     *     This is an Async action. The action must eventually complete with a
     *     non-null <code>HttpResponse</code>, or an exception.
     * </p>
     * <p>
     *     Note that {@link HttpResponseImpl} is a subtype of <code>Async&lt;HttpResponse&gt;</code>,
     *     therefore it can be returned from this method.
     * </p>
     * <p>
     *     The response status code must not be 1xx(e.g. "100 Continue");
     *     1xx responses are handled automatically by lower layers.
     * </p>
     * <p>
     *     For HttpServer handler:
     * </p>
     * <ul>
     *     <li><p>
     *         <code>handle(request)</code> is invoked for every incoming request.
     *         It's always invoked on a {@link bayou.async.Fiber fiber},
     *         with the request as the {@link bayou.http.HttpRequest#current() fiber-local request}.
     *         If this action fails with an exception, <code>HttpServer</code> will generate
     *         a simple {@link HttpResponse#internalError(Throwable) internal error} response.
     *     </p></li>
     *     <li><p>
     *         request entity is not <a href="HttpEntity.html#sharable">sharable</a>;
     *         its body can be read only once.
     *     </p></li>
     *     <li><p>
     *         after <code>handle(request)</code> completes,
     *         the request body must not be read any more.
     *     </p></li>
     *     <li><p>
     *         HEAD requests can be treated the same as GET requests;
     *         the server will not read the response entity body.
     *     </p></li>
     *     <li><p>
     *         conditional GET requests can be treated the same as normal GET requests;
     *         they are handled
     *         {@link HttpServerConf#autoConditional(boolean) automatically} by default.
     *     </p></li>
     *     <li><p>
     *         Range requests can be treated the same as normal GET requests;
     *         they are handled
     *         {@link HttpServerConf#autoRange(boolean) automatically} by default.
     *     </p></li>
     * </ul>
     */
    Async<HttpResponse> handle(HttpRequest request);

    // why not "throws Exception" - force app to generate a proper response for checked exception.

    // server always invoke this method from a selector thread.
    // once the result is completed, request body must no longer be read by app.
    //   (server may read request body to drain it. there are both concurrency issue and body integrity issue)

    // no wildcard in return type. this code does not compile
    //    return // target type Async<HttpResponse>
    //      formData
    //        .map(formData -> HttpResponse.text(200, "post ok: " + formData.params()))
    //        .catch_(CsrfException.class, e -> HttpResponse.text(403, "your session expired? " + e))
    //        ;
    // because map() returns Async<HttpResponseImpl>, and Async<T>.catch_() returns Async<T>.
    //    ideally catch_() should return Async<R> where <R super T>. but java doesn't support it.
    // it can work if we use wildcard in return type. but that causes other problems, e.g.
    // in invoking child handlers, wildcard capture introduces a subtype that's difficult to work with:
    //     childHandler.handle(request) // returns Async<? extends HttpResponse>
    //         .catch_(Exception.class, ex->anHttpResponse)  // fail
    //
    // at this time, our general practice is to avoid wildcards, and see how far we can go.
    // use Async.covary() when necessary for covariance.

}

