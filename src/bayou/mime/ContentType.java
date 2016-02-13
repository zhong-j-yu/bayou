package bayou.mime;

import _bayou._str._CharDef;
import _bayou._str._StrUtil;

import java.util.*;

/**
 * Content-Type of MIME body.
 * <p>
 *     A ContentType contains type, subtype, and parameters. The type, subtype, and parameter names
 *     are all converted to lower case.
 * </p>
 * <p>
 *     The Content-Type header is originally defined in
 *     <a href="http://www.ietf.org/rfc/rfc2045.txt">RFC2045</a>.
 *     This implementation follows
 *     <a href="http://tools.ietf.org/html/rfc2616#section-14.17">RFC2616 &sect;14.17 Content-Type</a>.
 * </p>
 * <p>
 *     This class is immutable.
 * </p>
 *
 */
// main usage is as a container of type and subtype. many codes care only about these two components.
//     don't want to pass around raw fused Content-Type string, which may also contain parameters.
// another benefit is to auto quote parameter values when necessary.
//
// initially defined in rfc2045. rfc2616 also defines it in http context. we follow rfc2616 here.
public class ContentType
{
    /** ContentType of <code>"text/plain"</code>. */
    static public final ContentType text_plain          = new ContentType("text","plain");
    /** ContentType of <code>"text/plain;charset=US-ASCII"</code>. */
    static public final ContentType text_plain_US_ASCII = new ContentType("text","plain","charset","US-ASCII");
    /** ContentType of <code>"text/plain;charset=ISO-8859-1"</code>. */
    static public final ContentType text_plain_ISO88591 = new ContentType("text","plain","charset","ISO-8859-1");
    /** ContentType of <code>"text/plain;charset=UTF-8"</code>. */
    static public final ContentType text_plain_UTF_8    = new ContentType("text","plain","charset","UTF-8");
    /** ContentType of <code>"text/html;charset=UTF-8"</code>. */
    static public final ContentType text_html_UTF_8    = new ContentType("text","html","charset","UTF-8");
    /** ContentType of <code>""application/octet-stream""</code>. */
    static public final ContentType application_octet_stream  = new ContentType("application","octet-stream");

    /** ContentType of <code>"application/json"</code>. */
    static public final ContentType json          = new ContentType("application","json");
    /** ContentType of <code>"application/json;charset=UTF-8"</code>. */
    static public final ContentType json_UTF_8    = new ContentType("application","json","charset","UTF-8");


    final String type;     // lower case
    final String subtype;  // lower case
    final String types;    // type/subtype
    final Map<String,String> parameters;  // read only. all keys in lower case. lookup is case-sensitive
    final String string;

    /**
     * The type, in lower case.
     * <p>
     *     CAUTION: This does not include the subtype. For both type and subtype, see {@link #types()}.
     * </p>
     */
    public String type(){ return type; }

    /**
     * The subtype, in lower case.
     */
    public String subtype(){ return subtype; }

    /**
     * The type and subtype, joined by "/". Example return value: "text/plain". Always in lower case.
     */
    public String types(){ return types; }

    /**
     * The parameters as a Map.
     * <p>
     *     The returned Map is immutable with only lower-case keys.
     * </p>
     */
    public Map<String,String> params(){ return parameters; }

    /**
     * Get the value of the parameter with the name.
     * <p>
     *     The name should be in lower-case.
     * </p>
     */
    public String param(String name){ return parameters.get(name); }

    /**
     * The string form of this ContentType, for example, <code>"text/html;charset=UTF-8"</code>.
     */
    public String toString(){ return string; }

    /**
     * Create a ContentType, with no parameters.
     */
    public ContentType(String type, String subtype)
    {
        this.type = token(type);
        this.subtype = token(subtype);
        this.parameters = Collections.emptyMap();

        this.types = type+"/"+subtype;
        this.string = this.types;
    }

