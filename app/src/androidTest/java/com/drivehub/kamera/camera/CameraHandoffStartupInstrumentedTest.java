package com.drivehub.kamera.camera;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.settings.UiPrefs;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Run after RELEASE_CAMERAS + force-stop to verify restoration in a fresh process. */
@RunWith(AndroidJUnit4.class)
public class CameraHandoffStartupInstrumentedTest {
    @Test public void applicationRestoresGateBeforeStartingAnyComponent() {
        assumeTrue("Prepare with RELEASE_CAMERAS before starting this fresh-process test",
                UiPrefs.getPrefs(InstrumentationRegistry.getInstrumentation().getTargetContext())
                        .getBoolean(UiPrefs.KEY_EXTERNAL_CAMERA_PAUSED, false));
        int[] snapshot = CameraProbe.cameraOwnershipSnapshot();
        assertEquals(0, snapshot[0]);
        assertEquals(0, snapshot[1]);
        assertEquals(1, snapshot[2]);
    }
}
