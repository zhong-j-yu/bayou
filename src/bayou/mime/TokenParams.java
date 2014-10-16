package bayou.mime;

import _bayou._tmp._StrUtil;

import java.util.*;

/**
 * Represents a token with some parameters.
 * <p>
 *     This class is useful for header values in the form of
 *     <code>"token; n1=v1; n2=v2 ..."</code>, for example
 * </p>
 * <pre>
 *     Content-Type: text/plain; charset=UTF-8
 *
 *     Accept-Language: en-us, en;q=0.5    ( multiple TokenParams separated by comma )
 * </pre>
 * <p>
 *     See {@link #parse(String)} and {@link #parseCommaSeparated(String)}.
 * </p>
 * <p>
 *     The parameter names are all converted to lower-case;
 *     the token and the parameter values have their case preserved.
 * </p>
 * <p>
 *     Currently we don't support parameter without value, like p1 in
 *     <code>"token; p1; p2=v2"</code>.
 * </p>
 */
public class TokenParams
{
    final String token;   // case preserved

    // all keys are in lower case.
    // map itself is case-sensitive when lookup. caller should pass only lower-case keys.
    // map may not be read-only - rely on user discipline
    final Map<String,String> parameters;

    /**
     * Create a TokenParams instance.
     * <p>
     *     The `params` Map should be read-only with lower-case keys.
     * </p>
     */
    public TokenParams(String token, Map<String, String> params)
    {
        // no validation of token, param name/value?
        this.token = token;
        this.parameters = params;
    }

    /**
     * The token.
     */
    public String token()
    {
        return token;
    }

    /**
     * The parameters as a Map.
     * <p>
     *     All keys in this Map are in lower-case.
     * </p>
     * <p>
     *     Caller should treat the returned Map as read-only.
     * </p>
     */
    public Map<String,String> params()
    {
        return parameters; // may not be read-only; rely on user discipline.
    }

    /**
     * Get the value of the parameter with the name.
     * <p>
     *     The name should be in lower-case.
     * </p>
     */
    public String param(String name)
    {
        return parameters.get(name);
    }

