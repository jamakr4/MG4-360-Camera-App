package com.drivehub.kamera.dashcam;

/** Physical MG4 display coordinates, independent of the foreground app's work area. */
final class StatusIconPosition {
    static final int SCREEN_WIDTH = 1920;
    static final int SCREEN_HEIGHT = 720;

    private StatusIconPosition() {}

    static int clamp(int position, int screenSize, int iconSize) {
        return Math.max(0, Math.min(Math.max(0, screenSize - iconSize), position));
    }

    static int windowX(int screenLeft, int iconWidth, int launcherWidth) {
        // Keep CENTER_HORIZONTAL gravity and its existing projection correction.
        // The factory work area starts to the right of the launcher bar.
        return clamp(screenLeft, SCREEN_WIDTH, iconWidth)
                - (SCREEN_WIDTH + launcherWidth - iconWidth) / 2;
    }
}
