package dev.agentcraft.hq;

import java.util.Arrays;
import java.util.Random;

/** Standalone lossless round-trip test: run with java -ea after compiling with SurveyCuboids. */
public class SurveyCuboidsTest {
    public static void main(String[] args) {
        verify(new int[109*137*155],109,137,155);
        int[] full=new int[109*137*155];Arrays.fill(full,7);
        assert SurveyCuboids.pack(full,109,137,155).size()==1;
        verify(full,109,137,155);
        Random random=new Random(42);
        for(int i=0;i<100;i++){
            int[] cells=new int[13*9*17];
            for(int j=0;j<cells.length;j++)cells[j]=random.nextInt(8);
            verify(cells,13,9,17);
        }
        // Far corner and highest roof layer must survive; no player-centered sampling.
        int[] distant=new int[109*137*155];distant[0]=1;distant[distant.length-1]=2;
        verify(distant,109,137,155);
        System.out.println("Survey cuboids: exact coverage, air, boundaries, palette identities and no overlap passed");
    }
    private static void verify(int[] cells,int nx,int ny,int nz){
        int[] original=cells.clone(),decoded=new int[cells.length];
        for(int[] b:SurveyCuboids.pack(cells,nx,ny,nz)){
            for(int y=b[1];y<=b[4];y++)for(int z=b[2];z<=b[5];z++)for(int x=b[0];x<=b[3];x++){
                int index=(y*nz+z)*nx+x;
                assert decoded[index]==0:"overlap";
                decoded[index]=b[6];
            }
        }
        assert Arrays.equals(original,cells):"source mutated";
        assert Arrays.equals(original,decoded):"lossy survey";
    }
}
