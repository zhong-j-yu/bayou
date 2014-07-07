package bayou.http;

import _bayou._tmp._CharDef;

import java.util.HashMap;

/**
 * HTTP response status.
 * <p>
 *     An HttpStatus contains a Status-Code (e.g. 200) and a Reason-Phrase (e.g. "OK").
 *     See <a href="http://tools.ietf.org/html/rfc2616#section-6.1.1">RFC2616
 *     &sect;6.1.1 Status Code and Reason Phrase</a>.
 * </p>
 * <p>
 *     Well-known status are available as constants like {@link #c200_OK HttpStatus.c200_OK},
 *     or can be retrieved by Status-Code like {@link #of(int) HttpStatus.of(200)}.
 * </p>
 * <p>
 *     This class is immutable.
 * </p>
 */

// note: use "status" for plural of "status".

public class HttpStatus
{
    final int code;
    final String phrase;
    final String string; // "200 OK". server accesses this field frequently.

    /**
     * Create an HttpStatus instance.
     * @param code the Status-Code. required: <code>100&lt;=code&lt;=999</code>
     * @param phrase the Reason-Phrase
     */
    public HttpStatus(int code, String phrase)
    {
        if(code<100 || code>999)  // must be 3 digits
            throw new IllegalArgumentException("illegal status code");

        if(!_CharDef.check(phrase, _CharDef.Http.reasonPhraseChars))
            throw new IllegalArgumentException("illegal char in reason-phrase");

        this.code = code;
        this.phrase = phrase;
        this.string = ""+code+" "+phrase.trim();
    }

    /**
     * The Status-Code, for example, 200.
     */
    public int code() { return code; }

    /**
     * The Reason-Phrase, for example, "OK".
     */
    public String phrase(){ return phrase; }

    /**
     * Return the {@link #code() Status-Code} as the hash-code.
     */
    public int hashCode() { return code; }

    /**
     * Return true iff `that` object is an HttpStatus with the same Status-Code.
     */
    public boolean equals(Object that)
    {
        return (that instanceof HttpStatus) &&
            ((HttpStatus)that).code == this.code;
    }

    /**
     * Return a string of <code>"Status-Code SP Reason-Phrase"</code>, for example, "200 OK".
     */
    public String toString() { return string; }

    static private HashMap<Integer,HttpStatus> code2obj = new HashMap<>();

    /**
     * Get an HttpStatus instance with the Status-Code.
     * <p>
     *     If the Status-Code is well-known, a cached instance is returned;
     *     otherwise a new HttpStatus instance is returned.
     * </p>
     * @param code the Status-Code. required: <code>100&lt;=code&lt;=999</code>
     */
    static public HttpStatus of(int code)
    {
        HttpStatus obj = code2obj.get(code);
        if(obj==null) obj = new HttpStatus(code, ""+code); //unknown code
        return obj;
    }
    static private HttpStatus dfResponseStatus(int code, String phrase)
    {
        HttpStatus obj = new HttpStatus(code,phrase);
        code2obj.put(code, obj);
        return obj;
    }

    // no need to javadoc these constant fields; they are self-explanatory.

    static public final HttpStatus c100_Continue
                  = dfResponseStatus(100, "Continue");

    static public final HttpStatus c101_Switching_Protocols
                  = dfResponseStatus(101, "Switching Protocols");

    static public final HttpStatus c200_OK
                  = dfResponseStatus(200, "OK");

    static public final HttpStatus c201_Created
                  = dfResponseStatus(201, "Created");

    static public final HttpStatus c202_Accepted
                  = dfResponseStatus(202, "Accepted");

    static public final HttpStatus c203_Non_Authoritative_Information
                  = dfResponseStatus(203, "Non-Authoritative Information");

    static public final HttpStatus c204_No_Content
                  = dfResponseStatus(204, "No Content");

    static public final HttpStatus c205_Reset_Content
                  = dfResponseStatus(205, "Reset Content");

    static public final HttpStatus c206_Partial_Content
                  = dfResponseStatus(206, "Partial Content");

    static public final HttpStatus c300_Multiple_Choices
                  = dfResponseStatus(300, "Multiple Choices");

    static public final HttpStatus c301_Moved_Permanently
                  = dfResponseStatus(301, "Moved Permanently");

    static public final HttpStatus c302_Found
                  = dfResponseStatus(302, "Found");

    static public final HttpStatus c303_See_Other
                  = dfResponseStatus(303, "See Other");

    static public final HttpStatus c304_Not_Modified
                  = dfResponseStatus(304, "Not Modified");

    static public final HttpStatus c305_Use_Proxy
                  = dfResponseStatus(305, "Use Proxy");

    static public final HttpStatus c307_Temporary_Redirect
                  = dfResponseStatus(307, "Temporary Redirect");

    static public final HttpStatus c400_Bad_Request
                  = dfResponseStatus(400, "Bad Request");

    static public final HttpStatus c401_Unauthorized
                  = dfResponseStatus(401, "Unauthorized");

    static public final HttpStatus c402_Payment_Required
                  = dfResponseStatus(402, "Payment Required");

    static public final HttpStatus c403_Forbidden
                  = dfResponseStatus(403, "Forbidden");

    static public final HttpStatus c404_Not_Found
                  = dfResponseStatus(404, "Not Found");

    static public final HttpStatus c405_Method_Not_Allowed
                  = dfResponseStatus(405, "Method Not Allowed");

    static public final HttpStatus c406_Not_Acceptable
                  = dfResponseStatus(406, "Not Acceptable");

    static public final HttpStatus c407_Proxy_Authentication_Required
                  = dfResponseStatus(407, "Proxy Authentication Required");

    static public final HttpStatus c408_Request_Timeout
                  = dfResponseStatus(408, "Request Timeout");

    static public final HttpStatus c409_Conflict
                  = dfResponseStatus(409, "Conflict");

    static public final HttpStatus c410_Gone
                  = dfResponseStatus(410, "Gone");

    static public final HttpStatus c411_Length_Required
                  = dfResponseStatus(411, "Length Required");

    static public final HttpStatus c412_Precondition_Failed
                  = dfResponseStatus(412, "Precondition Failed");

    static public final HttpStatus c413_Request_Entity_Too_Large   // termed "request representation" in new draft
                  = dfResponseStatus(413, "Request Entity Too Large");

    static public final HttpStatus c414_URI_Too_Long
                  = dfResponseStatus(414, "URI_Too_Long");

    static public final HttpStatus c415_Unsupported_Media_Type
                  = dfResponseStatus(415, "Unsupported Media Type");

    static public final HttpStatus c416_Requested_Range_Not_Satisfiable
                  = dfResponseStatus(416, "Requested Range Not Satisfiable");

    static public final HttpStatus c417_Expectation_Failed
                  = dfResponseStatus(417, "Expectation Failed");

    static public final HttpStatus c500_Internal_Server_Error
                  = dfResponseStatus(500, "Internal Server Error");

    static public final HttpStatus c501_Not_Implemented
                  = dfResponseStatus(501, "Not Implemented");

    static public final HttpStatus c502_Bad_Gateway
                  = dfResponseStatus(502, "Bad Gateway");

    static public final HttpStatus c503_Service_Unavailable
                  = dfResponseStatus(503, "Service Unavailable");

    static public final HttpStatus c504_Gateway_Timeout
                  = dfResponseStatus(504, "Gateway Timeout");

    static public final HttpStatus c505_HTTP_Version_Not_Supported
                  = dfResponseStatus(505, "HTTP Version Not Supported");


}
