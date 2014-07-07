package bayou.form;

import _bayou._tmp._HttpUtil;
import bayou.async.Async;
import bayou.bytes.ByteSource;
import bayou.http.HttpEntity;
import bayou.http.HttpRequest;
import bayou.mime.ContentType;
import bayou.mime.Headers;
import bayou.util.OverLimitException;
import bayou.util.Result;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Parses form data from http requests.
 * See {@link #parse(CharSequence) parse(uri)} and {@link #parse(HttpRequest) parse(request)}.
 * <p>
 *     See also {@link FormData#parse(CharSequence) FormData.parse(uri)}
 *     and {@link FormData#parse(HttpRequest) FormData.parse(request)}
 *     which use this parser with default configuration.
 * </p>
 * <h4>Config</h4>
 * <p>
 *     A parser can be configured by methods like {@link #charset(Charset)}.
 *     These methods return `this` for method chaining. For example
 * </p>
 * <pre>
 *     FormParser parser = new FormParser()
 *         .charset(StandardCharsets.US_ASCII)
 *         .maxFileEntries(3)
 *         ;
 *
 *     parser.parse(...);
 * </pre>
 * <p>
 *     A parser can be cached to parse multiple requests concurrently,
 *     as long as its configuration is no longer modified.
 * </p>
 * <pre>
 *     static final FormParser parser = new FormParser().maxFileEntries(0); // disallow file upload
 * </pre>
 * <h4 id=csrf>CSRF Detection</h4>
 * <p>
 *     By default, {@link #detectCsrf(boolean) detectCsrf} is enabled.
 *     If a request is POST, <code>parse(request)</code> uses the following heuristics
 *     to determine that the request is <i>not</i> CSRF.
 * </p>
 * <ul>
 *     <li>
 *         The CSRF token in the form field matches the token in the cookie.
 *         See {@link #csrfTokenName(String)} and {@link CsrfToken}.
 *     </li>
 *     <li>
 *         The "Origin" request header matches the "Host" header,
 *         e.g. <code>"Origin: http://localhost:8080"</code>.
 *     </li>
 *     <li>
 *         The "Referer" request header matches the "Host" header,
 *         e.g. <code>"Referer: http://localhost:8080/show"</code>.
 *     </li>
 * </ul>
 * <p>
 *     Otherwise, <code>parse(request)</code> fails with {@link bayou.form.CsrfException}.
 *     Note that false detection is possible - the request is suspected as CSRF while
 *     in reality it is legitimate.
 *     See <a href="CsrfException.html#response">Generate a response for a CsrfException</a>
 * </p>
 */

// we only need to check CSRF for form POST, not other methods.
// it is an historical mistake that an html form post can be cross-origin.
// new mechanisms, like XMLHttpRequest, won't commit the same mistake.
// we don't check GET request even if csrf token cookie/field exist.

public class FormParser
{
    /**
     * Create a parser with default settings.
     * <p>
     *     Use methods like {@link #charset(Charset)} to configure the parser after construction.
     * </p>
     */
    public FormParser()
    {

    }

    // one FormParser can be used to parse multiple requests, concurrently.
    // but we don't need to advertise that.

    /**
     * Parse the URI for form data.
     * <p>
     *     The query component of the URI is expected to be <code>"application/x-www-form-urlencoded"</code>.
     * </p>
     * <p>
     *     This method can be used for any request URI, regardless of the request method.
     * </p>
     *
     * @throws ParseException
     *         the query component of the URI is not valid <code>"application/x-www-form-urlencoded"</code>.
     * @throws OverLimitException
     *         a conf limit is exceeded, for example <code>"maxParamEntries"</code>
     */
    // for GET/HEAD. maybe post request uri too.
    // uri can be full URI: "http://host/path?query". we don't advertise the fact.
    public FormData parse(CharSequence uri) throws ParseException, OverLimitException
    {
        return DoParseUrlEncoded.parseUri(this, uri);
    }

    /**
     * Parse the request for POST form data.
     * <p>
     *     If the request method is POST, and the Content-Type is <code>"multipart/form-data"</code>
     *     or <code>"application/x-www-form-urlencoded"</code>,
     *     the request body is parsed accordingly.
     * </p>
     * <p>
     *     This is an Async action. The action may fail with the following exceptions:
     * </p>
     * <ul>
     *     <li>{@link ParseException} - the request does not contain valid POST form data </li>
     *     <li>{@link OverLimitException} - a conf limit is exceeded, for example <code>"maxParamEntries"</code></li>
     *     <li>{@link CsrfException} - the request is suspected to be CSRF</li>
     *     <li>{@link Exception} - other errors, e.g. network error while reading request body</li>
     * </ul>
     */
    public Async<FormData> parse(HttpRequest request)
    {
        try
        {
            Async<FormData> afd = parseE(request);
            if(detectCsrf)
                afd = afd.map(formData -> detectCsrf(formData, request, csrfTokenName));
            return afd;
        }
        catch (Exception e)
        {
            return Result.failure(e);
        }
    }
    Async<FormData> parseE(HttpRequest request) throws OverLimitException, ParseException
    {
        String method = request.method();
        if( ! "POST".equals(method) )
            throw new ParseException("unexpected request method: "+method,0);
        // if GET/HEAD, we could parse the uri as form data instead for parse(request).
        // but it's very dangerous, so we reject the request.
        // we don't do CSRF detection on GET requests, and it's very super easy to forge GET requests.
        //    even if we do CSRF detection on GET, it may be easy to forge a GET request
        //    with proper Referer, e.g. the app displays images from URIs specified by end users.
        // if the server app forgets to check the request method, a forged GET request may be
        // accepted as a valid POST form submission. so we enforce "POST" method here.

        HttpEntity entity = request.entity();
        if(entity==null)
            throw new ParseException("POST request with no body",0);
        ContentType ct = entity.contentType();
        if(ct==null)
            throw new ParseException("POST request with no Content-Type",0);

        if(ct.types().equals(FormData.ENC_MULTIPART))
        {
            String boundary = ct.params().get("boundary");
            if(boundary==null || boundary.isEmpty())
                throw new ParseException("multipart/form-data with no boundary parameter",0);
            // check boundary char? probably unnecessary. we can handle any chars.

            ByteSource body = entity.body();
            return new DoParseMultipart(this, request.uri(), boundary, body).parse();
        }
        else if(ct.types().equals(FormData.ENC_URLENCODED))
        {
            ByteSource body = entity.body();
            return DoParseUrlEncoded.parsePostBody(this, request.uri(), body);
        }
        else
            throw new ParseException("unexpected Content-Type: "+ct,0);

    }

    // conf ====================================================================

    // todo: validate arguments
    // todo: use Var<> so app can read the values. not important.

    Charset charset = UTF_8;
    /**
     * The charset, used for decoding bytes to chars.
     * <p><code>
     *     default: UTF-8
     * </code></p>
     * @return `this`
     */
    public FormParser charset(Charset charset)
    {   this.charset=charset; return this;   }



    // CAUTION: if disabled, app must perform some security check to prevent CSRF
    boolean detectCsrf = true;
    /**
     * Whether to detect <a href="#csrf">CSRF</a>.
     * <p><code>
     *     default: true
     * </code></p>
     * @return `this`
     */
    public FormParser detectCsrf(boolean detectCsrf)
    {   this.detectCsrf =detectCsrf; return this; }




    String csrfTokenName = CsrfToken.DEFAULT_NAME;
    /**
     * The name of the CSRF token.
     * <p><code>
     *     default: {@link CsrfToken#DEFAULT_NAME}
     * </code></p>
     * @return `this`
     */
    public FormParser csrfTokenName(String csrfTokenName)
    {
        CsrfToken.validateTokenName(csrfTokenName);
        this.csrfTokenName=csrfTokenName;
        return this;
    }
    // csrf token cookie domain/path don't matter



    // no conf for parse timeout.
    // http server has min upload throughput to guard against slow clients.
    // app can also do parse(request).timeout(...)



    int maxEntryKeyBytes = 64;
    /**
     * Max length of any entry key, in bytes.
     * <p><code>
     *     default: 64
     * </code></p>
     * @return `this`
     */
    public FormParser maxEntryKeyBytes(int maxEntryKeyBytes)
    {   this.maxEntryKeyBytes =maxEntryKeyBytes; return this;   }




    int maxParamEntries = 256;
    /**
     * Max number of parameter entries.
     * <p><code>
     *     default: 256
     * </code></p>
     * @return `this`
     */
    public FormParser maxParamEntries(int maxParamEntries)
    {   this.maxParamEntries=maxParamEntries; return this;   }




    long maxParamValueTotalBytes = 16*1024; // bytes of values of all param entries
    /**
     * Max length of all parameter values combined, in bytes
     * <p><code>
     *     default: 16*1024 (16KB)
     * </code></p>
     * @return `this`
     */
    public FormParser maxParamValueTotalBytes(long maxParamValueTotalBytes)
    {   this.maxParamValueTotalBytes=maxParamValueTotalBytes; return this;   }



    // by default 1. if app needs more files, it'll see exception,
    // which is good, it'll then consciously choose a proper value that's not too big.
    // maybe even "1" is too big - most form posts have no file entry.
    int maxFileEntries = 1;
    /**
     * Max number of file entries
     * <p><code>
     *     default: 1
     * </code></p>
     * @return `this`
     */
    public FormParser maxFileEntries(int maxFileEntries)
    {   this.maxFileEntries=maxFileEntries; return this;   }




    int maxFileNameBytes = 256;
    /**
     * Max length of filename in any file entry, in bytes.
     * <p><code>
     *     default: 256
     * </code></p>
     * @return `this`
     */
    public FormParser maxFileNameBytes(int maxFileNameBytes )
    {   this.maxFileNameBytes=maxFileNameBytes; return this;   }



    long maxFileSize = 16 * 1024 * 1024; // of one file entry. small, likely reached during dev test
    /**
     * Max size of any file.
     * <p><code>
     *     default: 16*1024*1024 (16MB)
     * </code></p>
     * @return `this`
     */
    public FormParser maxFileSize(long maxFileSize)
    {   this.maxFileSize=maxFileSize; return this;   }




    // app may want to use diff dirs for diff files, e.g.  /my_file_upload/2013-01-01/
    Path tmpFileDir = Paths.get("/tmp/bayou/form_data_files");
    /**
     * The tmp dir for storing files.
     * <p><code>
     *     default: "/tmp/bayou/form_data_files"
     * </code></p>
     * @return `this`
     */
    public FormParser tmpFileDir(String tmpFileDir)
    {   this.tmpFileDir=Paths.get(tmpFileDir); return this;   }




    // check to see if the request could be csrf. if yes, throw CsrfException. else return formData.
    // we may also check Origin and Referer here.
    static FormData detectCsrf(FormData formData, HttpRequest request, String csrfTokenName) throws CsrfException
    {
        Map<String,String> requestHeaders = request.headers();
        String param = formData.param(csrfTokenName);
        boolean hasParam = has(param);

        String cookie = request.cookies().get(csrfTokenName);
        boolean hasCookie = has(cookie);

        if(hasCookie && hasParam) // they must match, regardless of Origin/Referer
        {
            if(cookie.equals(param)) // majority of times
                return formData;
            else  // most likely a malicious request
                throw new CsrfException("CSRF tokens do not match");
        }

        // let's then check Origin/Referer, usually they'll prove that the request is innocent.
        // apps that don't do anything for CSRF will reject all real CSRF requests and allow most innocent requests.
        // there will be some false rejections, when that become a problem, app need to employ CsrfToken.

        String host = requestHeaders.get(Headers.Host);
        assert has(host);

        String origin = requestHeaders.get(Headers.Origin);
        if(has(origin))  // if Origin is set, it is definitely reliable.
        {
            if(_HttpUtil.matchHost(host, origin))
                return formData;
            else
                throw new CsrfException("Origin does not match Host");
        }

        String referer = requestHeaders.get(Headers.Referer);
        // attacker can make Referer null, e.g. attack page is https, form action is http,
        // then browser will not set Referer for the request.
        if(has(referer)) // Referer is not very reliable
        {
            if(_HttpUtil.matchHost(host, referer))
            {
                return formData;
                // if Referer matches Host, we accept it. but it can be false acceptance!!
                // the browser could be configured so that Referer always matches Host,
                // because the user wants to fool sites that use Referer for access control on GET requests.
                // we don't fault these users. but we reason that attackers are very unlikely to exploit this situation,
                // since such users are few in number, it's not economical for attackers.
            }
            // else, don't make a decision here. user could have faked Referer for privacy.
        }

        // no Origin, Referer missing or not matched. cookie or param is missing.

        if(hasParam) // && !hasCookie
        {
            // most likely user disables cookie
            throw new CsrfException("No CSRF token ["+csrfTokenName+"] in cookies");
        }
        else // no param in form.
        {
            // either attacker submitted the form without the csrf field,
            // or app did not employ csrf token in the form
            throw new CsrfException("No CSRF token ["+csrfTokenName+"] in the form");
            // if hasCookie
            //     attacker submitted the form without the csrf field.
            //     or app problem: csrf token was used in prev forms, but not this form.
            // if !hasCookie
            //     attacker submitted the form without the csrf field, before user visited our form
            //     or app problem: csrf token was not used in any form
        }
    }

    static boolean has(String string)
    {
        return string!=null && !string.isEmpty();
    }

}
