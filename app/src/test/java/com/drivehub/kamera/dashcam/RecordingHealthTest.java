package com.drivehub.kamera.dashcam;
import org.junit.Test;
import static org.junit.Assert.*;
public class RecordingHealthTest {
    @Test public void activityRequiresSuccessfulSamplesAndExpires() {
        assertEquals(RecordingHealth.State.STARTING, RecordingHealth.evaluate(0, 4000));
        assertEquals(RecordingHealth.State.RECORDING, RecordingHealth.evaluate(1, 0));
        assertEquals(RecordingHealth.State.ERROR, RecordingHealth.evaluate(42, 5001));
        assertEquals(RecordingHealth.State.ERROR, RecordingHealth.evaluate(-1, 0));
    }
    @Test public void watchdogExcludesOemButDetectsHungSegment() {
        assertFalse(RecordingHealth.segmentOverdue(90_000, 30_000, false));
        assertTrue(RecordingHealth.segmentOverdue(90_001, 30_000, false));
        assertFalse(RecordingHealth.segmentOverdue(200_000, 30_000, true));
    }
}
