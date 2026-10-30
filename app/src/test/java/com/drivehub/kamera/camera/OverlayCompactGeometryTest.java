package com.drivehub.kamera.camera;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OverlayCompactGeometryTest {
    private static final float DELTA = 0.001f;

    @Test
    public void rightCameraKeepsExistingSliderMapping() {
        assertHorizontalEdges(false, 0, 25, 250f, 0f);
        assertHorizontalEdges(false, 25, 0, 0f, 250f);
        assertHorizontalEdges(false, 10, 30, 300f, 100f);
    }

    @Test
    public void leftCameraReversesSliderMapping() {
        assertHorizontalEdges(true, 0, 25, 0f, 250f);
        assertHorizontalEdges(true, 25, 0, 250f, 0f);
        assertHorizontalEdges(true, 10, 30, 100f, 300f);
    }

    @Test
    public void cropFollowsBothCameraRotations() {
        for (boolean leftCamera : new boolean[]{false, true}) {
            for (int[] crop : new int[][]{{0, 25}, {25, 0}, {10, 30}, {45, 45}}) {
                OverlayCompactGeometry flat = OverlayCompactGeometry.calculate(
                        1000, 480, leftCamera, false, crop[0], crop[1]);
                OverlayCompactGeometry rotated = OverlayCompactGeometry.calculate(
                        480, 1000, leftCamera, true, crop[0], crop[1]);
                assertEquals(480, rotated.visibleWidth);
                assertEquals(flat.visibleWidth, rotated.visibleHeight);
                assertEquals(0f, rotated.translationX, DELTA);
                assertEquals(leftCamera ? -flat.translationX : flat.translationX,
                        rotated.translationY, DELTA);
                // After either side camera's turn, the left control crops the
                // bottom and the right control crops the top.
                float top = (1000 - rotated.visibleHeight) / 2f - rotated.translationY;
                float bottom = (1000 - rotated.visibleHeight) / 2f + rotated.translationY;
                assertEquals(crop[1] * 10f, top, DELTA);
                assertEquals(crop[0] * 10f, bottom, DELTA);
            }
        }
    }

    @Test
    public void zeroCropRestoresFullSizeAndClearsBothOffsets() {
        for (boolean leftCamera : new boolean[]{false, true}) {
            for (boolean rotated : new boolean[]{false, true}) {
                OverlayCompactGeometry geometry = OverlayCompactGeometry.calculate(
                        480, 1000, leftCamera, rotated, 0, 0);
                assertEquals(480, geometry.visibleWidth);
                assertEquals(1000, geometry.visibleHeight);
                assertEquals(0f, geometry.translationX, DELTA);
                assertEquals(0f, geometry.translationY, DELTA);
            }
        }
    }

    private void assertHorizontalEdges(boolean leftCamera, int leftCrop, int rightCrop,
            float expectedLeft, float expectedRight) {
        OverlayCompactGeometry geometry = OverlayCompactGeometry.calculate(
                1000, 480, leftCamera, false, leftCrop, rightCrop);
        float left = (1000 - geometry.visibleWidth) / 2f - geometry.translationX;
        float right = (1000 - geometry.visibleWidth) / 2f + geometry.translationX;
        assertEquals(expectedLeft, left, DELTA);
        assertEquals(expectedRight, right, DELTA);
        assertEquals(480, geometry.visibleHeight);
        assertEquals(0f, geometry.translationY, DELTA);
    }
}
