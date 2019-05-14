package pt.ulisboa.tecnico.cnv.server;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsRequest;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class AutoScaler {

    public static Set<Instance> instances;
    public static Collection<TagSpecification>  tags = new ArrayList<>();
    /*
     * Before running the code:
     *      Fill in your AWS access credentials in the provided credentials
     *      file template, and be sure to move the file to the default location
     *      (~/.aws/credentials) where the sample code will load the
     *      credentials from.
     *      https://console.aws.amazon.com/iam/home?#security_credential
     *
     * WARNING:
     *      To avoid accidental leakage of your credentials, DO NOT keep
     *      the credentials file in your source directory.
     */

    static AmazonEC2      ec2;

    /**
     * The only information needed to create a client are security credentials
     * consisting of the AWS Access Key ID and Secret Access Key. All other
     * configuration, such as the service endpoints, are performed
     * automatically. Client parameters, such as proxies, can be specified in an
     * optional ClientConfiguration object when constructing a client.
     *
     * @see com.amazonaws.auth.BasicAWSCredentials
     * @see com.amazonaws.auth.PropertiesCredentials
     * @see com.amazonaws.ClientConfiguration
     */
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
        TagSpecification tag = new TagSpecification();
        tag.withTags( new Tag("WebServer","Server")).setResourceType("instance");
        tags.add(tag);
    }


    public static  void main(String[] args) throws Exception {


        init();




        final HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);

        server.createContext("/addServer", new AutoScaler.AddHandler());

        server.createContext("/server", new AutoScaler.RemoveHandler());

        // be aware! infinite pool of threads!
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        try {
            DescribeAvailabilityZonesResult availabilityZonesResult = ec2.describeAvailabilityZones();
            System.out.println("You have access to " + availabilityZonesResult.getAvailabilityZones().size() +
                    " Availability Zones.");
            /* using AWS Ireland.
             * TODO: Pick the zone where you have your AMI, sec group and keys */
            DescribeInstancesRequest request = new DescribeInstancesRequest();
            List<String> values = new ArrayList<>();
            values.add("Server");
            values.add("ServerC");
            DescribeInstancesResult describeInstancesRequest = ec2.describeInstances(request.withFilters(new Filter("tag:WebServer",values)));
            List<Reservation> reservations = describeInstancesRequest.getReservations();
            Map<Instance, Boolean> myMap = new ConcurrentHashMap<Instance, Boolean>();
            instances =  Collections.newSetFromMap(myMap);

            for (Reservation reservation : reservations) {
                instances.addAll(reservation.getInstances());
            }
            if(instances.size()==0){
                System.out.println("No instances found, creating one");
                createInstance();
            }
        }catch (AmazonServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Reponse Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());
        }

    }


    private static class AddHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange t) throws IOException {
            System.out.println("Creating");
            AutoScaler.createInstance();
            String response = "Server added";
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

    private static class RemoveHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange t) throws IOException {
            final String query = t.getRequestURI().getQuery();
            final String[] params = query.split("id=");
            System.out.println("Removing " + params[1]);
            removeInstance(params[1]);
            String response = "Server removed";
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

    public  static void createInstance() {

        try {

            RunInstancesRequest runInstancesRequest =
                    new RunInstancesRequest();

            runInstancesRequest.withImageId("ami-05bb58f95078557a4")
                    .withInstanceType("t2.micro")
                    .withMinCount(1)
                    .withMaxCount(1)
                    .withKeyName("CNV-lab-AWS")
                    .withSecurityGroups("CNV-ssh+http")
                    .withTagSpecifications(tags);

            RunInstancesResult runInstancesResult =
                    ec2.runInstances(runInstancesRequest);


            Instance newInstanceId = runInstancesResult.getReservation().getInstances()
                    .get(0);

            AutoScaler.instances.add(newInstanceId);

        }catch (AmazonServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Reponse Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());

        }
    }
    public  static void removeInstance(String id) {
        try {
            TerminateInstancesRequest termInstanceReq = new TerminateInstancesRequest();
            termInstanceReq.withInstanceIds(id);
            ec2.terminateInstances(termInstanceReq);
        }catch (AmazonServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Reponse Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());
        }
    }

}