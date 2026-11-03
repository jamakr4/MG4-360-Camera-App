package com.drivehub.kamera.camera;

import org.junit.Test;
import static org.junit.Assert.*;

public class IpkPreviewGeometryTest {
    private static final float EPS = 0.002f; // Subpixel rounding of the 720-pixel reference.

    @Test public void compactKeepsTheSameCameraRegionForEitherSideAndRotation() {
        for (boolean left : new boolean[]{false, true}) {
            for (boolean rotated : new boolean[]{false, true}) {
                for (int side : new int[]{180, 260, 520}) {
                    for (int[] crop : new int[][]{{9, 20}, {0, 25}, {25, 0}, {10, 30}, {45, 45}, {0, 0}}) {
                        IpkPreviewGeometry image = IpkPreviewGeometry.calculate(side, left, rotated, crop[0], crop[1]);
                        float firstX = (left ? crop[0] : crop[1]) / 100f;
                        float lastX = 1f - (left ? crop[1] : crop[0]) / 100f;
                        float[] topLeft = cameraPoint(image, 0, 0);
                        float[] bottomRight = cameraPoint(image, image.width, image.height);
                        assertEquals(rotated && left ? lastX : firstX, topLeft[0], EPS);
                        assertEquals(rotated && left ? firstX : lastX, bottomRight[0], EPS);
                        assertEquals(rotated && !left ? 1f : 0f, topLeft[1], EPS);
                        assertEquals(rotated && !left ? 0f : 1f, bottomRight[1], EPS);
                        assertTrue(image.width > 0 && image.width <= side);
                        assertTrue(image.height > 0 && image.height <= side);
                        assertEquals(side, Math.max(image.width, image.height));
                        float cameraAspect = 1.5f * (lastX - firstX);
                        float expectedAspect = rotated ? 1f / cameraAspect : cameraAspect;
                        // At most one output pixel of aspect-ratio rounding.
                        assertEquals(image.width, image.height * expectedAspect, 1f);
                    }
                }
            }
        }
    }

    @Test public void disablingCompactRestoresFullImageAndItsOriginalAspect() {
        IpkPreviewGeometry full = IpkPreviewGeometry.calculate(260, false, false, 0, 0);
        assertEquals(260, full.width);
        assertEquals(173, full.height);
        IpkPreviewGeometry turned = IpkPreviewGeometry.calculate(260, true, true, 0, 0);
        assertEquals(173, turned.width);
        assertEquals(260, turned.height);
        assertArrayEquals(new float[]{0f, 0f}, cameraPoint(full, 0, 0), EPS);
        assertArrayEquals(new float[]{1f, 1f}, cameraPoint(full, full.width, full.height), EPS);
    }

    @Test public void rearviewKeepsFullDashcamAspectDespiteSideCameraSettings() {
        for (boolean compact : new boolean[]{false, true}) {
            for (boolean rotated : new boolean[]{false, true}) {
                for (int[] crop : new int[][]{{9, 20}, {45, 45}, {0, 25}}) {
                    IpkPreviewGeometry rear = IpkPreviewGeometry.forCamera(
                            260, CameraIndex.REAR, rotated, compact, crop[0], crop[1]);
                    assertEquals(260, rear.width);
                    assertEquals(173, rear.height);
                    // Mirroring belongs to the existing native rear-camera sink;
                    // geometry must neither flip again nor lose any camera edge.
                    assertArrayEquals(new float[]{0f, 0f}, cameraPoint(rear, 0, 0), EPS);
                    assertArrayEquals(new float[]{1f, 1f}, cameraPoint(rear, rear.width, rear.height), EPS);
                }
            }
        }
    }

    @Test public void selectingASideCameraStillAppliesItsCompactAndRotationSettings() {
        IpkPreviewGeometry side = IpkPreviewGeometry.forCamera(260, CameraIndex.LEFT, true, true, 10, 30);
        float[] topLeft = cameraPoint(side, 0, 0);
        float[] bottomRight = cameraPoint(side, side.width, side.height);
        assertArrayEquals(new float[]{0.7f, 0f}, topLeft, EPS);
        assertArrayEquals(new float[]{0.1f, 1f}, bottomRight, EPS);
    }

    private float[] cameraPoint(IpkPreviewGeometry image, float x, float y) {
        float[] corners = image.contentCorners;
        float ux = corners[2] - corners[0], uy = corners[3] - corners[1];
        float vx = corners[6] - corners[0], vy = corners[7] - corners[1];
        float dx = x - corners[0], dy = y - corners[1];
        float determinant = ux * vy - uy * vx;
        return new float[]{(dx * vy - dy * vx) / determinant, (ux * dy - uy * dx) / determinant};
    }
}
