package com.drivehub.kamera.dashcam;
import org.junit.Test;
import static org.junit.Assert.*;
public class StorageTargetPolicyTest {
    @Test public void autoPrefersUsbAndFallsBackOnlyToWritableInternal() {
        assertEquals(1, StorageTargetPolicy.choose(0, true, true));
        assertEquals(2, StorageTargetPolicy.choose(0, false, true));
        assertEquals(-1, StorageTargetPolicy.choose(0, false, false));
    }
    @Test public void explicitTargetsNeverSilentlyUseOtherMedium() {
        assertEquals(-1, StorageTargetPolicy.choose(1, false, true));
        assertEquals(1, StorageTargetPolicy.choose(1, true, false));
        assertEquals(2, StorageTargetPolicy.choose(2, true, true));
        assertEquals(-1, StorageTargetPolicy.choose(2, true, false));
    }
}
