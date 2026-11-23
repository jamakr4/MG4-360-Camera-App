package com.drivehub.kamera.birdseye;

import com.drivehub.kamera.rivela.Bowl;
import com.drivehub.kamera.rivela.FisheyeCamera;

/** Calibrated flat rear half of the bowl's inner opening, prepared on the scene IO worker. */
final class RearGroundPatch {
    private static final int RINGS=16;
    private RearGroundPatch() {}
    static float[] build(RivelaScene scene,Bowl bowl) {
        int half=(bowl.nx-1)/2,columns=half+1;
        float[] grid=new float[(RINGS+1)*columns*Bowl.STRIDE];
        double[] projected=new double[3];
        for(int r=0;r<=RINGS;r++)for(int angle=0;angle<=half;angle++) {
            int index=(r*columns+angle)*Bowl.STRIDE,outer=angle*Bowl.STRIDE;
            if(r==RINGS) {
                // Exact boundary attributes prevent seams against the original bowl.
                System.arraycopy(bowl.vertices,outer,grid,index,Bowl.STRIDE);continue;
            }
            double t=r/(double)RINGS;
            double x=scene.car.centerX+t*(bowl.vertices[outer]-scene.car.centerX);
            double y=scene.car.centerY+t*(bowl.vertices[outer+1]-scene.car.centerY);
            grid[index]=(float)x;grid[index+1]=(float)y;
            double[] preference=Bowl.preference(scene.car,x,y,scene.car.front(),scene.car.rear(),scene.car.width/2);
            double sum=0;
            for(int c=0;c<4;c++) {
                FisheyeCamera camera=scene.camera(c);camera.projectWorld(x,y,0,projected);
                grid[index+3+c*2]=(float)(projected[0]/camera.imgW);
                grid[index+4+c*2]=(float)(projected[1]/camera.imgH);
                double weight=camera.validity(projected,Bowl.MAX_THETA_DEG)*(preference[c]+.002);
                grid[index+11+c]=(float)weight;sum+=weight;
            }
            if(sum>0)for(int c=0;c<4;c++)grid[index+11+c]/=(float)sum;
        }
        float[] triangles=new float[RINGS*half*6*Bowl.STRIDE];int out=0;
        for(int r=0;r<RINGS;r++)for(int angle=0;angle<half;angle++) {
            int a=r*columns+angle,b=a+1,c=a+columns,d=c+1;
            for(int point:new int[]{a,c,b,b,c,d}) {
                System.arraycopy(grid,point*Bowl.STRIDE,triangles,out,Bowl.STRIDE);out+=Bowl.STRIDE;
            }
        }
        for(float value:triangles)if(!Float.isFinite(value))throw new IllegalArgumentException("Invalid rear ground patch");
        return triangles;
    }
}
