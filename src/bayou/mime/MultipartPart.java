package bayou.mime;

import bayou.bytes.ByteSource;

import java.util.Map;

/**
 * A part in a multipart body.
 * <p>
 *     A part contains some headers, and a sequence of bytes as the part body.
 *     See <a href="https://www.ietf.org/rfc/rfc2046.txt">RFC2046</a>.
 * </p>
 */

//   see html4/5 -> rfc2388 -> rfc2046
//       http rfc2616 -> rfc2046

public interface MultipartPart
{
    /**
     * The headers of this part.
     * <p>
     *     The returned Map is read-only; keys are case-insensitive for lookup.
     * </p>
     */
    Map<String,String> headers();
    // name/value chars must be octets, i.e. 0x00-0xFF.
    // if needed, app can encode a string to bytes, then disguise it as Latin-1 string.
    // name chars are print chars except COLON. value chars are any octets except CR LF.
    // see _CharDef.Rfc822

    /**
     * The body of this part.
     * <p>
     *     This method may be invoked only once in some implementations.
     * </p>
     */
    ByteSource body();
    // each body() call should return a new independent ByteSource at pos=0.
    // if not possible, only 1st call will succeed; other calls throw IllegalStateException
}
