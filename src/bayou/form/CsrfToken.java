package bayou.form;

import _bayou._log._Logger;
import _bayou._tmp._CharDef;
import bayou.html.Html5;
import bayou.http.Cookie;
import bayou.http.CookieJar;

import java.security.SecureRandom;

/**
 * <p>
 *     A name-value pair for <a href="FormParser.html#csrf">CSRF detection</a>.
 * </p>
 * <p>
 *     A CSRF token is a name-value pair that exists both as a form field
 *     and as a cookie in a POST request, so that the server can identify
 *     the request as non-CSRF.
 * </p>
 * <p>
 *     A typical usage in html builder:
 * </p>
 * <pre>
 *     _form().method("POST").action("/addArticle").add(
 *         ...
 *         CsrfToken._input()
 *         ...
 * </pre>
 * <p>
 *     This creates a hidden &lt;input&gt; with a csrf token's name and value,
 *     at the same time, ensures that a cookie exists with the same name and value.
 *     When the form is submitted, the server will see that the csrf token in the form field
 *     agrees with the csrf token in the cookie.
 * </p>
 * <p>
 *     To make sure all POST forms contain CSRF tokens, consider using a convenient builder method:
 * </p>
 * <pre>
     default Html5.FORM _form_post()
     {
         return Html5.html5._form()
             .method("POST")
             .enctype(FormData.ENC_MULTIPART)
             .add( CsrfToken._input() );
     }

    // usage example

         _form_post().action("/logout").add( _button("Log Out") );

        // note: even though this form contains no fields (other than the CSRF token)
        // the server app should still do parse(request) to detect CSRF.

 * </pre>
 */

// only need to check CSRF for form POST.
// it is an historical mistake that an html form post can be cross origin.
// new mechanisms, like XMLHttpRequest, won't commit the same mistake.
//
// csrf token name -  for both cookie name and form field name
//   must be valid cookie name (which makes it a valid field name)
//
// one token can be used for multiple forms in one request scope.
// token object identity isn't important; name is important.
// if there are two forms in one response, they can use same or different csrf token names.
// same token name, same token value. diff name, diff value.

//it's not necessary that server creates the token, set the cookie and form field.
//upon form submission, server only compares csrf cookie and field, both can be generated on client side with javascript.
//so we can provide a js script that create a random csrf token, add a csrf field for each POST form.
//    function setupCsrf()
//    function setupCsrf(form, tokenName)
//see http://appsandsecurity.blogspot.de/2012/01/stateless-csrf-protection.html

public class CsrfToken
{

    /**
     * The default name for CSRF tokens.
     */
    public static final String DEFAULT_NAME = loadDefaultName();

    static String loadDefaultName()
    {
        String name = null;

        String key = CsrfToken.class.getName()+".defaultName";
        String prop = System.getProperty(key);
        if(prop!=null)
        {
            if(isCookieValidName(prop))
                name = prop;
            else
                _Logger.of(CsrfToken.class)
                    .error("Invalid CSRF token name specified by system property %s=%s", key, prop);
        }

        return name!=null ? name : "_csrf_token";
    }

    static boolean isCookieValidName(String name)
    {
        return name!=null && !name.isEmpty()
            && _CharDef.check(name, _CharDef.Cookie.nameChars);
    }
    static void validateTokenName(String csrfTokenName) throws IllegalArgumentException
    {
        if(!isCookieValidName(csrfTokenName))
            throw new IllegalArgumentException("invalid CSRF token name: "+csrfTokenName);
    }




    // note: "&" and "'" are valid chars for cookie names. make sure to escape if necessary.
    final String name;   // usually the default name

    final String value;

    /**
     * Create a CsrfToken with the default name and the default CookieJar.
     * <p>
     *     This constructor is equivalent to
     *     <code>CsrfToken(CsrfToken.DEFAULT_NAME, CookieJar.current())</code>.
     * </p>
     * @see #CsrfToken(String, CookieJar)
     */
    public CsrfToken()
    {
        this(DEFAULT_NAME, false, CookieJar.current()); // no need to validate default name.
    }

    /**
     * Create a CsrfToken.
     * <p>
     *     The CookieJar is used to lookup a cookie with the same name.
     *     If not found, one is created with a random value.
     * </p>
     * <p>
     *     The value of this CsrfToken is the value of that cookie.
     * </p>
     */
    public CsrfToken(String name, CookieJar cookieJar)
    {
        this(name, true, cookieJar);
    }
    CsrfToken(String name, boolean validateName, CookieJar cookieJar)
    {
        if(validateName)
            validateTokenName(name);

        this.name = name;
        this.value = tokenValue(cookieJar, name);
    }

    /**
     * Name of the token.
     */
    public String name()
    {
        return name;
    }

    /**
     * Value of the token.
     */
    public String value()
    {
        return value;
    }


    /**
     * Build an &lt;input&gt; element representing this token.
     * <p>
     *     This method is equivalent to
     *     <code>_input().type("hidden").name(token.name()).value(token.value());</code>
     *     see {@link bayou.html.Html5#_input()}.
     * </p>
     * <p>
     *     This method adds an &lt;input&gt; to the context parent;
     *     the method name starts with underscore to be consistent with html builder methods.
     * </p>
     * <p>
     *     You may want to cast the return value to <code>Html4.INPUT</code>
     *     if you are building a strict HTML4 document.
     * </p>
     */
    // starts with underscore to be consistent to html builder methods. it adds an <input> to context parent.
    public Html5.INPUT _toInput()
    {
        return Html5.html5._input().type("hidden").name(name).value(value);
    }

    /**
     * Make an &lt;input&gt; string representing this token.
     * <p>
     *     An example return value:
     *     <code>&lt;input type="hidden" name="_csrf_token" value="rMNxKwixn7hC"&gt;</code>
     * </p>
     */
    // for apps that don't use html builder.
    // do not call CsrfToken.toHtmlInput(req).render(0) which adds <input> to context parent
    public CharSequence toInputString()
    {
        return new Html5.INPUT().type("hidden").name(name).value(value).render(0);
    }


    /**
     * <p>
     *     Build an &lt;input&gt; element using the default CSRF token.
     * </p>
     * <p>
     *     This method is shorthand for <code>new CsrfToken()._toInput()</code>.
     * </p>
     * @see #_toInput()
     */
    public static Html5.INPUT _input()
    {
        return new CsrfToken()._toInput();
    }







    // tokenName must have been validated
    static String tokenValue(CookieJar cookieJar, String tokenName)
    {
        String tokenValue = cookieJar.get(tokenName);
        if(tokenValue==null || tokenValue.isEmpty())
            cookieJar.put(tokenName, tokenValue=createNewTokenValue(), Cookie.SESSION);
        return tokenValue;
    }

    static final SecureRandom random = new SecureRandom();
    static final int csrfTokenValueLength = 12;
    static String createNewTokenValue()
    {
        final String candidateChars = _CharDef.a_zA_Z0_9;
        char[] chars = new char[csrfTokenValueLength];
        for(int i=0; i<csrfTokenValueLength; i++)
            chars[i] = candidateChars.charAt( random.nextInt(candidateChars.length()) );
        return new String(chars);
    }






}
