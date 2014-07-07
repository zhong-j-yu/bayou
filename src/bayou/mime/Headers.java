package bayou.mime;

/**
 * Well-known MIME header names.
 * <p>
 *     Every constant String field in this interface corresponds to the name of a well-known MIME header,
 *     for example, {@link #Accept_Charset Headers.Accept_Charset}<code>="Accept-Charset"</code>.
 *     Note that dash("-") in the header name is replaced with underscore("_") in the field name.
 * </p>
 */
public interface Headers
{
    // all "public static final String" fields must be header name constants.
    // class _KnownHeaders depends on that fact when compiling the list of all headers.

    // http headers from rfc 2616
    public static final String
            Accept="Accept", Accept_Charset="Accept-Charset", Accept_Encoding="Accept-Encoding",
            Accept_Language="Accept-Language", Accept_Ranges="Accept-Ranges", Age="Age", Allow="Allow",
            Authorization="Authorization", Cache_Control="Cache-Control", Connection="Connection",
            Content_Encoding="Content-Encoding", Content_Language="Content-Language",
            Content_Length="Content-Length", Content_Location="Content-Location", Content_MD="Content-MD",
            Content_Range="Content-Range", Content_Type="Content-Type", Date="Date", ETag="ETag", Expect="Expect",
            Expires="Expires", From="From", Host="Host", If_Match="If-Match", If_Modified_Since="If-Modified-Since",
            If_None_Match="If-None-Match", If_Range="If-Range", If_Unmodified_Since="If-Unmodified-Since",
            Last_Modified="Last-Modified", Location="Location", Max_Forwards="Max-Forwards", Pragma="Pragma",
            Proxy_Authenticate="Proxy-Authenticate", Proxy_Authorization="Proxy-Authorization", Range="Range",
            Referer="Referer", Retry_After="Retry-After", Server="Server", TE="TE", Trailer="Trailer",
            Transfer_Encoding="Transfer-Encoding", Upgrade="Upgrade", User_Agent="User-Agent", Vary="Vary",
            Via="Via", Warning="Warning", WWW_Authenticate="WWW-Authenticate";

    public static final String
            Set_Cookie="Set-Cookie", Cookie="Cookie", Keep_Alive="Keep-Alive",
            Content_Disposition="Content-Disposition",
            Origin="Origin";

}
