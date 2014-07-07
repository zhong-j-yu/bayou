package bayou.http;

import _bayou._tmp._CharDef;
import _bayou._tmp._HttpDate;
import _bayou._tmp._HttpUtil;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Http response cookie. Modeled on <a href="http://tools.ietf.org/html/rfc6265">RFC6265</a>.
 * <p>
 *     This class represents cookies in http <i>responses</i>; see {@link bayou.http.HttpResponse#cookies()}.
 * </p>
 * <p>
 *     This class is immutable.
 * </p>
 * <h4 id=properties>Cookie Properties</h4>
 * <dl>
 *
 * <dt id=name>name</dt>
 * <dd>
 * <p>
 *     Must be non-null and non-empty. Case sensitive.
 * </p>
 * <p>
 *     Legal chars in cookie names:
 *     <code style="color:blue;font-weight:bold;">a-z A-Z 0-9 ! # $ % &amp; ' * + - . ^ _ ` | ~ </code>
 * </p>
 * <p>
 *     Cookie names should be unique.
 *     Although the full identity of a cookie is triplet <code>{domain,path,name}</code>,
 *     only the cookie name is sent back in http requests.
 *     Therefor to avoid confusion, the server should not create two cookies with the same name
 *     but different domain/path.
 * </p>
 * </dd>
 *
 * <dt id=value>value</dt>
 * <dd>
 * <p>
 *     Must be non-null; can be empty. Case sensitive.
 * </p>
 * <p>
 *     Legal chars in cookie values:
 *     <code style="color:blue;font-weight:bold;">0x21-0x7E <i style="color:red">except</i> " , ; \</code>
 * </p>
 * </dd>
 *
 * <dt id=maxAge>maxAge</dt>
 * <dd>
 * <p>
 *     If <code>maxAge==null</code>, this is a session cookie.
 *     See also the sentinel value {@link #SESSION Cookie.SESSION}.
 * </p>
 * <p>
 *     If <code>maxAge&lt;=0</code>, the client should delete the cookie immediately.
 *     See also the sentinel value {@link #DELETE Cookie.DELETE}.
 * </p>
 * <p>
 *     If <code>maxAge&gt;0</code>,
 *     this is a persistent cookie which the client should store for the specified duration.
 *     The precision is 1 second.
 * </p>
 * </dd>
 *
 * <dt id=domain>domain</dt>
 * <dd>
 * <p>
 *     If <code>domain==null</code>, the cookie only applies to the request host.
 *     For example, if the request is to <code>"http://example.com:8080/xyz"</code>,
 *     the response cookie only applies to future requests to <code>example.com</code> (not any subdomains of).
 *     It's ok if the request host is an IP address.
 *     The <code>null</code> domain is appropriate for many server apps.
 * </p>
 * <p>
 *     If non-null, <code>domain</code> must be a valid domain name;
 *     it must be the same as the request host, or a parent domain of the request host;
 *     it cannot be a "public suffix"; it cannot be an IP address.
 *     The cookie applies to future requests to the same domain or subdomains of.
 * </p>
 * <p>
 *     For example, if the request is to <code>"http://s1.example.com:8080/xyz"</code>,
 *     these would  be <b>invalid</b> response cookie domains:
 *     <code>"foo.com", ".com", "s2.example.com", ".example.com"</code>.
 *     A <b>valid</b> response cookie domain would be <code>"example.com"</code>,
 *     in which case, the cookie applies to future requests to
 *     <code>"example.com", "s1.example.com", "s2.example.com", "a.b.c.example.com",</code> etc.
 * </p>
 * <p>
 *     The server port and the URI scheme are irrelevant when matching a cookie with a request.
 *     For example, if the request is to <code>"http://example.com:8080"</code>,
 *     the response cookie could be applicable to a future request to
 *     <code>"https://example.com:8433"</code>.
 * </p>
 * <p>
 *     Domain is case insensitive; this class converts it to lower case.
 * </p>
 * </dd>
 *
 * <dt id=path>path</dt>
 * <dd>
 * <p>
 *     We strongly recommend that apps always use "/" as the cookie path;
 *     then you don't need to read the following details.
 * </p>
 * <p>
 *     If <code>path==null</code>, it's equivalent to the "default-path",
 *     which is derived from the request URI. For example, if the request URI
 *     is <code>"/abc/xyz.html?q=p"</code>, the default-path for cookies is <code>"/abc"</code>.
 * </p>
 * <p>
 *     If non-null, <code>path</code> must be a valid URI path;
 *     it must start with "/"; it must not contain ";" chars.
 *     <!-- response cookie path does not need to match request uri path. -->
 * </p>
 * <p>
 *     Path is case sensitive.
 *     Trailing slash matters: <code>"/abc"</code> and <code>"/abc/"</code> are different cookie paths;
 *     <!-- both in storage model and in path-matches (cookie path "/abc/" does not match request path "/abc") -->
 *     we recommend not to include any trailing slash.
 * </p>
 * <p>
 *     The cookie applies to future requests that match the path
 *     (the request uri path is either the cookie path, or a sub-path of).
 * </p>
 * <p>
 *     We strongly recommend that apps always use "/" as the cookie path,
 *     which matches all request URIs.
 * </p>
 * </dd>
 *
 * <dt id=secure>secure</dt>
 * <dd>
 * <p>
 *     A <code>boolean</code> value.
 * </p>
 * </dd>
 *
 * <dt id=httpOnly>httpOnly</dt>
 * <dd>
 * <p>
 *     A <code>boolean</code> value.
 * </p>
 * </dd>
 *
 * </dl>
 */

// http cookie, modeled on RFC 6265. (other related RFCs are obsolete)

// in this class we use Max-Age instead of Expires, since relative duration is usually preferred by apps.
// caution: java.net.HttpCookie maxAge semantics is different from the RFC and this class.
// Max-Age browser support isn't universal yet. When generating Set-Cookie, we'll try both Max-Age and Expires.

// we don't use java.net.HttpCookie or javax.servlet.http.Cookie, they are messy, based on obsolete RFCs.
//   we want a simple, immutable, fully validated object.

// why name it Cookie instead of HttpCookie: it's shorter. "cookie" is widely understood to mean http cookie.
//     we don't want to conflict with java.net.HttpCookie. (we don't worry about conflicting with
//     javax.servlet.http.Cookie since it's unlikely in user's classpath)
public class Cookie
{
    /**
     * A sentinel value for cookie <a href="#maxAge">maxAge</a>,
     * meaning the cookie is a session cookie.
     * <p>
     *     The value of this field is <code>null</code>.
     * </p>
     */
    public static final Duration SESSION = null;

    /**
     * A sentinel value for cookie <a href="#maxAge">maxAge</a>,
     * meaning the cookie should be deleted.
     * <p>
     *     The value of this field is <code>Duration.ZERO</code>.
     * </p>
     */
    public static final Duration DELETE = Duration.ZERO;

    final String name;
    final String value;
    final Duration maxAge;
    final String domain;  // null, or lower cased (for easier comparison)
    final String path;
    boolean secure;
    boolean httpOnly;


    /**
     * Create a cookie.
     * <p>
     *     Values for other properties: <code>domain=null, path="/", secure=false, httpOnly=false</code>.
     * </p>
     */
    public Cookie(String name, String value, Duration maxAge)
    {
        this(name, value, maxAge, null, "/", false, false);
    }

    /**
     * Create a cookie.
     * <p>
     *     See <a href="#properties">properties</a> for requirements on the parameters.
     * </p>
     */
    // we do pretty strict validations here
    public Cookie(String name, String value, Duration maxAge,
                  String domain, String path, boolean secure, boolean httpOnly)
    {
        checkName(name);
        checkValue(value);
        checkMaxAge(maxAge);
        checkPath(path);

        if (domain!=null)
            domain = domain.toLowerCase();
        checkDomain(domain);
        // it does not allow IP (v4 or v6). server must not use IP for Domain in Set-Cookie, so that's fine.
        // however, on client side, if the request host is an IP(4/6) (in which case response Set-Cookie must not
        // specify Domain) client uses the request host IP as cookie domain. this class can't be used for that. TBD

        this.name = name;
        this.value = value;
        this.maxAge = maxAge;
        this.domain = domain;
        this.path = path;
        this.secure = secure;
        this.httpOnly = httpOnly;
    }

    /**
     * The <a href="#name">name</a> of the cookie.
     */
    public String name()
    {
        return name;
    }

    /**
     * The <a href="#value">value</a> of the cookie.
     */
    public String value()
    {
        return value;
    }

    /**
     * The <a href="#maxAge">maxAge</a> of the cookie.
     */
    public Duration maxAge()
    {
        return maxAge;
    }
    boolean expired()
    {
        return maxAge!=null && maxAge.getSeconds()<=0;
    }

    /**
     * The <a href="#domain">domain</a> of the cookie.
     * <p>
     *     This method returns a lower case string, if <code>domain!=null</code>.
     * </p>
     */
    public String domain()
    {
        return domain;
    }

    /**
     * The <a href="#path">path</a> of the cookie.
     */
    public String path()
    {
        return path;
    }

    /**
     * The <a href="#secure">secure</a> property of the cookie.
     */
    public boolean secure()
    {
        return secure;
    }

    /**
     * The <a href="#httpOnly">httpOnly</a> property of the cookie.
     */
    public boolean httpOnly()
    {
        return httpOnly;
    }


    boolean sameId(Cookie that)
    {
        return Objects.equals(this.name,   that.name)
            && Objects.equals(this.domain, that.domain)
            && Objects.equals(this.path,   that.path);
    }

    /**
     * Convert to string, as the cookie would appear in a Set-Cookie response header.
     * <p>
     *     Example string:
     * </p>
     * <pre>
     *     name=value; Path=/; Expires=Fri, 21 Dec 2012 00:00:00 GMT; HttpOnly
     * </pre>
     */
    public String toSetCookieString()
    {
        StringBuilder sb = new StringBuilder();

        sb.append(name).append('=').append(value);

        if(domain!=null)
            sb.append("; Domain=").append(domain);

        if(path!=null)
            sb.append("; Path=").append(path);

        if(maxAge!=null)
        {
            long seconds = maxAge.getSeconds();
            if(seconds==0 && maxAge.getNano()>0) // positive value less than 1 second
                seconds = 1;

            if(seconds>0)
            {
                sb.append("; Max-Age=").append(seconds);
                // Max-Age is more accurate since it's relative to client clock,
                // however it's not supported by all. so we set Expires too.

                Instant expires = Instant.now();
                try
                {
                    expires = expires.plusSeconds(seconds);
                }
                catch (Exception e)
                {
                    expires = Instant.MAX;
                }
                // Expires only allows 4 digit year. toHttpDate() takes care of that.
                sb.append("; Expires=").append(_HttpDate.toHttpDate(expires));
            }
            else // delete cookie
            {
                // note: per RFC, server cannot generate a negative Max-Age (tho client must accept a negative one)
                // use Expires attr, with a date in the distant past to overcome server-client clock difference
                sb.append("; Expires=").append("Fri, 21 Dec 2012 00:00:00 GMT");
            }
        }
        // else maxAge==null, session cookie, no Max-Age/Expires

        if(secure)
            sb.append("; Secure");

        if(httpOnly)
            sb.append("; HttpOnly");

        return sb.toString();
    }

    static void checkName(String name) throws IllegalArgumentException
    {
        if(name==null)
            throw new IllegalArgumentException("cookie name cannot be null");
        if(name.isEmpty())
            throw new IllegalArgumentException("cookie name cannot be empty");
        if(!_CharDef.check(name, _CharDef.Cookie.nameChars))
            throw new IllegalArgumentException("invalid cookie name: "+name);
        // can cookie name be one of the attribute names, e.g. Expires? seems ok.
    }

    static void checkValue(String value) throws IllegalArgumentException
    {
        if(value==null)
            throw new IllegalArgumentException("cookie value cannot be null");
        // empty value is ok
        if(!_CharDef.check(value, _CharDef.Cookie.valueChars))
            throw new IllegalArgumentException("invalid cookie value: "+value);
        // RFC6265 allows DQUOTE pair around value. seems no good reason for server app to do that.
        // we are stricter here and don't accept it.
    }

    static void checkMaxAge(Duration maxAge)
    {
        // it should not exceed year 9999
        // we don't need to enforce it here. if it overflows, silently adjust it to year 9999.
    }

    static void checkPath(String path) throws IllegalArgumentException
    {
        if(path==null)
            return;
        if(!checkPath2(path))
            throw new IllegalArgumentException("invalid cookie path: "+path);
    }
    static boolean checkPath2(String path)
    {
        return path.indexOf(';')==-1 // ";" is excluded per cookie spec, since ";" is to separate cookie attrs.
            && path.indexOf('?')==-1 // no query
            && _HttpUtil.isOriginFormUri(path);
    }

    static void checkDomain(String domain) throws IllegalArgumentException
    {
        if(domain==null)
            return;
        if(!checkDomain2(domain))
            throw new IllegalArgumentException("invalid cookie domain: "+domain);
        // domain must not be a public suffix, but we aren't checking that here
    }
    static boolean checkDomain2(String domain)
    {
        // syntax:
        // http://tools.ietf.org/html/rfc1123#page-13
        // http://tools.ietf.org/html/rfc1034#section-3.5
        // 1 or more labels separated by ".". each label contains 1 or more letter/digit/hyphen.
        // each label cannot start/end with hyphen. last label cannot be all digits.

        int state=0;
        boolean allDigits=true;
        for(int i=0; i<domain.length(); i++)
        {
            char c = domain.charAt(i);
            if(c>0xff)
                return false;

            allDigits = allDigits && '0'<=c && c<='9';

            if(_CharDef.check(c, _CharDef.alphaDigitChars))
                state=1;
            else if(c=='-' && state!=0)
                state=2;
            else if(c=='.' && state==1)
            {   state=0; allDigits=true;   }
            else
                return false;
        }
        return state==1 && !allDigits;
    }


    // util for request Cookie header -----------------------------------------------------------------


    // parse the value of Cookie request header. e.g. n1=v1; n2=v2
    // cookie names are case sensitive.
    // if same name appears multiple times, the last value is in the map. no good reason either way
    //     see http://tools.ietf.org/html/rfc6265#section-5.4
    // we simply split on ";" to find cookie-pairs. invalid cookie-pair are skipped
    static Map<String,String> parseCookieHeader(String hCookie)
    {
        if(hCookie==null)
            return Collections.emptyMap();

        HashMap<String,String> map = new HashMap<>();
        int iS=0;
        while(iS<hCookie.length())
        {
            int iA = iS;
            int iB = hCookie.indexOf(';', iA);
            if(iB==-1)
                iB = hCookie.length();
            iS = iB+1;  // next start index

            int iE = hCookie.indexOf('=', iA);
            if(iE==-1 || iE>=iB)
                continue;
            String name = hCookie.substring(iA, iE).trim();
            String value = hCookie.substring(iE+1, iB).trim();
            if(!_validNameValue(name, value))
                continue;
            map.put(name, value);
        }
        return map;
    }
    static boolean _validNameValue(String name, String value)
    {
        return !name.isEmpty() &&
            _CharDef.check(name, _CharDef.Cookie.nameChars) &&
            _CharDef.check(value, _CharDef.Cookie.valueChars);
    }

    // modify a Cookie header. (null means no Cookie header. spec doesn't allow an empty Cookie header)
    // if value==null, remove the cookie
    // if value!=null, add/change the cookie
    static String modCookieHeader(String hCookie, String cookieName, String cookieValue) throws IllegalArgumentException
    {
        checkName(cookieName);
        if(cookieValue!=null)
            checkValue(cookieValue);

        if(hCookie==null)
        {
            if(cookieValue==null)
                return null;
            return cookieName +"="+ cookieValue;
        }

        StringBuilder sb = new StringBuilder();  //  *( ; SP name=value )
        int iS=0;
        while(iS<hCookie.length())
        {
            int iA = iS;
            int iB = hCookie.indexOf(';', iA);
            if(iB==-1)
                iB = hCookie.length();
            iS = iB+1;  // next start index

            int iE = hCookie.indexOf('=', iA);
            if(iE==-1 || iE>=iB)
                continue;
            String name = hCookie.substring(iA, iE).trim();
            if(name.isEmpty())
                continue;

            if(!name.equals(cookieName)) // some other cookie, keep as is
            {
                String value = hCookie.substring(iE+1, iB).trim();
                sb.append("; ").append(name).append('=').append(value);
            }
            // otherwise, skip it
        }
        if(cookieValue!=null)
            sb.append("; ").append(cookieName).append('=').append(cookieValue);

        if(sb.length()==0)
            return null;
        else
            return sb.substring(2); // remove the leading ( ; SP )
    }

    static String makeCookieHeader(Map<String,String> cookies) throws IllegalArgumentException
    {
        if(cookies==null || cookies.isEmpty())
            return null;

        StringBuilder sb = new StringBuilder();
        for(Map.Entry<String,String> entry : cookies.entrySet())
        {
            String name = entry.getKey();
            String value = entry.getValue();
            checkName(name);
            checkValue(value);
            if(sb.length()>0)
                sb.append("; ");
            sb.append(name).append('=').append(value);
        }
        return sb.toString();
    }



}
