package bayou.form;

import _bayou._http._SimpleRequestEntity;
import _bayou._tmp._Exec;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.http.HttpEntity;
import bayou.http.HttpRequest;
import bayou.http.HttpRequestImpl;
import bayou.mime.ContentType;
import bayou.util.OverLimitException;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Html form data set.
 * <p>
 *     A form data set contains a list of entries. Each entry has a key and a value.
 * </p>
 * <p>
 *     The entry key is case sensitive; it should not contain '\r' or '\n'.
 *     Multiple entries can have the same name.
 * </p>
 * <p>
 *     There are two types of entries: <i>parameter entry</i> where the value is a String,
 *     <i>file entry</i> where the value is a file represented by {@link FormDataFile}.
 * </p>
 * <p>
 *     This class also contains form attributes <code>method, action, charset, enctype</code>.
 * </p>
 * <p>
 *     <code>FormData</code> can usually be used in two ways:
 * </p>
 * <p>
 *     <b>Parse form data from an http request: </b>
 *     call {@link #parse(CharSequence) parse(uri)} or {@link #parse(HttpRequest) parse(request)}
 *     to get a FormData, then read and process parameters and files.
 * </p>
 * <pre>
 *     FormData.parse(request)
 *         .then( (FormData fd)-&gt;
 *         {
 *             print(fd.param("n1");
 *             ...
 *         })
 * </pre>
 * <p>
 *     <b>Create an http request from a FormData:</b> create a <code>FormData</code>,
 *     populate parameters and files, then convert it
 *     {@link #toUri()} or {@link #toRequest()}.
 * </p>
 * <pre>
 *     new FormData("POST", "http://localhost:8080/submit")
 *         .param("n1", "v1")
 *         .file("f1", "/tmp/100.txt")
 *         .toRequest();
 * </pre>
 */
// html 4.01 has no restrictions on entry keys and parameter values. we allow any unicode here.
// however, if an entry key contains CR or LF, it'll fail when encoded in multipart/form-data.
// similarly, fileName should not contain CR or LF.
// we don't check entry key or file names here - illegal chars are highly unlikely.
public class FormData
{
    /**
     * The <code>"multipart/form-data"</code> enctype. This is the recommended enctype.
     */
    public static final String ENC_MULTIPART = "multipart/form-data";

    /**
     * The <code>"application/x-www-form-urlencoded"</code> enctype.
     */
    public static final String ENC_URLENCODED = "application/x-www-form-urlencoded";

    static final ContentType contentTypeUrlEncoded = ContentType.parse(ENC_URLENCODED);


    String method; // GET/POST only
    String action;    // absolute or path-query
    Charset charset;  // should be ASCII-compatible.
    String enctype;   // ignored for GET

    /**
     * Create a FormData.
     * <p>
     *     The method must be "GET" or "POST".
     *     The action must be an {@link bayou.http.HttpRequest#absoluteUri() absolute URI}
     *     or {@link bayou.http.HttpRequest#uri() path-query}.
     *     For example
     * </p>
     * <pre>
     *     new FormData("GET", "https://example.com/search")
     *
     *     new FormData("POST", "/submit")
     * </pre>
     * <p>
     *     This constructor sets <code>charset=UTF-8, enctype="multipart/form-data"</code>.
     *     They can be changed by {@link #charset(Charset)} and {@link #enctype(String)}.
     * </p>
     */
    public FormData(String method, String action)
    {
        this(method, action, UTF_8, ENC_MULTIPART);
    }

    // not public yet. use new FormData().charset(..).enctype(..)
    FormData(String method, String action, Charset charset, String enctype)
    {
        this.method(method);
        this.action(action);
        this.charset(charset);
        this.enctype(enctype);
    }

    /**
     * The form's method, either "GET" or "POST".
     */
    public String method()
    {
        return method;
    }

    /**
     * The form's action.
     * <p>
     *     The action is an {@link bayou.http.HttpRequest#absoluteUri() absolute URI}
     *     or {@link bayou.http.HttpRequest#uri() path-query},
     *     for example
     * </p>
     * <pre>
     *     "https://example.com/search"
     *
     *     "/submit"
     * </pre>
     */
    public String action()
    {
        return action;
    }

    /**
     * The form's charset, used for converting between chars and bytes.
     */
    public Charset charset()
    {
        return charset;
    }

    /**
     * The form's enctype, either <code>"multipart/form-data"</code>
     * or <code>"application/x-www-form-urlencoded"</code>.
     * @see #ENC_MULTIPART
     * @see #ENC_URLENCODED
     */
    public String enctype()
    {
        return enctype;
    }

    // all parameter/file entries
    // user can directly read/write the 2 maps.  an entry name can appear in both maps

    final LinkedHashMap<String,List<String>> parameters = new LinkedHashMap<>();

    /**
     * Get all parameter entries.
     * <p>
     *     The caller can freely modify the Map and the Lists within.
     * </p>
     */
    public Map<String,List<String>> params()
    {
        return parameters;
    }

    /**
     * Get values of the parameter.
     * <p>
     *     Return an empty list if there are no parameter entries with the key.
     * </p>
     * <p>
     *     The returned List should be treated as read-only.
     * </p>
     */
    public List<String> params(String key)
    {
        List<String> list = parameters.get(key);
        if(list==null)
            return Collections.emptyList();
        else
            return (list);
    }

    /**
     * Get the value of the parameter; null if none.
     * <p>
     *     The caller expects only one value for this parameter.
     *     If there are multiple values, the last one is returned.
     * </p>
     */
    public String param(String name)
    {
        List<String> list = parameters.get(name);
        return last(list);
    }

    static <T> T last(List<T> list)
    {
        if(list==null)
            return null;
        int size = list.size();
        if(size==0)
            return null;
        return list.get(size-1);
    }


    final LinkedHashMap<String,List<FormDataFile>> files = new LinkedHashMap<>();

    /**
     * Get all file entries.
     * <p>
     *     The caller can freely modify the Map and the Lists within.
     * </p>
     */
    public Map<String,List<FormDataFile>> files()
    {
        return files;
    }

    /**
     * Get files under the key.
     * <p>
     *     Return an empty list if there are no file entries with the key.
     * </p>
     * <p>
     *     The returned List should be treated as read-only.
     * </p>
     */
    // caution: name is entry name, not file name
    public List<FormDataFile> files(String key)
    {
        List<FormDataFile> list = files.get(key);
        if(list==null)
            return Collections.emptyList();
        else
            return (list);
    }

    /**
     * Get the file under the key; null if none.
     * <p>
     *     The caller expects only one file under the key.
     *     If there are multiple files, the last one is returned.
     * </p>
     */
    public FormDataFile file(String key)
    {
        List<FormDataFile> list = files.get(key);
        return last(list);
    }

    /**
     * Return the total number of files.
     */
    public int fileCount()
    {
        int count = 0;
        for(List<FormDataFile> list : files.values())
            count += list.size();
        return count;
    }

    /**
     * Delete all files in this FormData from local file system.
     * <p>
     *     This method is non-blocking; errors will be silently logged.
     * </p>
     * @see FormDataFile#delete()
     */
    public void deleteFiles()
    {
        _Exec.executeB(() -> {
            for (List<FormDataFile> list : files.values())
                for (FormDataFile ff : list)
                    FormDataFile.del(ff.localPath);
        });
    }

    // convenience methods to add entries ------------------------------------------------------
    // no methods for replace/remove entries - not often needed when building a form.
    // user can manipulate parameters()/files() instead

    FormData method(String method)
    {
        _Util.require(method.equals("GET")||method.equals("POST"), "method is GET or POST");
        this.method = method;
        return this;
    }

    FormData action(String action)
    {
        // not checked
        this.action = action;
        return this;
    }

    /**
     * Set the charset for this form.
     * @return this
     */
    public FormData charset(Charset charset)
    {
        this.charset = charset;
        return this;
    }

    /**
     * Set the enctype for this form.
     * <p>
     *     The enctype must be either <code>"multipart/form-data"</code>
     *     or <code>"application/x-www-form-urlencoded"</code>.
     *     We recommend <code>"multipart/form-data"</code> for all forms.
     * </p>
     * @return this
     * @see #ENC_MULTIPART
     * @see #ENC_URLENCODED
     */
    public FormData enctype(String enctype)
    {
        _Util.require( enctype.equals(ENC_MULTIPART) || enctype.equals(ENC_URLENCODED),
            "enctype is either application/x-www-form-urlencoded or multipart/form-data");

        this.enctype = enctype;
        return this;
    }


    /**
     * Add a parameter entry.
     * <p>
     *     If value is null, this method has no effect.
     * </p>
     * <p>
     *     Previous values under the same key will <b>not</b> be removed.
     * </p>
     * @return this
     */
    public FormData param(String key, Object value)
    {
        if(value==null)  // ok
            return this;

        List<String> list = parameters.get(key);
        if(list==null)
            parameters.put(key, list = new ArrayList<>(2)); // usually 1 or a few
        list.add(value.toString());
        return this;
    }
    /**
     * Add multiple parameter entries.
     * <p>
     *     Any <code>null</code> in <code>values</code> will be ignored.
     * </p>
     * <p>
     *     Previous values under the same key will <b>not</b> be removed.
     * </p>
     * @return this
     */
    // overload ambiguity: params(key) refers to the read method. fine, app unlikely means it for this method.
    // even if app does mean this method, it's ok since `values` is empty anyway so there's no side effect.
    public FormData params(String key, Object... values)
    {
        List<String> list = parameters.get(key);
        if(list==null)
            parameters.put(key, list = new ArrayList<>(values.length));
        for(Object value : values)
            if(value!=null)  // nulls are skipped
                list.add(value.toString());
        return this;
    }

    //  //  //

    /**
     * Add a file entry.
     * <p>
     *     This method is equivalent to
     *     <code>file(key, FormDataFile.of(filePath))</code>.
     *     See {@link #file(String, FormDataFile)}.
     * </p>
     * <p>
     *     CAUTION: this method reads file metadata from OS, which may involve blocking IO operations.
     *     See {@link FormDataFile#of(String)}.
     * </p>
     * @return this
     */
    // mostly for testing. may read disk, may block, may throw.
    public FormData file(String key, String filePath)
    {
        return file(key, FormDataFile.of(filePath));
    }

    /**
     * Add a file entry.
     * <p>
     *     Previous files under the same entry key will <b>not</b> be removed.
     * </p>
     * @return this
     */
    public FormData file(String key, FormDataFile ff)
    {
        List<FormDataFile> list = files.get(key);
        if(list==null)
            files.put(key, list = new ArrayList<>(1)); // usually just 1
        list.add(ff);
        return this;
    }


    // generate request ------------------------------------------------------

    /**
     * Create a URI containing the form data.
     * <p>
     *     The form data is encoded in the query component of the URI
     *     as <code>"application/x-www-form-urlencoded"</code>.
     * </p>
     * <p>
     *     For example:
     * </p>
     * <pre>
     *     new FormData("GET", "http://example.com/query?a=b").param("n","v").toUri();
     *     // return "http://example.com/query?n=v"
     *     // note that the existing query in `action` is dropped.
     *
     *     new FormData("POST", "/submit").param("n","v").toUri();
     *     // return "/submit?n=v"
     * </pre>
     * <p>
     *     The form's <code>method</code> and <code>enctype</code> are irrelevant here.
     * </p>
     */
    public String toUri()
    {
        String host_path = action;
        int q = host_path.indexOf('?');
        if(q!=-1)
            host_path = host_path.substring(0, q); // remove existing query

        DoGenUrlEncoded enc = new DoGenUrlEncoded(charset, parameters, files);
        if(enc.ib==0) // no query
            return host_path;

        String query = new String(enc.bytes, 0, enc.ib, StandardCharsets.ISO_8859_1);
        return host_path + '?' + query;
    }

    /**
     * Create an http request that submits the form data.
     * <p>
     *     The request method is the same as the form's {@link #method() method}.
     * </p>
     * <p>
     *     If the method is GET, the form data is encoded in request URI, see {@link #toUri()}.
     * </p>
     * <p>
     *     If the method is POST, the form data is encoded in request entity, according to the enctype.
     * </p>
     * <p>
     *     Example usage:
     * </p>
     * <pre>
     *     new FormData("GET", "/show")
     *         .params("a", 1, 2)
     *         .toRequest()
     *         .host("localhost:8080");
     *
     *     new FormData("POST", "http://example.com/submit")
     *         .params("a", 1, 2)
     *         .toRequest()
     *         .header("Origin", "http://example.com");
     * </pre>
     * <p>
     *     The caller can make further modifications to the returned {@link HttpRequestImpl} object.
     * </p>
     * <p>
     *     If the form's {@link #action()} is not an absolute URI,
     *     the caller should set the request {@link bayou.http.HttpRequestImpl#host(String) host} afterwards.
     * </p>
     * <p>
     *     To avoid a POST request being
     *     <a href="../form/FormParser.html#csrf">treated as CSRF by FormParser</a>,
     *     the simplest way is to set the "Origin" header.
     * </p>
     *
     *
     */
    public HttpRequestImpl toRequest()
    {
        if(method.equals("GET"))
        {
            return new HttpRequestImpl("GET", toUri(), null);
        }
        else // POST
        {
            return new HttpRequestImpl("POST", action, toHttpEntity());
        }
    }

    HttpEntity toHttpEntity()
    {
        if(enctype.equals(ENC_MULTIPART))
            return new FormDataHttpEntity(parameters, files, charset);

        DoGenUrlEncoded enc = new DoGenUrlEncoded(charset, parameters, files);
        ByteBuffer bb = ByteBuffer.wrap(enc.bytes, 0, enc.ib);
        return new _SimpleRequestEntity(contentTypeUrlEncoded, bb);
    }


    /**
     * Parse the URI for form data.
     * <p>
     *     This method is equivalent to
     *     {@link FormParser#parse(CharSequence)}
     *     with default parser settings.
     * </p>
     */
    public static FormData parse(CharSequence uri) throws ParseException, OverLimitException
    {
        return new FormParser().parse(uri);
    }

    /**
     * Parse the request for POST form data.
     * <p>
     *     This method is equivalent to
     *     {@link FormParser#parse(HttpRequest)}
     *     with default parser settings.
     * </p>
     */
    public static Async<FormData> parse(HttpRequest request)
    {
        return new FormParser().parse(request);
    }

}
