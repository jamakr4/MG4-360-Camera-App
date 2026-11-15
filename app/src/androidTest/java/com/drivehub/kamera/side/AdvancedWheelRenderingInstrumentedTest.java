package com.drivehub.kamera.side;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.drivehub.kamera.birdseye.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AdvancedWheelRenderingInstrumentedTest {
    private final BirdseyeCalibration calibration=BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes());
    private int[] image(int height){int[] rgb=new int[720*height];for(int y=0;y<height;y++)for(int x=0;x<720;x++){int referenceY=height==240?y*2:y;rgb[y*720+x]=0xFF000000|((x/3)<<16)|((referenceY/2)<<8)|37;}return rgb;}
    private WheelSillPatch.Map map(int side,boolean front){return new WheelSillPatch.Map(calibration,side,front,254,224);}
    @Test public void nativeAdvancedMatchesSharedLensMathForReferenceAndTopFieldInputs(){
        int[] full=image(480);
        for(int side=0;side<2;side++){
            WheelSillPatch.Map front=map(side,true),rear=map(side,false);
            int[] expected=front.render(full);float[] uv=front.coordinates();
            for(int height:new int[]{240,480}){
                int[] output=SidePreviewDebugBridge.compose(image(height),height,side==0,true,2,2,16.5f,-45,false,SideCameraGeometry.previewCrops(side==0),uv,rear.coordinates());
                assertNotNull(output);assertEquals(254*456,output.length);double error=0;int count=0;
                for(int i=0;i<254*224;i++)if(!Float.isNaN(uv[i*2]))for(int shift=0;shift<24;shift+=8){error+=Math.abs(((expected[i]>>>shift)&255)-((output[i]>>>shift)&255));count++;}
                assertTrue("mean RGB error="+error/count,error/count<1.2);
            }
        }
    }
    @Test public void mixedAxlesAndFallbackKeepLegacyPixelsAndMissingAreaToggle(){
        int[] source=image(480);WheelSillPatch.Map front=map(0,true),rear=map(0,false);
        float[] crops=SideCameraGeometry.previewCrops(true),uvFront=front.coordinates(),uvRear=rear.coordinates();
        int[] basic=SidePreviewDebugBridge.compose(source,480,true,true,1,1,16.5f,-45,true,crops,null,null);
        int[] mixed=SidePreviewDebugBridge.compose(source,480,true,true,2,1,16.5f,-45,true,crops,uvFront,uvRear);
        assertNotNull(basic);assertNotNull(mixed);
        for(int i=232*254;i<mixed.length;i++)assertEquals(basic[i],mixed[i]);
        int[] none=SidePreviewDebugBridge.compose(source,480,true,true,0,0,16.5f,-45,true,crops,null,null);
        int[] rearNone=SidePreviewDebugBridge.compose(source,480,true,true,2,0,16.5f,-45,true,crops,uvFront,uvRear);
        for(int i=232*254;i<rearNone.length;i++)assertEquals(none[i],rearNone[i]);
        int[] frontNone=SidePreviewDebugBridge.compose(source,480,true,true,0,2,16.5f,-45,true,crops,uvFront,uvRear);
        for(int i=0;i<224*254;i++)assertEquals(none[i],frontNone[i]);
        int[] fallback=SidePreviewDebugBridge.compose(source,480,true,true,2,2,16.5f,-45,true,crops,null,null);assertArrayEquals(basic,fallback);
        int[] black=new int[720*480];java.util.Arrays.fill(black,0xFF000000);
        uvFront[0]=uvFront[1]=Float.NaN;
        int[] highlighted=SidePreviewDebugBridge.compose(black,480,true,true,2,0,16.5f,-45,true,crops,uvFront,uvRear);
        int[] plain=SidePreviewDebugBridge.compose(black,480,true,true,2,0,16.5f,-45,false,crops,uvFront,uvRear);
        assertNotEquals(0xFF000000,highlighted[0]);assertEquals(0xFF000000,plain[0]);
        for(int i=1;i<254*224;i++)if(!Float.isNaN(uvFront[i*2]))assertEquals(0xFF000000,highlighted[i]);
    }
    @Test public void fullSidesMatchVehicleAlignedGroundMapsAndKeepOriginalFallback(){
        int[] full=image(480);
        for(int side=0;side<2;side++){
            SideGroundPatch.Map map=new SideGroundPatch.Map(calibration,side,SideGroundPatch.defaults(calibration,side,SideGroundPatch.SIDE),254,456);
            float[] uv=map.coordinates();int[] expected=map.render(full);float[] crops=SideCameraGeometry.previewCrops(side==0);
            for(int height:new int[]{240,480}){
                int[] actual=SidePreviewDebugBridge.composeSides(image(height),height,side==0,true,false,crops,uv);
                assertNotNull(actual);assertEquals(254*456,actual.length);double error=0;int count=0;
                for(int i=0;i<actual.length;i++)if(!Float.isNaN(uv[i*2]))for(int shift=0;shift<24;shift+=8){error+=Math.abs(((expected[i]>>>shift)&255)-((actual[i]>>>shift)&255));count++;}
                assertTrue("Side mean RGB error="+error/count,error/count<1.2);
            }
            int[] original=SidePreviewDebugBridge.composeSides(full,480,side==0,false,false,crops,null);
            assertArrayEquals(original,SidePreviewDebugBridge.composeSides(full,480,side==0,false,true,crops,uv));
            assertArrayEquals(original,SidePreviewDebugBridge.composeSides(full,480,side==0,true,true,crops,null));
            uv[0]=uv[1]=Float.NaN;int[] black=new int[720*480];java.util.Arrays.fill(black,0xFF000000);
            int[] hatch=SidePreviewDebugBridge.composeSides(black,480,side==0,true,true,crops,uv);
            int[] plain=SidePreviewDebugBridge.composeSides(black,480,side==0,true,false,crops,uv);
            assertNotEquals(plain[0],hatch[0]);assertEquals(0xFF000000,plain[0]);
            for(int i=1;i<hatch.length;i++)if(!Float.isNaN(uv[i*2]))assertEquals(0xFF000000,hatch[i]);
        }
    }
    @Test public void sixMapsPublishThroughTheLiveJniAndReuseTheCalibrationCache(){
        float[][] uv=WheelLensMaps.get(calibration);String key=WheelLensMaps.key(calibration);
        int old=1_000_000_000,current=old+1;
        try{
            SideCameraNative.setAdvancedSession(old);
            assertTrue(SideCameraNative.setAdvancedMaps(old,key,uv[0],uv[1],uv[2],uv[3],uv[4],uv[5]));
            assertTrue(SideCameraNative.setAdvancedMaps(old,key,null,null,null,null,null,null));
            assertFalse(SideCameraNative.setAdvancedMaps(old,key,uv[0],uv[1],uv[2],uv[3],uv[0],uv[5]));
            SideCameraNative.setAdvancedSession(current);
            assertFalse(SideCameraNative.setAdvancedMaps(old,key,null,null,null,null,null,null));
            assertTrue(SideCameraNative.setAdvancedMaps(current,key,null,null,null,null,null,null));
            SideCameraNative.setAdvancedSession(-old);
            assertTrue(SideCameraNative.setAdvancedMaps(current,key,null,null,null,null,null,null));
        }finally{SideCameraNative.setAdvancedSession(-current);SideCameraNative.setAdvancedSession(-old);}
    }
}