    /**
     * Return a String in the form of <code>"token; n1=v1; n2=v2 ..."</code>.
     * <p>
     *     Example strings: <code>"text/plain", "text/plain; charset=UTF-8"</code>.
     * </p>
     */
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(token);
        for(Map.Entry<String,String> param : parameters.entrySet())
        {
            String name = param.getKey();
            String value = param.getValue();
            sb.append("; ").append(name).append('=').append(_StrUtil.mayQuote(value));
        }
        return sb.toString();
    }


    /**
     * Parse a header value in the form of <code>"token; n1=v1; n2=v2 ..."</code>.
     * <p>
     *     This parser is very loose; caller should validate token and parameters afterwards.
     * </p>
     * <p>
     *     Example headers:
     * </p>
     * <pre>
     *     Content-Type: text/plain; charset=UTF-8
     *
     *     Content-Disposition: form-data; name="abc"
     * </pre>
     */
    static public TokenParams parse(String headerValue)
    {
        final String string = headerValue; // just alias

        final int N = string.length();
        int iS=0;
        // token, ended by ; or EOF
        int iE= findCommaSemicolonEof(string, iS, N, false);
        String token = string.substring(iS, iE).trim(); // could be empty. not lower cased
        Map<String,String> params = Collections.emptyMap();
        if(iE<N && string.charAt(iE)==';')
        {
            params = new LinkedHashMap<>();
            parseParams(string, iE, N, params, false);
        }
        return new TokenParams(token, params); // token may be empty. whatever.
    }


    /**
     * Parse a header value in the form of <code>"token-params *( , token-params)"</code>
     * where <code>token-params</code> is in the form of <code>"token *( ; name=value )"</code>.
     * <p>
     *     This parser is very loose; caller should validate tokens and parameters afterwards.
     * </p>
     * <p>
     *     Example headers:
     * </p>
     * <pre>
     *     Accept-Encoding: gzip, deflate
     *     Accept-Language: en-us, en;q=0.5
     * </pre>
     * <p>
     *     All known headers in RFC 2616 that this method may apply:
     * </p>
     * <pre>
     *      Accept Accept-Charset Accept-Encoding Accept-Language Accept-Ranges
     *      Allow Connection
     *      Content-Encoding Content-Language
     *      TE Trailer Transfer-Encoding
     *      Upgrade Vary
     * </pre>
     */
    // parse comma separated header value.
    // this is useful for quite some headers, whose values are comma separated elements,
    // and each element is in the form of `token *( ; name=value )`. examples
    // this parser is very loose, we only require that
    //    `token` does not contain , or ;
    //    `name` does not contain =
    //    `value` is either quoted string (with \ escaping), or unquoted string without , or ;
    // all names are considered case insensitive, and converted by parser to lower case as key in parameter map.
    // if same name appears multiple times in an element, the last one overrides prev ones.
    // tokens cases are preserved as is. if it is semantically case-insensitive, user should handle that himself.
    // a value successfully parsed by this method may still be semantically/syntactically invalid for the header.
    // this method tolerates invalid inputs. in the worst case, an empty list is returned
    static public List<TokenParams> parseCommaSeparated(String headerValue)
    {
        final String string = headerValue; // just alias

        ArrayList<TokenParams> list = new ArrayList<>();

        final int N = string.length();
        int iS=0;
        while(iS<N)
        {
            // token, ended by , or ; or EOF
            int iE= findCommaSemicolonEof(string, iS, N, true);
            String token = string.substring(iS, iE).trim(); // could be empty. not lower cased
            Map<String,String> params = Collections.emptyMap();
            if(iE<N && string.charAt(iE)==';')
            {
                params = new LinkedHashMap<>();
                iE = parseParams(string, iE, N, params, true);
            }
            if(!token.isEmpty())   // possible: "   ; n=v "
                list.add(new TokenParams(token, params));
            // possible to have empty token with params, e.g. ",;n=v," or "a, ,b". ignore the element.

            // iE==N if EOF. iE<N if comma.
            iS = iE+1;
        }
        return list;
    }

    // string[i] is semicolon.
    // return pos of ending comma of the element, or N for EOF.
    static int parseParams(String string, int i, int N, Map<String,String> map, boolean comma)
    {
        // 1*(;n=v) (,|EOF)
        while(true)
        {
            i+=1; // the first char is ";". skip it.
            int iEQ = string.indexOf('=', i);
            if(iEQ==-1)   // malformed. don't care about the rest
                return N;
            String name = string.substring(i, iEQ).trim();  // maybe empty
            name = _StrUtil.lowerCase(name);  // lower cased!
            i = _StrUtil.skipWhiteSpaces(string, iEQ + 1);  // possible i==N
            String value;
            int j;
            if(i<N && string.charAt(i)=='"') // quoted string. ended by closing quote
            {
                String[] result = {null};
                j = parseQuotedString(string, i, N, result);  // quoted string not trimmed
                if(j==N) // no closing quote. no result
                    return N;
                value = result[0];
                j=findCommaSemicolonEof(string, j, N, comma); // assume all white spaces in between
            }
            else // not quoted string. ended by , or ; or EOF
            {
                j=findCommaSemicolonEof(string, i, N, comma);
                value = string.substring(i,j).trim();
            }

            if(!name.isEmpty())        // ignore empty name, e.g. ";=v"
                map.put(name, value);  // allow empty value (mainly to honor quoted empty value "" )

            if(j<N && string.charAt(j)==';')
                i=j;  // continue next param
            else  // comma or EOF - no more params for this element
                return j;
        }
    }

    // return position of , or ; or EOF.  N for EOF.
    static int findCommaSemicolonEof(String string, int i, int N, boolean comma)  // i<=N
    {
        for(; i<N; i++)
        {
            char ch = string.charAt(i);
            if( ch==';' || (ch==',' && comma ) )
                break;
        }
        return i;  // i<=N
    }

    // string[start] is quote.
    // return pos of closing quote, or N if not closed
    static int parseQuotedString(String string, int start, int N, String[] result)
    {
        start++; // the first char is quote. skip it
        boolean esc=false;
        StringBuilder sb = null;  // only needed if there's escaping
        int i=start;
        for( ; i<N; i++)
        {
            char ch = string.charAt(i);
            if(esc)
                esc=false;
            else if(ch=='"')
                break;
            else if(ch=='\\')
            {
                esc=true;
                if(sb==null)
                    sb=new StringBuilder().append(string, start, i);
                continue;
            }

            if(sb!=null)
                sb.append(ch);
        }
        if(i==N) // no closing quote. no result
            return N;

        result[0] = (sb==null)? string.substring(start, i) : sb.toString(); // quoted string not trimmed.
        return i;
    }


}
