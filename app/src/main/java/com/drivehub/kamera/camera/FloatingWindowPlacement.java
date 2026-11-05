package com.drivehub.kamera.camera;

/** Converts a preferred physical screen position into coordinates of a measured window frame. */
public final class FloatingWindowPlacement {
    public final int screenX, screenY, windowX, windowY;

    private FloatingWindowPlacement(int screenX, int screenY, int originX, int originY) {
        this.screenX = screenX;
        this.screenY = screenY;
        windowX = screenX - originX;
        windowY = screenY - originY;
    }

    public static FloatingWindowPlacement calculate(int preferredX, int preferredY,
            int width, int height, int originX, int originY,
            int left, int top, int right, int bottom) {
        return new FloatingWindowPlacement(
                clamp(preferredX, left, right, width),
                clamp(preferredY, top, bottom, height), originX, originY);
    }

    private static int clamp(int value, int start, int end, int size) {
        return Math.max(start, Math.min(Math.max(start, end - size), value));
    }
}
