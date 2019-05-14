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

    public InstanceData(Instance instance) {
        this.publicIP = instance.getPublicIpAddress();
        this.id = instance.getInstanceId();
        this.alive = false;
        this.tries = 0;
    }

}
