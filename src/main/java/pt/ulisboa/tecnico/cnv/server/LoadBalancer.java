package pt.ulisboa.tecnico.cnv.server;


import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import pt.ulisboa.tecnico.cnv.data.InstanceData;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class LoadBalancer {

    public static final int WEBSERVER_PORT = 8000;
    public static final long period = 1000L;
    public static final int TRIES_UNTIL_REBOOT = 5;
    public static final long ALIVE_PERIOD = 1000L;

    public static Set<InstanceData> instances;
    static AmazonEC2 ec2;

    public static Set<InstanceData> availableInstances;

    private static void init() throws Exception {

        /*
         * The ProfileCredentialsProvider will return your [default]
         * credential profile by reading from the credentials file located at
         * (~/.aws/credentials).
         */
        AWSCredentials credentials = null;
        try {
            credentials = new ProfileCredentialsProvider().getCredentials();
        } catch (Exception e) {
            throw new AmazonClientException(
                    "Cannot load the credentials from the credential profiles file. " +
                            "Please make sure that your credentials file is at the correct " +
                            "location (~/.aws/credentials), and is in valid format.",
                    e);
        }
        ec2 = AmazonEC2ClientBuilder.standard().withRegion("us-east-1").withCredentials(new AWSStaticCredentialsProvider(credentials)).build();

    }

    public static void main(final String[] args) throws Exception {

        init();
        Map<InstanceData, Boolean> myMap = new ConcurrentHashMap<InstanceData, Boolean>();
        instances =  Collections.newSetFromMap(myMap);
        TimerTask repeatedTask = new TimerTask() {
            public void run() {
                try {
                    DescribeAvailabilityZonesResult availabilityZonesResult = ec2.describeAvailabilityZones();
                    /* using AWS Ireland.
                     * TODO: Pick the zone where you have your AMI, sec group and keys */
                    DescribeInstancesRequest request = new DescribeInstancesRequest();
                    List<String> values = new ArrayList<>();
                    values.add("Server");
                    if(instances.size()==0){
                        values.add("ServerC");
                    }
                    DescribeInstancesResult describeInstancesRequest = ec2.describeInstances(request.withFilters(new Filter("tag:WebServer", values)));
                    List<Reservation> reservations = describeInstancesRequest.getReservations();
                    Set<Instance> result = new HashSet<>();
                    for (Reservation reservation : reservations){
                        result.addAll(reservation.getInstances());
                    }
                    if(result.size()>0) {
                        List<String> ids = new ArrayList<>();
                        System.out.println("Found "+ result.size()+ " new server.");
                        for (Instance instnc : result) {
                            instances.add(new InstanceData(instnc));
                            ids.add(instnc.getInstanceId());
                        }

                        CreateTagsRequest request2 = new CreateTagsRequest().withTags(new Tag().withKey("WebServer").withValue("ServerC"));
                        request2.setResources(ids);
                        ec2.createTags(request2);
                    }
                }catch (AmazonServiceException ase) {
                    System.out.println("Caught Exception: " + ase.getMessage());
                    System.out.println("Reponse Status Code: " + ase.getStatusCode());
                    System.out.println("Error Code: " + ase.getErrorCode());
                    System.out.println("Request ID: " + ase.getRequestId());
                }
            }
        };

/*
        TimerTask pingTask = new TimerTask() {
            public void run() {
                if(instances.size()>0){
                    try {
                    for (InstanceData data :  instances ) {

                            URL url = new URL("http://" + data.publicIP +":"+LoadBalancer.WEBSERVER_PORT + "/ping");

                        }
                    } catch (MalformedURLException e) {
                        e.printStackTrace();
                    }
                }
            }
        };
*/
        Timer timer = new Timer("LookForWebServers");
        Timer pingTimer = new Timer("PingWebservers");
        timer.schedule(repeatedTask,0L,period);
        //pingTimer.schedule(pingTask,ALIVE_PERIOD,ALIVE_PERIOD);


        final HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        server.createContext("/climb", new LoadBalancer.LoadBalancerHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    static class LoadBalancerHandler implements HttpHandler {
        public static int current = 0;
        public void handle(final HttpExchange t) throws IOException {

            System.out.println("received request");
            String ip = getInstanceIp();
            System.out.println("http://" + ip +":"+LoadBalancer.WEBSERVER_PORT +"/climb?" + t.getRequestURI().getQuery());
            URL url = new URL("http://" + ip +":"+LoadBalancer.WEBSERVER_PORT + "/climb?" + t.getRequestURI().getQuery());

            System.out.println("Sending request to: " + ip);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.connect();

            final OutputStream os = t.getResponseBody();

            os.write(con.getInputStream().read());

            System.out.println("Receive response from: " + ip);
            os.close();
            System.out.println("> Sent response to " + t.getRemoteAddress().toString());
        }

        public synchronized String getInstanceIp(){
            List<InstanceData> list = new ArrayList<>(instances);
            String result = list.get(current).publicIP;
            current = (current+1)%list.size();
            return result;
        }
    }

}

