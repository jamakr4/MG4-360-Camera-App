package com.drivehub.kamera.birdseye;

import com.drivehub.kamera.rivela.Bowl;
import com.drivehub.kamera.rivela.FisheyeCamera;
import com.drivehub.kamera.steering.SteeringModel;
import com.drivehub.kamera.steering.WheelGuides;
import org.junit.Test;
import static org.junit.Assert.*;

public class BirdseyeWideMeshTest {
    @Test public void wideViewDoublesTheFieldOfViewAndUsesTheBowlCameraCoordinates() {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();RivelaScene scene=new RivelaScene(frames.calibration,4000);
        BirdseyeMesh normal=new BirdseyeMesh(frames.calibration),wide=new BirdseyeMesh(scene);
        assertEquals(normal.groundWidth*2,wide.groundWidth,0);assertEquals(normal.groundHeight*2,wide.groundHeight,0);
        assertEquals(normal.indexCount,wide.indexCount);assertTrue(wide.coveredVertices>0);
        int bent=0;
        for(int row=0;row<=BirdseyeMesh.ROWS;row+=7)for(int col=0;col<=BirdseyeMesh.COLS;col+=7) {
            int at=(row*(BirdseyeMesh.COLS+1)+col)*BirdseyeMesh.FLOATS;
            double x=scene.car.centerX+wide.vertices.get(at)*wide.groundWidth/2;
            double y=scene.car.centerY-wide.vertices.get(at+1)*wide.groundHeight/2;
            double z=Bowl.surfaceZ(scene.car,x,y,4000,Bowl.CURVE_MM);if(z<-.01)bent++;
            for(int camera=0;camera<4;camera++) {
                double[] p=new double[3];FisheyeCamera cam=scene.camera(camera);cam.projectWorld(x,y,z,p);
                assertEquals(Math.max(-10000,Math.min(10000,p[0])),wide.vertices.get(at+2+camera*3),.002);
                assertEquals(Math.max(-10000,Math.min(10000,p[1])),wide.vertices.get(at+3+camera*3),.002);
            }
        }
        assertTrue("Wide must include the curved bowl beyond the metric floor",bent>0);
        for(int i=0;i<wide.vertices.capacity();i++)assertTrue(Float.isFinite(wide.vertices.get(i)));
    }
    @Test public void inverseBowlSurfaceMatchesTheOriginalThreeDMesh() {
        RivelaScene scene=new RivelaScene(new BirdseyeSynthetic().calibration,4000);scene.prepareBowl();
        float[] points=scene.bowl().vertices;
        for(int i=0;i<points.length;i+=Bowl.STRIDE*13)
            assertEquals(points[i+2],Bowl.surfaceZ(scene.car,points[i],points[i+1],4000,Bowl.CURVE_MM),.03);
        assertEquals(0,Bowl.surfaceZ(scene.car,scene.car.centerX,scene.car.centerY,4000,Bowl.CURVE_MM),0);
    }
    @Test public void mirrorWidthGuidanceStaysEntirelyOnTheFlatPartInBothDirections() {
        RivelaScene scene=new RivelaScene(new BirdseyeSynthetic().calibration,4000);
        WheelGuides guides=new WheelGuides();SteeringModel model=new SteeringModel();
        for(WheelGuides.Direction direction:WheelGuides.Direction.values())for(float raw:new float[]{0,-199.3f,199.3f,-516.95f,516.95f})
            for(com.drivehub.kamera.rivela.PdcBars.Bar edge:guides.corridor(model.solve(raw),scene.car.centerX,scene.car.centerY,direction).boundaries)
                for(int i=0;i<edge.xy.length;i+=2)assertEquals(0,Bowl.surfaceZ(scene.car,edge.xy[i],edge.xy[i+1],4000,Bowl.CURVE_MM),0);
    }
}
