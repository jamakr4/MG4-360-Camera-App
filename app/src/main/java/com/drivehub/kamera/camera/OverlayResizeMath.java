package com.drivehub.kamera.camera;

final class OverlayResizeMath {

    static final float SENSITIVITY = 1.5f;

    private OverlayResizeMath() {
    }

    /** Bounds the visible window while retaining the uncropped preview's aspect ratio. */
    static int[] clampSize(int requestedWidth, float aspect, int minWidth, int maxSize,
            int availableWidth, int availableHeight,
            float visibleWidthFraction, float visibleHeightFraction) {
        int maxWidth = Math.min(maxSize, (int) Math.floor(availableWidth / visibleWidthFraction));
        int maxHeight = Math.min(maxSize, (int) Math.floor(availableHeight / visibleHeightFraction));
        // The height is rounded to pixels below. Include all widths whose rounded
        // height fits, without forcing the hidden part of the preview onto screen.
        int heightLimitedWidth = (int) Math.floor((maxHeight + 0.499d) * aspect);
        int limit = Math.max(1, Math.min(maxWidth, heightLimitedWidth));
        int width = Math.max(Math.min(minWidth, limit), Math.min(requestedWidth, limit));
        return new int[]{width, Math.max(1, Math.round(width / aspect))};
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
