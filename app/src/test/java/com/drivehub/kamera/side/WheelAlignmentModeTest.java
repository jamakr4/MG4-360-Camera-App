package com.drivehub.kamera.side;
import org.junit.Test;
import static org.junit.Assert.*;
public class WheelAlignmentModeTest {
    @Test public void oldBooleansAndUnknownModesNeverEnableAdvanced(){
        for(String missing:new String[]{null,"","future", "ADVANCED"}){
            assertEquals(WheelAlignmentMode.NONE,WheelAlignmentMode.fromId(missing,false));
            assertEquals(WheelAlignmentMode.BASIC,WheelAlignmentMode.fromId(missing,true));
        }
        for(WheelAlignmentMode mode:WheelAlignmentMode.values()){
            assertEquals(mode,WheelAlignmentMode.fromId(mode.id,false));assertEquals(mode,WheelAlignmentMode.fromId(mode.id,true));
            assertEquals(mode.ordinal(),mode.code);
        }
    }
}
