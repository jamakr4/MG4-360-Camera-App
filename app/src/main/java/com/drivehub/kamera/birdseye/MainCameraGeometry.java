package com.drivehub.kamera.birdseye;

/** Physical pixels after the root's active insets. One common scale and origin. */
public final class MainCameraGeometry {
    public final int[][] panels = new int[3][4];
    public MainCameraGeometry(int width, int height) {
        double s = Math.min(Math.max(0, width)/1920.0, Math.max(0, height)/640.0);
        int x = (int)Math.round((width-1920*s)/2), y = (int)Math.round((height-640*s)/2);
        int[] edges = {0, 540, 1470, 1920};
        for (int i = 0; i < 3; i++) panels[i] = new int[]{x+(int)Math.round(edges[i]*s), y,
                x+(int)Math.round(edges[i+1]*s), y+(int)Math.round(640*s)};
    }
}
