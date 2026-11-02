package com.drivehub.kamera.camera;

/** Fits the shared signal-camera crop into the cluster's square without further cropping. */
final class IpkPreviewGeometry {
    final int width, height;
    // Full camera TL, TR, BR, BL in viewport coordinates, for TextureView.setTransform().
    final float[] contentCorners;

    private IpkPreviewGeometry(int width, int height, float[] contentCorners) {
        this.width = width;
        this.height = height;
        this.contentCorners = contentCorners;
    }

    static IpkPreviewGeometry calculate(int side, boolean leftCamera, boolean rotated,
            int leftCrop, int rightCrop) {
        // Fast capture has fewer vertical samples; the logical image is still 720x480.
        int fullWidth = rotated ? 480 : 720;
        int fullHeight = rotated ? 720 : 480;
        OverlayCompactGeometry crop = OverlayCompactGeometry.calculate(
                fullWidth, fullHeight, leftCamera, rotated, leftCrop, rightCrop);
        float fit = Math.min(side / (float) crop.visibleWidth, side / (float) crop.visibleHeight);
        int width = Math.max(1, Math.min(side, Math.round(crop.visibleWidth * fit)));
        int height = Math.max(1, Math.min(side, Math.round(crop.visibleHeight * fit)));
        float[] corners;
        if (rotated) {
            corners = crop.rotatedContentCorners(fullWidth, fullHeight, leftCamera);
        } else {
            float x = (crop.visibleWidth - fullWidth) / 2f + crop.translationX;
            float y = (crop.visibleHeight - fullHeight) / 2f + crop.translationY;
            corners = new float[]{x, y, x + fullWidth, y,
                    x + fullWidth, y + fullHeight, x, y + fullHeight};
        }
        for (int i = 0; i < corners.length; i += 2) {
            corners[i] *= width / (float) crop.visibleWidth;
            corners[i + 1] *= height / (float) crop.visibleHeight;
        }
        return new IpkPreviewGeometry(width, height, corners);
    }
}
