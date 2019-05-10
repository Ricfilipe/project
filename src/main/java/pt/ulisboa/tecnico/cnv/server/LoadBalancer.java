package pt.ulisboa.tecnico.cnv.server;

import com.sun.deploy.net.BasicHttpRequest;
import com.sun.deploy.net.HttpResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.Executors;

public class LoadBalancer {


    public static void main(final String[] args) throws Exception {


        final HttpServer server = HttpServer.create(new InetSocketAddress(80), 0);
        server.createContext("/climb", new LoadBalancer.LoadBalancerHandler());

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    static class LoadBalancerHandler implements HttpHandler {
        public void handle(final HttpExchange t) throws IOException {
            BasicHttpRequest request = new BasicHttpRequest();
            String ip = "3.93.248.234";

                URL url = new URL("http://" + ip + "?" + t.getRequestURI().getQuery());
                HttpResponse response =request.doGetRequest(url);

            final OutputStream os = t.getResponseBody();

            os.write(response.getInputStream().read());


            os.close();
            System.out.println("> Sent response to " + t.getRemoteAddress().toString());



        }

    }
}

