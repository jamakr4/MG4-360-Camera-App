package com.drivehub.kamera.birdseye;

import org.junit.Test;
import static org.junit.Assert.*;

public class BirdseyeFrameHealthTest {
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
