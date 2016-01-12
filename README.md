# bayou.io

Async http server & client for Java

For more information, see <http://bayou.io>

## HttpServer

        HttpHandler handler = request -> HttpResponse.text(200, "Hello World");

        HttpServer server = new HttpServer(handler);
        server.conf().trafficDump(System.out::print);
        server.start();


## HttpClient

        HttpClient client = new HttpClient();
        
        Async<HttpResponse> asyncRes = client.doGet( "https://example.com" );
