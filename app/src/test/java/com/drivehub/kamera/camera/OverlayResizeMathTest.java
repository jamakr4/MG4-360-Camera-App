package com.drivehub.kamera.camera;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OverlayResizeMathTest {

    private static final float DELTA = 0.001f;

    @Test
    public void increasesOutwardScaleFactorByConfiguredSensitivity() {
        float result = OverlayResizeMath.updateTargetWidth(1000f, 1.1f, 240f, 1800f);

        assertTrue(result > 1100f);
        assertEquals(1000f * (float) Math.pow(1.1f, 1.5f), result, DELTA);
    }

    @Test
    public void increasesInwardScaleFactorByConfiguredSensitivity() {
        float result = OverlayResizeMath.updateTargetWidth(1000f, 0.9f, 240f, 1800f);

        assertTrue(result < 900f);
        assertEquals(1000f * (float) Math.pow(0.9f, 1.5f), result, DELTA);
    }

    @Test
    public void accumulatesSubPixelScaleChanges() {
        float targetWidth = 1000f;
        for (int i = 0; i < 10; i++) {
            targetWidth = OverlayResizeMath.updateTargetWidth(
                    targetWidth,
                    1.0004f,
                    240f,
                    1800f
            );
        }

        assertTrue(Math.round(targetWidth) > 1000);
    }

    @Test
    public void ignoresInvalidScaleFactors() {
        assertEquals(1000f,
                OverlayResizeMath.updateTargetWidth(1000f, Float.NaN, 240f, 1800f),
                DELTA);
        assertEquals(1000f,
                OverlayResizeMath.updateTargetWidth(1000f, Float.POSITIVE_INFINITY, 240f, 1800f),
                DELTA);
        assertEquals(1000f,
                OverlayResizeMath.updateTargetWidth(1000f, 0f, 240f, 1800f),
                DELTA);
        assertEquals(1000f,
                OverlayResizeMath.updateTargetWidth(1000f, -1f, 240f, 1800f),
                DELTA);
    }

    @Test
    public void clampsAtBoundsWithoutAccumulatingOvershoot() {
        float maximum = OverlayResizeMath.updateTargetWidth(900f, 2f, 240f, 1000f);
        assertEquals(1000f, maximum, DELTA);

        float backInside = OverlayResizeMath.updateTargetWidth(maximum, 0.99f, 240f, 1000f);
        assertTrue(backInside < maximum);

        float minimum = OverlayResizeMath.updateTargetWidth(300f, 0.5f, 240f, 1000f);
        assertEquals(240f, minimum, DELTA);

        float backOutside = OverlayResizeMath.updateTargetWidth(minimum, 1.01f, 240f, 1000f);
        assertTrue(backOutside > minimum);
    }
}
