package bayou.http;

// received a bad response.
// a response object may be included here for reference, but it may null, or its methods may malfunction
public class HttpResponseException extends Exception
{
    final HttpResponse response; // could be null or corrupt

    public HttpResponseException(String message, HttpResponse response)
    {
        super(message);

        this.response = response;
    }

    public HttpResponse response()
    {
        return response;
    }

}
