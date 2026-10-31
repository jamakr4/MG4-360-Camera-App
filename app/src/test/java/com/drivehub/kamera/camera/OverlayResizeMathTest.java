package com.drivehub.kamera.camera;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OverlayResizeMathTest {

    private static final float DELTA = 0.001f;

    @Test
    public void rotatedCompactViewCanGrowBeyondUncroppedScreenHeight() {
        int[] size = OverlayResizeMath.clampSize(3840, 480f / 1000f,
                240, 3840, 1920, 720, 1f, 0.75f);
        OverlayCompactGeometry visible = OverlayCompactGeometry.calculate(
                size[0], size[1], false, true, 0, 25);

        assertTrue(size[1] > 720);
        assertEquals(720, visible.visibleHeight);
        assertTrue(visible.visibleWidth <= 1920);
    }

    @Test
    public void unrotatedCompactViewCanGrowBeyondUncroppedScreenWidth() {
        int[] size = OverlayResizeMath.clampSize(3840, 1000f / 480f,
                240, 3840, 1000, 720, 0.75f, 1f);
        OverlayCompactGeometry visible = OverlayCompactGeometry.calculate(
                size[0], size[1], false, false, 0, 25);

        assertTrue(size[0] > 1000);
        assertEquals(1000, visible.visibleWidth);
        assertTrue(visible.visibleHeight <= 720);
    }

    @Test
    public void visibleWindowFitsAcrossRotationCropAndScreenModes() {
        for (boolean rotated : new boolean[]{false, true}) {
            for (int crop : new int[]{0, 25, 45, 90}) {
                for (int availableWidth : new int[]{1000, 1778, 1920}) {
                    for (int availableHeight : new int[]{520, 720}) {
                        float fraction = 1f - crop / 100f;
                        int[] size = OverlayResizeMath.clampSize(10000,
                                rotated ? 480f / 1000f : 1000f / 480f,
                                240, 3840, availableWidth, availableHeight,
                                rotated ? 1f : fraction, rotated ? fraction : 1f);
                        OverlayCompactGeometry visible = OverlayCompactGeometry.calculate(
                                size[0], size[1], true, rotated,
                                Math.min(45, crop), Math.max(0, crop - 45));
                        assertTrue(visible.visibleWidth <= availableWidth);
                        assertTrue(visible.visibleHeight <= availableHeight);
                        assertTrue(size[0] <= 3840 && size[1] <= 3840);
                        // Reapplying layout constraints must not shrink a valid size.
                        int[] repeated = OverlayResizeMath.clampSize(size[0],
                                rotated ? 480f / 1000f : 1000f / 480f,
                                240, 3840, availableWidth, availableHeight,
                                rotated ? 1f : fraction, rotated ? fraction : 1f);
                        assertEquals(size[0], repeated[0]);
                        assertEquals(size[1], repeated[1]);
                    }
                }
            }
        }
    }

    @Test
    public void zeroCropStillLimitsFullPreviewToScreen() {
        int[] landscape = OverlayResizeMath.clampSize(3840, 1000f / 480f,
                240, 3840, 1920, 720, 1f, 1f);
        int[] portrait = OverlayResizeMath.clampSize(3840, 480f / 1000f,
                240, 3840, 1920, 720, 1f, 1f);
        assertEquals(720, landscape[1]);
        assertTrue(portrait[1] >= 718 && portrait[1] <= 720);
        assertTrue(landscape[0] <= 1920);
        assertTrue(portrait[0] <= 1920);
    }

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
