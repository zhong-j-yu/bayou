package bayou.util;

import bayou.bytes.ByteSource;

/**
 * To indicate that a certain limit is exceeded.
 *
 * <p>
 *     For example, {@link ByteSource#readAll(int) ByteSource.allBytes(maxBytes)} reads all bytes
 *     from the source into a <code>ByteBuffer</code>, up to a limit.
 *     If we invoke <code>source.allBytes(1024)</code>, and the total number of bytes in the source exceeds 1024,
 *     the action fails with a <code>new OverLimitException("maxBytes", 1024)</code>.
 * </p>
 *
 * <p>
 *     Each <code>OverLimitException</code> contains the name of the limit (for example <code>"maxBytes"</code>)
 *     and the value of the limit (for example <code>1024</code>).
 *     The UI layer can use them to generate a more user-friendly error message, for example:
 *     <i>"The maximum number of bytes must not exceed 1024."</i>
 * </p>
 *
 * <p>
 *     Note that <code>OverLimitException</code> does not contain the information of
 *     how much is exceeded beyond the limit.
 *     Usually the action is aborted as soon as the limit is exceeded,
 *     therefore it is unknown what the total amount would have been.
 * </p>
 *
 */
public class OverLimitException extends Exception
{
    final String limitName;
    final long limitValue;

    /**
     * Example: <code>new OverLimitException("maxBytes", 1024)</code>
     */
    public OverLimitException(String limitName, long limitValue)
    {
        this(limitName, limitValue, limitName+"="+limitValue);
    }

    /**
     * Example: <code>new OverLimitException("maxBytes", 1024, "number of bytes exceeds 1024")</code>
     */
    public OverLimitException(String limitName, long limitValue, String message)
    {
        super(message);

        this.limitName = limitName;
        this.limitValue = limitValue;
    }

    /**
     * The name of the limit, for example, <code>"maxBytes"</code>.
     */
    public String getLimitName()
    {
        return limitName;
    }

    /**
     * The value of the limit, for example, <code>1024</code>.
     */
    public long getLimitValue()
    {
        return limitValue;
    }
}
