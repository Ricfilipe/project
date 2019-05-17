package pt.ulisboa.tecnico.cnv.server;


import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsRequest;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsResult;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBQueryExpression;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import pt.ulisboa.tecnico.cnv.data.DynamoController;
import pt.ulisboa.tecnico.cnv.data.SolverData;
import pt.ulisboa.tecnico.cnv.managers.InstancesManager;
import pt.ulisboa.tecnico.cnv.data.InstanceData;
import pt.ulisboa.tecnico.cnv.solver.Solver;

import javax.xml.crypto.Data;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class LoadBalancer {


    public static final int WEBSERVER_PORT = 8000;
    public static final long period = 1000L;
    private static final long INSTANCEM_MANAGER_PERIOD = 10000L;
    private static final int MAX_INSTANCES = 20;
    private static final int MIN_INSTANCES = 1;

    public static Set<InstanceData> instances;
    static AmazonEC2 ec2;
    private static AmazonCloudWatch cloudWatch;
    public static Map<InstanceData,Integer> tries = new ConcurrentHashMap<>();
    public static Set<InstanceData> availableInstances;
    private static Instance autoScalerInstance;
    public static DynamoDBMapper mapper;

    private static void init() throws Exception {

        /*
         * The ProfileCredentialsProvider will return your [default]
         * credential profile by reading from the credentials file located at
         * (~/.aws/credentials).
         */


        ec2 = AmazonEC2ClientBuilder.standard().withRegion("us-east-1").build();
        cloudWatch = AmazonCloudWatchClientBuilder.standard()
                .withRegion("us-east-1")
                .build();
    }

    public static void main(final String[] args) throws Exception {

        init();
        Map<InstanceData, Boolean> myMap = new ConcurrentHashMap<InstanceData, Boolean>();
        instances =  Collections.newSetFromMap(myMap);
        createInstanceManager();

         myMap = new ConcurrentHashMap<InstanceData, Boolean>();
        availableInstances =  Collections.newSetFromMap(myMap);

        createInstanceFinder();
        final HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        server.createContext("/climb", new LoadBalancer.LoadBalancerHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        DynamoController.init();
        mapper = new DynamoDBMapper(DynamoController.dynamoDB);
    }

    static class LoadBalancerHandler implements HttpHandler {
        public static int current = 0;
        public void handle(final HttpExchange t) throws IOException {
            InstancesManager manager = InstancesManager.getInstanceManager();
            System.out.println("received request");


            int load = getLoad(t.getRequestURI().getQuery());

            InstanceData ip = manager.findBestInstance(load);
            System.out.println("Sending request to " + ip.publicIP);
            URL url = new URL("http://" + ip.publicIP +":"+LoadBalancer.WEBSERVER_PORT + "/climb?" + t.getRequestURI().getQuery());
            System.out.println("Sending request to: " + ip.publicIP);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.connect();
            InputStream serverResponse = con.getInputStream();



            final Headers hdrs = t.getResponseHeaders();

            t.sendResponseHeaders(200, con.getContentLength());

            hdrs.add("Content-Type", "image/png");

            hdrs.add("Access-Control-Allow-Origin", "*");
            hdrs.add("Access-Control-Allow-Credentials", "true");
            hdrs.add("Access-Control-Allow-Methods", "POST, GET, HEAD, OPTIONS");
            hdrs.add("Access-Control-Allow-Headers", "Origin, Accept, X-Requested-With, Content-Type, Access-Control-Request-Method, Access-Control-Request-Headers");

            final OutputStream os = t.getResponseBody();

            os.write(getResponse(serverResponse,con.getContentLength()));
            manager.removeLoad(ip,load);
            System.out.println("Receive response from: " + ip.publicIP);
            os.close();
            con.disconnect();
            System.out.println("> Sent response to " + t.getRemoteAddress().toString());
        }

        private byte[] getResponse(InputStream serverResponse,int size) {
            byte[] response = new byte[size];
            try {
                int i=0;
                while(i<size){
                    i +=  serverResponse.read(response,i,size-i);
                    System.out.println("copied: " +i + " of " + size);
                }

            } catch (IOException e) {
                System.out.println("Something went wrong");
            }finally {
                try {
                    serverResponse.close();
                } catch (IOException e) {
                    //
                }
            }
            return response;
        }

    }



    private static void createInstanceFinder(){
        TimerTask repeatedTask = new TimerTask() {
            public void run() {
                InstancesManager manage = InstancesManager.getInstanceManager();
                try {
                    DescribeAvailabilityZonesResult availabilityZonesResult = ec2.describeAvailabilityZones();
                    DescribeInstancesRequest request = new DescribeInstancesRequest();
                    List<String> values = new ArrayList<>();
                    values.add("Server");
                    if(manage.count()==0){
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


                        for (Instance instnc : result) {
                            if(instnc.getState().getName().equals("running")){
                                manage.addInstance(instnc);
                                ids.add(instnc.getInstanceId());

                            }
                        }
                        System.out.println("Found "+ ids.size()+ " new server.");
                        if(ids.size()>0) {
                            CreateTagsRequest request2 = new CreateTagsRequest().withTags(new Tag().withKey("WebServer").withValue("ServerC"));
                            request2.setResources(ids);
                            ec2.createTags(request2);
                            if(manage.getNumber()>0){
                                manage.removeCreation(ids.size());
                            }
                        }
                    }

                    values = new ArrayList<>();
                    values.add("AutoScaler");

                    if(autoScalerInstance==null) {
                        System.out.println("looking for tag: WebServer:"+values.get(0));

                         request = new DescribeInstancesRequest();
                        describeInstancesRequest = ec2.describeInstances(request.withFilters(new Filter("tag:WebServer", values)));
                        reservations = describeInstancesRequest.getReservations();
                        List<Instance> result2 = new ArrayList();

                        for (Reservation reservation : reservations) {
                            result2.addAll(reservation.getInstances());
                        }

                        if (result2.size() > 0) {
                            System.out.println("Found AutoScaler");
                            autoScalerInstance =  result2.get(0);
                        }
                    }

                }catch (AmazonServiceException ase) {
                    System.out.println("Caught Exception: " + ase.getMessage());
                    System.out.println("Reponse Status Code: " + ase.getStatusCode());
                    System.out.println("Error Code: " + ase.getErrorCode());
                    System.out.println("Request ID: " + ase.getRequestId());
                }
            }
        };



        Timer timer = new Timer("LookForWebServers");
        timer.schedule(repeatedTask,0L,period);
    }


    public static GetMetricStatisticsResult requestCpuUsage(String id){

        long offsetInMilliseconds = 1000 * 60 * 10 * 5;
        Dimension instanceDimension = new Dimension();
        instanceDimension.setName("InstanceId");

        instanceDimension.setValue(id);
        GetMetricStatisticsRequest request = new GetMetricStatisticsRequest()
                .withStartTime(new Date(new Date().getTime() - offsetInMilliseconds))
                .withNamespace("AWS/EC2")
                .withPeriod(60)
                .withMetricName("CPUUtilization")
                .withStatistics("Average")
                .withDimensions(instanceDimension)
                .withEndTime(new Date());

        return cloudWatch.getMetricStatistics(request);

    }

    private static void createInstanceManager(){
        TimerTask repeatedTask = new TimerTask() {
            @Override
            public void run() {
                InstancesManager.getInstanceManager().cycle();
            }
        };
        Timer timer = new Timer("InstanceManager");
        timer.schedule(repeatedTask,0L,INSTANCEM_MANAGER_PERIOD);
    }

    public static void  requestNewInstance(){
        System.out.println("Check if have reached maximum");
        InstancesManager manager = InstancesManager.getInstanceManager();
        if(MAX_INSTANCES> manager.count()){
            URL url = null;
            manager.addCreation();
            try {
                System.out.println("Sending request to "+ autoScalerInstance.getPublicIpAddress());
                url = new URL("http://" + autoScalerInstance.getPublicIpAddress() +":"+ LoadBalancer.WEBSERVER_PORT + "/addServer");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.connect();
            InputStream input = con.getInputStream();
            input.read();
            con.disconnect();
            input.close();
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (ProtocolException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }

    public static void  requestRemoveInstance(String id){
        InstancesManager manager = InstancesManager.getInstanceManager();
        if(MIN_INSTANCES>manager.count()){
            URL url = null;
            try {
                url = new URL("http://" + autoScalerInstance.getPublicDnsName() +":"+ LoadBalancer.WEBSERVER_PORT + "/server?"+id);
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("GET");
                con.connect();
                InputStream input = con.getInputStream();
                input.read();
                con.disconnect();
                input.close();
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (ProtocolException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }

    public static int getLoad(String query) {
        SolverData data = new SolverData();
        data.setQuery(query);
        DynamoDBQueryExpression<SolverData> queryExpression = new DynamoDBQueryExpression<SolverData>().withHashKeyValues(data);
        System.out.println("Querying dynamo");
        List<SolverData> list = mapper.query(SolverData.class,queryExpression);

        if(list.size()>0) {
            System.out.println("Found One cost" + list.get(0).getCost());
           return list.get(0).getCost();
        }

        return 10000;
    }


}

