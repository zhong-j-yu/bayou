package bayou.http;

import _bayou._tmp._StrUtil;
import bayou.mime.Headers;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

/**
 * An entry in http access log.
 * <p>
 *     Each HttpAccess is passed to the
 *     {@link bayou.http.HttpServerConf#accessLogger(java.util.function.Consumer) accessLogger} to be logged.
 * </p>
 * <p>
 *     See {@link #toCombinedLogFormat()} and {@link #toCommonLogFormat()} for some commonly used output formats.
 * </p>
 */
// we may want to add more info in future.
public class HttpAccess
{
    /**
     * The http request.
     */
    public final HttpRequest request;
    /**
     * The http response.
     */
    public final HttpResponse response;

    /**
     * The response body size. The value is unreliable if {@link #responseError}!=null.
     */
    // may be smaller due to error when writing the response
    // actual bytes received by the client could be less than this value.
    public final long responseBodySize;

    /**
     * The time when the request is received.
     */
    // when the entire head of the request is received.
    public final long timeRequestReceived;
    /**
     * The time when the server starts to write the response.
     */
    // time response is generated and the server begins to write it to client
    public final long timeResponseBegins;
    /**
     * The time when the server finishes writing the response.
     */
    // time response writing is terminated, either normally or abnormally
    public final long timeResponseEnds;

    /**
     * Error occurred during writing the response; null if none.
     */
    // error when writing response to client. usually network problem. null if no error
    public final Exception responseError;

    /**
     * Create an HttpAccess instance.
     */
    public HttpAccess(HttpRequest request, HttpResponse response,
                      long responseBodySize,
                      long timeRequestReceived, long timeResponseBegins, long timeResponseEnds,
                      Exception responseError)
    {
        this.request = request;
        this.response = response;
        this.responseBodySize = responseBodySize;

        this.timeRequestReceived = timeRequestReceived;
        this.timeResponseBegins = timeResponseBegins;
        this.timeResponseEnds = timeResponseEnds;
        this.responseError = responseError;
    }

    /**
     * Convert to "Common Log Format".
     * <p>
     *     Example:
     * </p>
     * <pre>
     * 127.0.0.1 - - [10/Oct/2000:13:55:36 -0700] "GET /apache_pb.gif HTTP/1.0" 200 2326
     * </pre>
     */
    public CharSequence toCommonLogFormat()
    {
        StringBuilder sb = new StringBuilder();
        toCommonLogFormat(sb);
        return sb.toString();
    }
    void toCommonLogFormat(StringBuilder sb)
    {
        HttpRequest req = request;
        // date formatting is slow; ok compared to logging overhead
        SimpleDateFormat df = new SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z");
        String date = df.format(new Date(timeRequestReceived));

        sb.append(req.ip().getHostAddress());
        sb.append(" - - [");
        sb.append(date);
        sb.append("] \"");
        sb.append(req.method()).append(' ').append(req.uri()).append(' ').append(req.httpVersion());
        // request uri does not contain " or \ therefore we don't need to worry about escaping them
        sb.append("\" ");
        sb.append(response.status().code);
        sb.append(' ');
        sb.append(responseBodySize);
    }

    /**
     * Convert to "Combined Log Format".
     * <p>
     *     Example:
     * </p>
     * <pre>
     * 127.0.0.1 - - [10/Oct/2000:13:55:36 -0700] "GET /apache_pb.gif HTTP/1.0" 200 2326 "http://www.example.com/start.html" "Mozilla/4.08 [en] (Win98; I ;Nav)"
     * </pre>
     */
    public CharSequence toCombinedLogFormat()
    {
        StringBuilder sb = new StringBuilder();
        toCommonLogFormat(sb);

        Map<String,String> reqHeaders = request.headers();
        String hReferer = reqHeaders.get(Headers.Referer);
        if(hReferer==null)
            hReferer="";
        String hUserAgent = reqHeaders.get(Headers.User_Agent);
        if(hUserAgent==null)
            hUserAgent="";

        sb.append(' ');
        _StrUtil.appendQuoted(sb, hReferer);
        sb.append(' ');
        _StrUtil.appendQuoted(sb, hUserAgent);

        return sb.toString();
    }
}
