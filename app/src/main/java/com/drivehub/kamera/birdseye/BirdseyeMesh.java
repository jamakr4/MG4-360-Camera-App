package com.drivehub.kamera.birdseye;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/** Static 4-pixel mesh, rebuilt only for calibration/view changes, never per frame. */
public final class BirdseyeMesh {
    public static final float CAR_WIDTH = (float)com.drivehub.kamera.steering.VehicleGeometry.MG4.widthMm,
            CAR_LENGTH = (float)com.drivehub.kamera.steering.VehicleGeometry.MG4.lengthMm, MARGIN = 1500;
    public static final float GROUND_HEIGHT = CAR_LENGTH + 2*MARGIN;
    public static final float GROUND_WIDTH = GROUND_HEIGHT * 450/640;
    public static final int FLOATS = 14, COLS = 113, ROWS = 160;
    public final FloatBuffer vertices;
    public final ShortBuffer indices;
    public final int indexCount = COLS * ROWS * 6;
    public final int coveredVertices;
    public final float groundWidth,groundHeight;

    public BirdseyeMesh(BirdseyeCalibration calibration) {
        this(calibration,null);
    }
    /** Same Rivela bowl surface as 3D, resampled into a fixed top-view grid at twice the normal field of view. */
    public BirdseyeMesh(RivelaScene wideScene) {this(wideScene.calibration,wideScene);}
    private BirdseyeMesh(BirdseyeCalibration calibration,RivelaScene wideScene) {
        groundWidth=GROUND_WIDTH*(wideScene==null?1:2);groundHeight=GROUND_HEIGHT*(wideScene==null?1:2);
        vertices = ByteBuffer.allocateDirect((COLS+1)*(ROWS+1)*FLOATS*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        indices = ByteBuffer.allocateDirect(indexCount*2).order(ByteOrder.nativeOrder()).asShortBuffer();
        int covered = 0;
        for (int row = 0; row <= ROWS; row++) for (int col = 0; col <= COLS; col++) {
            float nx = 2f*col/COLS-1, ny = 1-2f*row/ROWS;
            double x = calibration.centerX + nx*groundWidth/2, y = calibration.centerY - ny*groundHeight/2;
            double z=wideScene==null?0:com.drivehub.kamera.rivela.Bowl.surfaceZ(wideScene.car,x,y,
                    RivelaScene.GUIDANCE_FLAT_MM,com.drivehub.kamera.rivela.Bowl.CURVE_MM);
            double[] preference=wideScene==null?null:com.drivehub.kamera.rivela.Bowl.preference(wideScene.car,x,y,
                    wideScene.car.front(),wideScene.car.rear(),wideScene.car.width/2);
            vertices.put(nx).put(ny);
            double weight = 0;
            for (int camera = 0; camera < 4; camera++) {
                double u,v,w;
                if(wideScene==null) {
                    BirdseyeCalibration.Projection p=calibration.project(camera,x,y);u=p.u;v=p.v;w=p.weight;
                }else {
                    double[] p=new double[3];com.drivehub.kamera.rivela.FisheyeCamera cam=wideScene.camera(camera);
                    cam.projectWorld(x,y,z,p);u=p[0];v=p[1];
                    w=cam.validity(p,com.drivehub.kamera.rivela.Bowl.MAX_THETA_DEG)*(preference[camera]+.002);
                }
                vertices.put((float)Math.max(-10000, Math.min(10000, u)));
                vertices.put((float)Math.max(-10000, Math.min(10000, v))).put((float)w);
                weight += w;
            }
            if (weight > 1e-8 && (Math.abs(x-calibration.centerX) > CAR_WIDTH/2
                    || Math.abs(y-calibration.centerY) > CAR_LENGTH/2)) covered++;
        }
        for (int row = 0; row < ROWS; row++) for (int col = 0; col < COLS; col++) {
            int a = row*(COLS+1)+col, b = a+COLS+1;
            indices.put((short)a).put((short)b).put((short)(a+1));
            indices.put((short)(a+1)).put((short)b).put((short)(b+1));
        }
        coveredVertices = covered;
        if (covered == 0) throw new IllegalArgumentException("Calibration has no ground coverage");
        vertices.position(0); indices.position(0);
    }
}
