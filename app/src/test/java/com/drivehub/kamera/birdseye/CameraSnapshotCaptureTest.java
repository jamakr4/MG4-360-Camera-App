package com.drivehub.kamera.birdseye;

import org.junit.Test;
import static org.junit.Assert.*;

public class CameraSnapshotCaptureTest {
    private CameraSnapshotCapture.Frame frame(long time){
        return new CameraSnapshotCapture.Frame(new byte[CameraSnapshotCapture.BYTES],time,1,5,1440);
    }
    @Test public void requiresFourNewFramesAndBoundsCrossCameraSkew(){
        CameraSnapshotCapture.Frame a=frame(1000),b=frame(101000);
        assertEquals(100000,new CameraSnapshotCapture.Set(new CameraSnapshotCapture.Frame[]{a,b,a,b},1000).skewUs);
        rejected(new CameraSnapshotCapture.Frame[]{a,a,a},1000);
        rejected(new CameraSnapshotCapture.Frame[]{a,a,a,null},1000);
        rejected(new CameraSnapshotCapture.Frame[]{a,a,a,a},1001);
        rejected(new CameraSnapshotCapture.Frame[]{a,a,a,frame(101001)},1000);
    }
    private void rejected(CameraSnapshotCapture.Frame[] frames,long request){
        try{new CameraSnapshotCapture.Set(frames,request);fail("Expected invalid snapshot set");}
        catch(IllegalArgumentException expected){}
    }
}
