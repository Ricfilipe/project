package pt.ulisboa.tecnico.cnv.data;

import pt.ulisboa.tecnico.cnv.solver.Solver;
import pt.ulisboa.tecnico.cnv.solver.SolverArgumentParser;

public class SolverData {
    private int height,width;
    private int startX,startY;
    private String strategy, inputImage;

    public SolverData(Solver s, SolverArgumentParser ap) {
        this.height = s.getHeight();
        this.width = s.getWidth();
        this.startX = s.getStartX();
        this.startY = s.getStartY();
        this.strategy = ap.getSolverStrategy().toString();
        this.inputImage = ap.getInputImage();
    }

    public String toString(){
        return  startX + ":" + startY + " " + width+":"+height + " "+ strategy + " "+ inputImage;

    }
}
