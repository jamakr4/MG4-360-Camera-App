package com.drivehub.kamera.camera;

/** Compact clipping in camera coordinates, followed by the preview's quarter turn. */
final class OverlayCompactGeometry {
    final int visibleWidth;
    final int visibleHeight;
    final float translationX;
    final float translationY;

    private OverlayCompactGeometry(int visibleWidth, int visibleHeight,
            float translationX, float translationY) {
        this.visibleWidth = visibleWidth;
        this.visibleHeight = visibleHeight;
        this.translationX = translationX;
        this.translationY = translationY;
    }

    static OverlayCompactGeometry calculate(int width, int height, boolean leftCamera,
            boolean rotated, int leftCrop, int rightCrop) {
        int cameraWidth = rotated ? height : width;
        int visibleCameraWidth = Math.max(1,
                Math.round(cameraWidth * (1f - (leftCrop + rightCrop) / 100f)));
        // The two side cameras face opposite ways. Preserve the right camera's
        // slider mapping and reverse it on the left camera.
        float offset = cameraWidth * (rightCrop - leftCrop) / -200f;
        if (leftCamera) offset = -offset;
        if (rotated) {
            // View translations use parent axes: rotate the crop offset as well.
            return new OverlayCompactGeometry(width, visibleCameraWidth,
                    0f, leftCamera ? -offset : offset);
        }
        return new OverlayCompactGeometry(visibleCameraWidth, height, offset, 0f);
    }

    /** Full camera corners after a quarter turn, relative to the visible viewport. */
    float[] rotatedContentCorners(int fullWidth, int fullHeight, boolean leftCamera) {
        float centerX = visibleWidth / 2f + translationX;
        float centerY = visibleHeight / 2f + translationY;
        float halfWidth = fullWidth / 2f;
        float halfHeight = fullHeight / 2f;
        // Texture corners are ordered TL, TR, BR, BL before rotation.
        if (leftCamera) {
            return new float[]{
                    centerX - halfWidth, centerY + halfHeight,
                    centerX - halfWidth, centerY - halfHeight,
                    centerX + halfWidth, centerY - halfHeight,
                    centerX + halfWidth, centerY + halfHeight
            };
        }
        return new float[]{
                centerX + halfWidth, centerY - halfHeight,
                centerX + halfWidth, centerY + halfHeight,
                centerX - halfWidth, centerY + halfHeight,
                centerX - halfWidth, centerY - halfHeight
        };
    }
}