    /**
     * Create a ContentType, with a single parameter.
     */
    // we optimize for single param; it's a common case.
    // performance 300ns @ 2.5GHz new ContentType("text", "plain", "charset", "UTF-8");
    public ContentType(String type, String subtype, String parameterName, String parameterValue)
    {
        StringBuilder sb = new StringBuilder();

        this.type = token(type);
        this.subtype = token(subtype);

        sb.append(this.type).append('/').append(this.subtype);
        this.types = sb.toString();

        String name = token(parameterName);
        checkValue(parameterValue);
        this.parameters = Collections.singletonMap(name, parameterValue);
        sb.append(';').append(name).append('=').append(_StrUtil.mayQuote(parameterValue));

        this.string = sb.toString();
    }

    /**
     * Create a ContentType.
     */
    public ContentType(String type, String subtype, Map<String,String> parameters)
    {
        this.type = token(type);
        this.subtype = token(subtype);
        StringBuilder sb = new StringBuilder();
        sb.append(this.type).append('/').append(this.subtype);
        this.types = sb.toString();

        if(parameters==null || parameters.isEmpty())
            this.parameters = Collections.emptyMap();
        else
        {
            HashMap<String,String> map = new HashMap<>();
            for(Map.Entry<String,String> entry : parameters.entrySet())
            {
                String name = token(entry.getKey());
                String value = entry.getValue();
                checkValue(value);
                map.put(name,value);
                sb.append(';').append(name).append('=').append(_StrUtil.mayQuote(value));
            }
            this.parameters = Collections.unmodifiableMap(map);
        }
        this.string = sb.toString();
    }


    /**
     * Parse a string into a ContentType.
     * <p>
     *     Example input string: <code>"application/x-www-form-urlencoded"</code>
     * </p>
     * @throws IllegalArgumentException if the input string is not a valid Content-Type value.
     */
    public static ContentType parse(String contentTypeString) throws IllegalArgumentException
    {
        return new ContentType(contentTypeString);  // hide this constructor to avoid confusion
    }

    // parsing is not strict
    // parse a content type string, which contains type, subtype, and maybe parameters.
    // usually this is for parsing Content-Type in a POST request from a browser, which has no parameters.
    // performance 130ns @ 2.5GHz parse("application/x-www-form-urlencoded")
    ContentType(String string) throws IllegalArgumentException
    {
        this.string = string; // preserve original string

        int idx = string.indexOf('/');
        assure(idx != -1);
        type = token(string.substring(0, idx));

        string = string.substring(idx+1);
        idx = string.indexOf(';');
        if(idx==-1) // pretty common
        {
            subtype = token(string);
            types = type+"/"+subtype;
            parameters = Collections.emptyMap();
            return;
        }

        subtype = token(string.substring(0, idx));
        types = type+"/"+subtype;

        HashMap<String,String> map = new HashMap<>();
        TokenParams.parseParams(string, idx, string.length(), map, false); // very loose parser.
        for(Map.Entry<String,String> entry : map.entrySet())
        {
            String name = entry.getKey(); // already trimmed and lower-cased, non-empty.
            token(name); // check chars
            String value = entry.getValue();
            checkValue(value);
        }
        parameters = Collections.unmodifiableMap(map);
    }





    static void assure(boolean condition)
    {
        if(!condition)
            throw new IllegalArgumentException("invalid Content-Type format");
    }
    String token(String token)
    {
        assure(token != null);

        token = token.trim();
        assure(!token.isEmpty());
        assure(_CharDef.check(token, _CharDef.Http.tokenChars));

        return _StrUtil.lowerCase(token);
    }
    void checkValue(String value)
    {
        // parameter value is token or quoted-string.
        // after un-quote and unescape, quoted-string becomes *(HT / SP / VCHAR / obs-text)
        assure(value != null);

        // empty is ok. no trim - preserve spaces.
        assure(_CharDef.check(value, _CharDef.Http.textChars));
    }
    // note: when checking chars we follow HTTP rfc2616

}
