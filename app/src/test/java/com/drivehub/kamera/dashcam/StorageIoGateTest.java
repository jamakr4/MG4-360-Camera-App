package com.drivehub.kamera.dashcam;
import org.junit.Test;
import static org.junit.Assert.*;
public class StorageIoGateTest {
    @Test public void ejectRejectsNewIoAndWaitsForExistingExport() throws Exception {
        StorageIoGate.reopen();
        assertTrue(StorageIoGate.begin());
        StorageIoGate.close();
        try {
            assertFalse(StorageIoGate.begin());
            assertFalse(StorageIoGate.awaitIdle(1));
        } finally { StorageIoGate.end(); }
        assertTrue(StorageIoGate.awaitIdle(1));
        StorageIoGate.reopen();
        assertTrue(StorageIoGate.begin());
        StorageIoGate.end();
    }
}
