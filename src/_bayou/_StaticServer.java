package _bayou;

import bayou.file.StaticHandler;
import bayou.http.HttpServer;

// a basic static server. for demo and testing.
public class _StaticServer
{
    public static void main(String[] args) throws Exception
    {
        if(args.length!=1)
        {
            System.out.printf("%nUsage: java %s <rootDir>%n", _StaticServer.class.getName());
            return;
        }

        String rootDir = args[0];
        StaticHandler handler = new StaticHandler("/", rootDir); // throws
        System.out.printf("%nBayou Demo Static Server, rootDir = %s %n", rootDir);

        HttpServer server = new HttpServer(handler);
        server.start(); // localhost:8080
    }
}
