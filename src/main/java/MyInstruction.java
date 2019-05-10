import BIT.highBIT.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class MyInstruction {

    private static PrintStream out = null;

    private static HashMap store= new HashMap<>();
    private static int counter =0;

    public static void main(String argv[]) {
        File file_in = new File(argv[0]);

                ClassInfo ci = new ClassInfo(argv[0] );

                // loop through all the routines
                // see java.util.Enumeration for more information on Enumeration class
                for (Enumeration e = ci.getRoutines().elements(); e.hasMoreElements(); ) {
                    Routine routine = (Routine) e.nextElement();
                        for (Enumeration b = routine.getInstructionArray().elements(); b.hasMoreElements(); ) {
                            Instruction bb = (Instruction) b.nextElement();
                            int op = bb.getInstructionType();
                            if(op == InstructionTable.LOAD_INSTRUCTION ){
                                System.out.println(ci.getSourceFileName());
                                bb.addBefore("MyInstruction", "count", new Integer(1));
                            }

                    }
                        if(routine.isPublic() && routine.getMethodName().equals("solve")){
                            routine.addAfter("MyInstruction","printICount",ci.getClassName());
                        }
                }
                ci.write(argv[0] );
            }




    public static synchronized void printICount(String foo) {
        try {
            PrintWriter file = new PrintWriter(new BufferedWriter(new FileWriter("myfile.txt", true)));
            file.println(store.get(new Long(Thread.currentThread().getId())) +" - "+ Thread.currentThread().getId());
            file.close();
        }catch (Exception x){}
        System.out.println("Logged");
    }


    public static synchronized void count(int i) {
        Long id = new Long(Thread.currentThread().getId());
        if(store.get(id)==null){
            store.put(id,new Integer(1));
            return;
        }
        store.put(id,new Integer((Integer) store.get(id)+1));
    }

}
