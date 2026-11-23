package com.drivehub.kamera.birdseye;

import org.junit.Test;
import static org.junit.Assert.*;

public class BirdseyeFrameHealthTest {
    @Test public void guidanceUsesEachCamerasOwnAgeWithoutTheInterCameraSkewLimit() {
        long now=5_000_000;
        long[] receipts={now,now-200_000,now-500_000,now-500_001};
        assertEquals(1,BirdseyeFrameHealth.freshMask(receipts,now));
        assertEquals(7,BirdseyeFrameHealth.guidanceMask(receipts,now));
        assertEquals(15,BirdseyeFrameHealth.displayMask(receipts,now));
        assertEquals(3,BirdseyeFrameHealth.guidanceMask(receipts,now+1));
        receipts[3]=now+1;
        assertEquals(11,BirdseyeFrameHealth.guidanceMask(receipts,now+1));
    }
    @Test public void guidanceRejectsMissingAndFutureReceiptsEvenWhenOtherCamerasAreCurrent() {
        long now=5_000_000;
        assertEquals(0,BirdseyeFrameHealth.guidanceMask(new long[]{0,-1,now+1,now+100},now));
        assertEquals(4,BirdseyeFrameHealth.guidanceMask(new long[]{0,0,now,0},now));
        assertEquals(8,BirdseyeFrameHealth.guidanceMask(new long[]{0,0,0,now},now));
    }
    @Test public void briefCameraGapsKeepImagesWithoutMakingThemFresh() {
        long now=5_000_000;
        long[] receipts={now,now-200_000,now-600_000,now-2_000_000};
        assertEquals(1,BirdseyeFrameHealth.freshMask(receipts,now));
        assertEquals(15,BirdseyeFrameHealth.displayMask(receipts,now));
        assertEquals(7,BirdseyeFrameHealth.displayMask(receipts,now+1));
    }
    @Test public void eachCameraExpiresIndependentlyAndItsFirstNewReceiptRecoversIt() {
        long now=5_000_000;
        long[] receipts={now,now-2_000_001,now-1_999_999,0};
        assertEquals(5,BirdseyeFrameHealth.displayMask(receipts,now));
        receipts[1]=now+1;
        assertEquals(7,BirdseyeFrameHealth.displayMask(receipts,now+1));
        assertEquals(3,BirdseyeFrameHealth.displayMask(receipts,now+2));
    }
    @Test public void imagesNeverReceivedAndFutureReceiptsCannotBeHeld() {
        long now=5_000_000;
        assertEquals(0,BirdseyeFrameHealth.displayMask(new long[]{0,-1,now+1,now+100},now));
    }
}
