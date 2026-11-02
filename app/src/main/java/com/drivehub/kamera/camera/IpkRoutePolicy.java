package com.drivehub.kamera.camera;

/** Tab values come from IPK_CLUSTER_TAB_BTN_STS, not the cluster app's internal enum. */
public final class IpkRoutePolicy {
    public enum RequestResult { WAITING, SENT, FAILED, CANCELLED }
    public static final int TAB_PROPERTY = 0x21407b18;
    public static final int MAX_REQUESTS = 2;
    public static final long RETRY_MS = 1500;

    private int requests;
    private long lastRequestMs;
    private boolean confirmed;

    public static boolean supportsCamera(Integer tab) {
        return tab != null && (tab == 1 || tab == 2);
    }

    public boolean shouldRequest(Integer tab, boolean autoRoute, long nowMs) {
        // Calls, warning centre and assistance have priority. Unknown state is not
        // permission to change a page. Trip and settings can request navigation.
        return !confirmed && autoRoute && tab != null && (tab == 0 || tab == 5)
                && requests < MAX_REQUESTS
                && (requests == 0 || nowMs - lastRequestMs >= RETRY_MS);
    }

    public void completed(RequestResult result, long nowMs) {
        if (result == RequestResult.SENT || result == RequestResult.FAILED) {
            requests++;
            lastRequestMs = nowMs;
        }
    }
    public int requests() { return requests; }
    public void observeTab(Integer tab) { if (supportsCamera(tab)) confirmed = true; }

    public static int[] bounds(int width, int height) {
        int side = Math.max(0, Math.min(260, Math.min(width, height)));
        // OEM content is centred between both constraints after its start/top margins.
        int x = width >= 424 + side ? (width + 424 - side) / 2 : (width - side) / 2;
        int y = height >= 32 + side ? (height + 32 - side) / 2 : (height - side) / 2;
        return new int[]{x, y, side};
    }
}
