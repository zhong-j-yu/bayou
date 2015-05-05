package _bayou;

import bayou.http.*;

import java.time.Duration;

// simple demo of HTTP+HTTPS proxy at port 9090
public class _HttpProxy
{
    public static void main(String[] args) throws Exception
    {
        int port = 9090;

        HttpClient downstream = new HttpClient();
        HttpHandler handler = request->
        {
            if(request.method().equals("CONNECT"))
            {
                System.err.println("## TUNNEL ## "+request.host());
                return HttpResponse.text(200, "tunneling request granted");
            }
            else
            {
                System.err.println("## FORWARD ## "+request.absoluteUri());
                return downstream.send0(request, null);
            }
        };

        HttpServer proxy = new HttpServer(handler);
        proxy.conf()
            .port(port)
            .setProxyDefaults()
            .trafficDump(System.out::print)
        ;
        proxy.start();

        System.out.printf("Bayou Demo HTTP+HTTPS Proxy, port = %s %n", port);
    }


}
