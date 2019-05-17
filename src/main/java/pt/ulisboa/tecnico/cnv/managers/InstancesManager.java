package pt.ulisboa.tecnico.cnv.managers;

import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsResult;
import com.amazonaws.services.ec2.model.Instance;
import pt.ulisboa.tecnico.cnv.data.InstanceData;
import pt.ulisboa.tecnico.cnv.server.LoadBalancer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InstancesManager {
    private Map<String, InstanceData> idsToInstances = new ConcurrentHashMap<>();

    private static InstancesManager instancesManager = null;
    private static int checkCPUCounter = 0;
    private static final int checkCPUUsage = 6;
    private static int current = 0;

    private final static double MIN_CPU = 35;
    private final static double MAX_CPU = 80;

    private int numberCreating = 0;

    private InstancesManager() {
    }

    public int count(){
        return idsToInstances.size();
    }

    public static InstancesManager getInstanceManager() {
        if (instancesManager == null) {
            instancesManager = new InstancesManager();
        }
        return instancesManager;
    }

    public void addInstance(Instance instance) {
        System.out.println("Adding " + instance.getInstanceId());
        InstanceData instanceData = new InstanceData(instance);
        idsToInstances.put(instanceData.publicIP, instanceData);
    }

    public void cycle(){

        checkCPUCounter++;

        if (checkCPUCounter == checkCPUUsage) {
            System.out.println("Checking CPU of servers...");
            Map<InstanceData, Double> cpuUtilization = getWebServersCpuUtilization();

            Double averageCpu = calculateAverageCpu(cpuUtilization);
            System.out.println("Average CPU is " + averageCpu + "%.");

            if (averageCpu <= MIN_CPU) {
                System.out.println("CPU low, asking to remove an instance");
                removeInstance();
            }
            if (averageCpu >= MAX_CPU) {
                System.out.println("CPU high, asking to add an instance");
                addInstance();
            }

            checkCPUCounter = 0;
        }
    }

    private Double calculateAverageCpu(Map<InstanceData, Double> cpuUtilization) {
        if (cpuUtilization.size() == 0) {
            return 0.0;
        }

        double value = 0;
        for (Double val : cpuUtilization.values()) {
            value += val;
        }
        return value / cpuUtilization.size();
    }


    private Map<InstanceData, Double> getWebServersCpuUtilization() {

        Map<InstanceData, Double> cpuUtilization = new HashMap<>();

        List<InstanceData> list = new ArrayList(idsToInstances.values());
        for (InstanceData instance : list ) {

            String name = instance.id;

            GetMetricStatisticsResult getMetricStatisticsResult = LoadBalancer.requestCpuUsage(name);
            List<Datapoint> datapoints = getMetricStatisticsResult.getDatapoints();

            // Get most recent datapoint
            Datapoint mostRecentDatapoint = null;
            for (Datapoint data : datapoints) {
                if (mostRecentDatapoint == null) {
                    mostRecentDatapoint = data;
                }

                if (data.getTimestamp().after(mostRecentDatapoint.getTimestamp())) {
                    mostRecentDatapoint = data;
                }
            }

            if (mostRecentDatapoint != null) {
                System.out.println(name + " cpu: " + mostRecentDatapoint.getAverage());
                cpuUtilization.put(instance, mostRecentDatapoint.getAverage());
            } else {
                System.out.println(name + " cpu: " + 0);
                cpuUtilization.put(instance, 0.0);
            }
        }

        return cpuUtilization;
    }


    public  InstanceData findBestInstance(int load) {
        while(getNumber()>0){
            try {
                System.out.println("Wainting for Creation");
                Thread.sleep(2000L);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        InstanceData result = findLeastUsed(load);
        System.out.println(result.publicIP +" workload is -> " +result.getWorkload());
        return result;
    }

    private void removeInstance(){
        TimerTask singleTask = new TimerTask() {
            @Override
            public void run() {

                String id =  InstancesManager.getInstanceManager().findBestInstance(0).id;
               LoadBalancer.requestRemoveInstance(id);
            }
        };
        Timer timer = new Timer("RemoveInstance");
        timer.schedule(singleTask,0);
    }

    public void removeLoad(InstanceData ip, int load){
        idsToInstances.get(ip).removeWorkload(load);
    }

    private void addInstance(){
        TimerTask singleTask = new TimerTask() {
            @Override
            public void run() {
                LoadBalancer.requestNewInstance();
            }
        };
        Timer timer = new Timer("AddInstance");
        timer.schedule(singleTask,0);
    }


    public  synchronized InstanceData findLeastUsed( int load){
        List<InstanceData> list = new ArrayList<>( idsToInstances.values());

        InstanceData result = null;
        int bestWork = Integer.MAX_VALUE;
        for(InstanceData data : list){
            if(data.getWorkload()<bestWork){
                result = data;
            }
        }
        result.addWorkload(load);
        return result;
    }

    public synchronized void addCreation(){
        numberCreating++;
    }

    public synchronized void removeCreation(int size){
        this.numberCreating = this.numberCreating - size;
        if(this.numberCreating<0)
            this.numberCreating =0;
    }

    public int getNumber(){
        return numberCreating;
    }

}
