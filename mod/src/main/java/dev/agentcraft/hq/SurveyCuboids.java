package dev.agentcraft.hq;

import java.util.ArrayList;
import java.util.List;

/** Lossless non-air cuboids for a palette-indexed snapshot in y,z,x order. */
public final class SurveyCuboids {
    private SurveyCuboids() {}
    public static List<int[]> pack(int[] source,int nx,int ny,int nz) {
        if(nx<=0||ny<=0||nz<=0||(long)nx*ny*nz!=source.length)throw new IllegalArgumentException("Invalid survey dimensions");
        int[] cells=source.clone();List<int[]> boxes=new ArrayList<>();
        for(int y=0;y<ny;y++)for(int z=0;z<nz;z++)for(int x=0;x<nx;x++){
            int id=cells[(y*nz+z)*nx+x];if(id==0)continue;
            int ex=x,ez=z,ey=y;
            while(ex+1<nx&&cells[(y*nz+z)*nx+ex+1]==id)ex++;
            rows:while(ez+1<nz){
                for(int xx=x;xx<=ex;xx++)if(cells[(y*nz+ez+1)*nx+xx]!=id)break rows;
                ez++;
            }
            layers:while(ey+1<ny){
                for(int zz=z;zz<=ez;zz++)for(int xx=x;xx<=ex;xx++)if(cells[((ey+1)*nz+zz)*nx+xx]!=id)break layers;
                ey++;
            }
            boxes.add(new int[]{x,y,z,ex,ey,ez,id});
            for(int yy=y;yy<=ey;yy++)for(int zz=z;zz<=ez;zz++)for(int xx=x;xx<=ex;xx++)cells[(yy*nz+zz)*nx+xx]=0;
        }
        return boxes;
    }
}
