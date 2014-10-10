package _bayou;

import bayou.http.HttpHandler;
import bayou.http.HttpRequest;
import bayou.http.HttpResponse;
import bayou.http.HttpServer;

// a basic http tunnel. for demo and testing.
public class _HttpTunnel
{
    public static void main(String[] args) throws Exception
    {
        //args = new String[]{"9090"};

        if(args.length!=1)
        {
            System.out.printf("%nUsage: java %s <port>%n", _HttpTunnel.class.getName());
            return;
        }

        int port = Integer.parseInt(args[0]);

        HttpHandler handler = request->
        {
            if(request.method().equals("CONNECT")) // a tunneling request
            {
                if(isAllowed(request))
                    return HttpResponse.text(200, "tunneling request granted");
                else
                    return HttpResponse.text(403, "tunneling request denied");
            }

            return HttpResponse.text(404, "Not Found");
        };

        HttpServer server = new HttpServer(handler);
        server.conf().port(port);
        server.conf().supportedMethods("CONNECT", "GET", "HEAD");
        server.conf().trafficDump(System.out::print);
        server.start();
        System.out.printf("Bayou Demo HTTP Tunnel, port = %s %n", port);
    }

    static boolean isAllowed(HttpRequest request)
    {
        // todo: check the client, e.g. only allow clients from certain ips
        // todo: check the target server, e.g. only allow certain target servers
        return true;
    }

}
