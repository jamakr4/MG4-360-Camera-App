package com.drivehub.kamera.birdseye;
import com.drivehub.kamera.rivela.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class RivelaSceneTest {
    @Test public void immutableParametersAndOriginalRivelaProjectionMatchValidatedGroundRays() {
        BirdseyeCalibration calibration=BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes());
        BirdseyeCalibration.CameraParameters c=calibration.cameraParameters(0);
        c.rotation()[0]=999;c.translation()[0]=999;c.distortion()[0]=999;
        RivelaScene scene=new RivelaScene(calibration);
        assertEquals(calibration.centerX,scene.car.centerX,1e-9);assertEquals(calibration.centerY,scene.car.centerY,1e-9);
        for(int camera=0;camera<4;camera++) {
            for(int axis=0;axis<3;axis++)assertEquals(calibration.cameraCenter(camera)[axis],scene.camera(camera).C[axis],1e-9);
            for(int x=-3000;x<=3000;x+=750)for(int y=-4000;y<=4000;y+=1000) {
                BirdseyeCalibration.Projection expected=calibration.project(camera,x,y);
                double[] actual=new double[3];scene.camera(camera).projectWorld(x,y,0,actual);
                assertEquals(expected.u,actual[0],1e-8);assertEquals(expected.v,actual[1],1e-8);
            }
        }
    }
    @Test public void bowlHasBoundedIndicesFlatNearGroundAndNormalizedValidWeights() {
        RivelaScene scene=new RivelaScene(BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes()));
        assertNull(scene.bowl());scene.prepareBowl();Bowl bowl=scene.bowl();scene.prepareBowl();assertSame(bowl,scene.bowl());
        assertEquals(145*56*15,bowl.vertices.length);assertEquals(144*55*6,bowl.indices.length);
        for(short index:bowl.indices)assertTrue((index&65535)<145*56);
        for(int row=0;row<56;row++)for(int column=0;column<145;column++) {
            int offset=(row*145+column)*15;double distance=11000*Math.pow(row/55.0,2);
            assertEquals(distance<=1500?0:-Math.pow(distance-1500,2)/3000,bowl.vertices[offset+2],.005);
            double sum=0;for(int i=0;i<15;i++)assertTrue(Float.isFinite(bowl.vertices[offset+i]));
            for(int i=0;i<4;i++){float weight=bowl.vertices[offset+11+i];assertTrue(weight>=0&&weight<=1);sum+=weight;}
            assertTrue(sum==0||Math.abs(1-sum)<1e-6);
        }
    }
    @Test public void originalRivelaAnalyticEquidistantRayIsFortyFiveDegrees() {
        FisheyeCamera c=new FisheyeCamera(720,480,180,180,360,240,new double[4],new double[]{1,0,0,0,1,0,0,0,1},new double[3]);
        double[] p=new double[3];c.projectRay(1,0,1,p);
        assertEquals(360+180*Math.PI/4,p[0],1e-9);assertEquals(240,p[1],0);assertEquals(45,p[2],1e-9);
    }
    @Test public void orbitClampsZoomAndPitchAndResets() {
        OrbitCamera c=new OrbitCamera();c.rotate(99999,99999);c.zoom(1e10f);
        assertEquals(80,c.values()[1],0);assertEquals(5000,c.values()[2],0);
        c.zoom(Float.NaN);assertEquals(5000,c.values()[2],0);c.rotate(0,-99999);c.zoom(1e-10f);
        assertEquals(25,c.values()[1],0);assertEquals(16000,c.values()[2],0);c.reset();assertArrayEquals(new float[]{35,55,11000},c.values(),0);
    }
    @Test public void objLoaderAcceptsTrianglesAndRejectsUnsafeOrNonFiniteInput() throws Exception {
        String triangle="v 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0 0\nvn 0 0 -1\nf 1/1/1 2/1/1 3/1/1\n";
        assertEquals(24,VehicleModel.parse(new java.io.StringReader(triangle)).remaining());
        for(String bad:new String[]{"",triangle.replace("v 1 0 0","v NaN 0 0"),triangle.replace("2/1/1","99/1/1"),triangle.replace("v 1 0 0","v 999999 0 0")}) {
            try{VehicleModel.parse(new java.io.StringReader(bad));fail("Invalid model accepted");}catch(java.io.IOException expected){}
        }
    }
    @Test public void groupedModelPreservesWheelRangesAndPivotsAndRejectsBrokenGroups() throws Exception {
        String vertices="v 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0 0\nvn 0 0 -1\n";
        String face="f 1/1/1 2/1/1 3/1/1\n";
        String grouped="# pivot front_left -777 -1271 -351\n# pivot rear_right 777 1458 -376\n"
                +vertices+"g body\n"+face+"g front_left\n"+face+"g rear_right\n"+face;
        VehicleModel.Parsed parsed=VehicleModel.parseParts(new java.io.StringReader(grouped));
        assertEquals(3,parsed.parts.size());assertEquals(72,parsed.vertices.remaining());
        VehicleModel.Part front=parsed.parts.get(1),rear=parsed.parts.get(2);
        assertTrue(front.steers);assertFalse(rear.steers);assertEquals(3,front.firstVertex);assertEquals(3,front.vertexCount);
        assertEquals(-777,front.pivotX,0);assertEquals(-1271,front.pivotY,0);assertEquals(-351,front.pivotZ,0);
        assertEquals(6,rear.firstVertex);assertEquals(777,rear.pivotX,0);
        for(String bad:new String[]{grouped.replace("# pivot front_left -777 -1271 -351\n",""),
                grouped.replace("-351","NaN"),grouped+"g body\n"+face,grouped.replace("g front_left","g unknown")}) {
            try{VehicleModel.parseParts(new java.io.StringReader(bad));fail("Broken wheel group accepted");}catch(java.io.IOException expected){}
        }
    }
    @Test public void visualSteeringAngleStaysFiniteAndBounded() {
        WheelSteering steering=new WheelSteering();assertEquals(0,steering.degrees(),0);
        steering.setDegrees(-20);assertEquals(-20,steering.degrees(),0);
        steering.setDegrees(Float.NaN);steering.setDegrees(Float.POSITIVE_INFINITY);assertEquals(-20,steering.degrees(),0);
        steering.setDegrees(100);assertEquals(35,steering.degrees(),0);steering.setDegrees(-100);assertEquals(-35,steering.degrees(),0);
        steering.setDegrees(0);assertEquals(0,steering.degrees(),0);
    }
}
