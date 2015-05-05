package bayou.http;

import bayou.async.Async;

import java.util.List;

/**
 * Cookie storage for HttpClient. See {@link HttpClientConf#cookieStorage(CookieStorage)}.
 * <p>
 *     Before sending a request, HttpClient calls {@link #getCookies(HttpRequest)};
 *     after receiving the response, HttpClient calls {@link #setCookies(HttpRequest, HttpResponse)}.
 * </p>
 */
public interface CookieStorage
{
    /**
     * Get the cookies that should be sent with the request.
     */
    public Async<List<Cookie>> getCookies(HttpRequest request);
    // no consideration for HttpOnly. (HttpOnly is retarded anyway)
    //    if necessary, caller can filter the result cookies.

    /**
     * Save the cookies carried in the response.
     */
    public Async<Void> setCookies(HttpRequest request, HttpResponse response);
    // atomicity: ideally we should atomically set all cookies in a response,
    //   because there could be integrity across multiple cookies.
    //   this is not a strict requirement though.
    // causality: after this action completes, getCookies() should see the effects.


    /**
     * Create a new instance of an in-memory implementation of CookieStorage.
     */
    public static CookieStorage newInMemoryStorage()
    {
        return new InMemoryCookieStorage();
    }


}
