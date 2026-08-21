package com.drivehub.kamera.camera;

final class OverlayResizeMath {

    static final float SENSITIVITY = 1.5f;

    private OverlayResizeMath() {
    }

    static float updateTargetWidth(float currentWidth, float scaleFactor,
            float minWidth, float maxWidth) {
        if (!Float.isFinite(currentWidth)
                || !Float.isFinite(scaleFactor)
                || scaleFactor <= 0f
                || !Float.isFinite(minWidth)
                || !Float.isFinite(maxWidth)
                || minWidth > maxWidth) {
            return currentWidth;
        }

        double amplifiedFactor = Math.pow(scaleFactor, SENSITIVITY);
        double nextWidth = currentWidth * amplifiedFactor;
        if (!Double.isFinite(nextWidth)) {
            return currentWidth;
        }
        if (nextWidth <= minWidth) {
            return minWidth;
        }
        if (nextWidth >= maxWidth) {
            return maxWidth;
        }
        return (float) nextWidth;
    }
}
