package pt.ulisboa.tecnico.cnv.data;

import com.amazonaws.services.ec2.model.Instance;
import pt.ulisboa.tecnico.cnv.solver.Solver;
import pt.ulisboa.tecnico.cnv.solver.SolverArgumentParser;

import java.util.ArrayList;
import java.util.List;

public class InstanceData {

    public String publicIP;
    public List<String> requests = new ArrayList<>();
    public String id;
    public boolean alive ;
    public int tries;
    private int workload;

    public InstanceData(Instance instance) {
        this.publicIP = instance.getPublicIpAddress();
        this.id = instance.getInstanceId();
        this.alive = false;
        this.tries = 0;
        this.workload = 0;
    }

    public int  getWorkload(){
        return workload;
    }

    public void addWorkload(int load){
        this.workload += load;
    }

    public void removeWorkload(int load) {
        this.workload -= load;
    }
}
