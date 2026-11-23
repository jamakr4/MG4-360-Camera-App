package com.drivehub.kamera.birdseye;

import com.drivehub.kamera.rivela.Bowl;
import com.drivehub.kamera.rivela.FisheyeCamera;
import com.drivehub.kamera.steering.SteeringModel;
import com.drivehub.kamera.steering.WheelGuides;
import org.junit.Test;
import static org.junit.Assert.*;

public class BirdseyeWideMeshTest {
    @Test public void wideViewDoublesTheFieldOfViewAndUsesTheBowlCameraCoordinates() {
        double flat=RivelaScene.GUIDANCE_FLAT_MM;
        BirdseyeSynthetic frames=new BirdseyeSynthetic();RivelaScene scene=new RivelaScene(frames.calibration,flat);
        BirdseyeMesh normal=new BirdseyeMesh(frames.calibration),wide=new BirdseyeMesh(scene);
        assertEquals(normal.groundWidth*2,wide.groundWidth,0);assertEquals(normal.groundHeight*2,wide.groundHeight,0);
        assertEquals(normal.indexCount,wide.indexCount);assertTrue(wide.coveredVertices>0);
        for(int row=0;row<=BirdseyeMesh.ROWS;row+=7)for(int col=0;col<=BirdseyeMesh.COLS;col+=7) {
            int at=(row*(BirdseyeMesh.COLS+1)+col)*BirdseyeMesh.FLOATS;
            double x=scene.car.centerX+wide.vertices.get(at)*wide.groundWidth/2;
            double y=scene.car.centerY-wide.vertices.get(at+1)*wide.groundHeight/2;
            double z=Bowl.surfaceZ(scene.car,x,y,flat,Bowl.CURVE_MM);
            for(int camera=0;camera<4;camera++) {
                double[] p=new double[3];FisheyeCamera cam=scene.camera(camera);cam.projectWorld(x,y,z,p);
                assertEquals(Math.max(-10000,Math.min(10000,p[0])),wide.vertices.get(at+2+camera*3),.002);
                assertEquals(Math.max(-10000,Math.min(10000,p[1])),wide.vertices.get(at+3+camera*3),.002);
            }
        }
        // The larger metric floor can cover the entire Wide window. The bowl still
        // rises outside it; visible UVs above must use the same surface as the 3D scene.
        assertTrue(Bowl.surfaceZ(scene.car,scene.car.centerX+scene.car.width/2+flat+500,
                scene.car.centerY,flat,Bowl.CURVE_MM)<0);
        for(int i=0;i<wide.vertices.capacity();i++)assertTrue(Float.isFinite(wide.vertices.get(i)));
    }
    @Test public void inverseBowlSurfaceMatchesTheOriginalThreeDMesh() {
        RivelaScene scene=new RivelaScene(new BirdseyeSynthetic().calibration,4000);scene.prepareBowl();
        float[] points=scene.bowl().vertices;
        for(int i=0;i<points.length;i+=Bowl.STRIDE*13)
            assertEquals(points[i+2],Bowl.surfaceZ(scene.car,points[i],points[i+1],4000,Bowl.CURVE_MM),.03);
        assertEquals(0,Bowl.surfaceZ(scene.car,scene.car.centerX,scene.car.centerY,4000,Bowl.CURVE_MM),0);
    }
    @Test public void continuedGuidanceStaysOnTheFlatPartAtBothLocksAndAllWidths() {
        double flat=RivelaScene.GUIDANCE_FLAT_MM;
        RivelaScene scene=new RivelaScene(new BirdseyeSynthetic().calibration,flat);
        WheelGuides guides=new WheelGuides();SteeringModel model=new SteeringModel();
        for(WheelGuides.Direction direction:WheelGuides.Direction.values())for(WheelGuides.Geometry geometry:WheelGuides.Geometry.values())
            for(int width:new int[]{1,1836,2060,3000})for(float raw:new float[]{0,-199.3f,199.3f,-440,440,-516.95f,516.95f}) {
                java.util.List<com.drivehub.kamera.rivela.PdcBars.Bar> paths=new java.util.ArrayList<>(
                        guides.corridor(model.solve(raw),scene.car.centerX,scene.car.centerY,direction,width,geometry).boundaries);
                paths.addAll(guides.clearanceLines(model.solve(raw),scene.car.centerX,scene.car.centerY,direction,width,geometry));
                for(com.drivehub.kamera.rivela.PdcBars.Bar edge:paths)for(int i=0;i<edge.xy.length;i+=2)
                    assertEquals(0,Bowl.surfaceZ(scene.car,edge.xy[i],edge.xy[i+1],flat,Bowl.CURVE_MM),0);
            }
    }
}
