package bayou.form;

/**
 * To indicate that an HTTP POST request <i>might</i> be <a href="FormParser.html#csrf">CSRF</a>.
 * <p>
 *     This exception is used when a FormParser cannot definitely prove that
 *     a request is non-CSRF.
 * </p>
 * <h4 id=response>Generate a response for a CsrfException</h4>
 * <p>
 *     Always assume that the client is innocent
 *     when generating an error response for a CsrfException
 *     (at the risk of being too nice to a real attacker).
 * </p>
 * <p>
 *     Possible reasons for the CsrfException (assuming the client is innocent):
 * </p>
 * <ul>
 *     <li>
 *         If the application does *not* use {@link CsrfToken} in the form,  <br>
 *         it's likely that the client hides/obfuscates Referer headers for privacy reasons.
 *     </li>
 *     <li>
 *         If the application *does* use {@link CsrfToken} in the form, <br>
 *         it's likely that the client disables cookies *and* hides/obfuscates Referer headers.
 *     </li>
 * </ul>
 */
public class CsrfException extends Exception
{
    public CsrfException(String msg)
    {
        super(msg);
    }
}
