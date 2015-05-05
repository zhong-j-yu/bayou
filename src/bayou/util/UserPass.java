package bayou.util;

/**
 * Username and password.
 */
public class UserPass
{
    final String username;
    final String password;

    /**
     * Create an instance.
     */
    public UserPass(String username, String password)
    {
        this.username = username;
        this.password = password;
    }

    /**
     * The username.
     */
    public String username()
    {
        return username;
    }

    /**
     * The password.
     */
    public String password()
    {
        return password;
    }
}
