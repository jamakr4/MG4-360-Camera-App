package com.drivehub.kamera.camera;

/** IPK_CLUSTER_TAB_BTN_STS values, independent of the cluster app's internal enum. */
public final class IpkRoutePolicy {
    public static final int TAB_PROPERTY = 0x21407b18;
    public static final int NAVIGATION = 1;
    public static final int MEDIA = 2;

    private IpkRoutePolicy() { }

    public static int normalizeScreen(int screen) {
        return screen == MEDIA ? MEDIA : NAVIGATION;
    }

    public static boolean isSelectedScreen(Integer tab, int selectedScreen) {
        return tab != null && tab == normalizeScreen(selectedScreen);
    }

    public static int[] bounds(int width, int height) {
        int side = Math.max(0, Math.min(260, Math.min(width, height)));
        int x = width >= 424 + side ? (width + 424 - side) / 2 : (width - side) / 2;
        int y = height >= 32 + side ? (height + 32 - side) / 2 : (height - side) / 2;
        return new int[]{x, y, side};
    }

    public static int previewHeight(int width) {
        // Both fast and enhanced capture represent the full logical 720 x 480 rear image.
        return Math.max(1, Math.round(width * (480f / 720f)));
    }
}
