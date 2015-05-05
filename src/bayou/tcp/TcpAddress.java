package bayou.tcp;

/**
 * Address of a TCP service.
 * <p>
 *     The address includes {@link #host() host} and {@link #port() port}.
 *     Additionally, {@link #ssl() ssl}=true if SSL is required by the service.
 * </p>
 */
public class TcpAddress
{
    final boolean ssl;
    final String host; // domain or ip literal. lower case
    final int port;

    /**
     * Create an instance.
     */
    public TcpAddress(boolean ssl, String host, int port)
    {
        // todo: validate host and port?
        this.ssl = ssl;
        this.host = host.toLowerCase();
        this.port = port;
    }
    /**
     * Create an instance.
     * <p>
     *     This constructor is equivalent to
     *     {@link #TcpAddress(boolean, String, int) TcpAddress(false, host, port)}.
     * </p>
     */
    public TcpAddress(String host, int port)
    {
        this(false, host, port);
    }

    /**
     * Whether SSL is required.
     */
    public boolean ssl()
    {
        return ssl;
    }

    /**
     * The TCP host, either a domain name or an IP literal, in lower case.
     */
    public String host()
    {
        return host;
    }

    /**
     * The TCP port.
     */
    public int port()
    {
        return port;
    }

    @Override
    public int hashCode()
    {
        return (ssl?31*31:0) + 31*host.hashCode() + port;
    }

    /**
     * Return true iff `obj` is a TcpAddress with the same `ssl`, `host`, and `port`.
     */
    public boolean equals(Object obj)
    {
        if(!(obj instanceof TcpAddress))
            return false;

        TcpAddress that = (TcpAddress)obj;
        return this.ssl==that.ssl
            && this.port==that.port
            && this.host.equals(that.host);
    }
}
