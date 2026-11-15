package com.drivehub.kamera.rivela;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws ground lines (parking-sensor bars, guide lines...) "into" a camera image.
 * <p>
 * Contributed by andneg7 (Rivela 360). A line on the ground with a real width in millimetres becomes a filled band in
 * the fisheye image: both edges are projected point by point (every {@link #STEP_MM} mm, because straight lines on the
 * ground become curves in a fisheye image), so the band gets thinner with distance exactly like a painted line would.
 * Parts that leave the image or the lens circle are cut, and the band restarts when it comes back.
 * <p>
 * Output: polygons in the camera's IMAGE pixels (0..imgW, 0..imgH). Map them to your view with the same crop, scale
 * and mirroring you apply to the camera frame. With Android's Canvas, each polygon is one Path (moveTo the first
 * point, lineTo the others, close) filled with the bar colour.
 */
public final class GroundProjector {
    /** subdivision along the line (mm) */
    public static final double STEP_MM = 50;
    /** points farther than this from the optical axis are not drawn (degrees) */
    public static final double MAX_THETA_DEG = 95;

    private GroundProjector() {}

    /** Polygons (x0,y0,x1,y1,...) for one ground polyline xy (mm) of width widthMm. */
    public static List<float[]> band(FisheyeCamera cam, double[] xy, double widthMm) {
        List<float[]> out = new ArrayList<>();
        List<double[]> left = new ArrayList<>(), right = new ArrayList<>();
        double[] pa = new double[3], pb = new double[3];
        int np = xy.length / 2;
        for (int j = 0; j + 1 < np; j++) {
            double x0 = xy[2 * j], y0 = xy[2 * j + 1], x1 = xy[2 * j + 2], y1 = xy[2 * j + 3];
            double len = Math.hypot(x1 - x0, y1 - y0);
            if (len < 1e-6) continue;
            double px = -(y1 - y0) / len * widthMm / 2, py = (x1 - x0) / len * widthMm / 2;   // half-width normal
            int n = Math.max(1, (int) (len / STEP_MM));
            for (int k = (j == 0 ? 0 : 1); k <= n; k++) {
                double t = k / (double) n, cx = x0 + (x1 - x0) * t, cy = y0 + (y1 - y0) * t;
                if (visible(cam, cx + px, cy + py, pa) && visible(cam, cx - px, cy - py, pb)) {
                    left.add(new double[]{pa[0], pa[1]});
                    right.add(new double[]{pb[0], pb[1]});
                } else flush(left, right, out);
            }
        }
        flush(left, right, out);
        return out;
    }

    /** All the bars of {@link PdcBars} as polygons, with their colours (same order). */
    public static List<float[]> bars(FisheyeCamera cam, List<PdcBars.Bar> bars, List<Integer> colorsOut) {
        List<float[]> out = new ArrayList<>();
        for (PdcBars.Bar b : bars) {
            for (float[] poly : band(cam, b.xy, b.widthMm)) { out.add(poly); if (colorsOut != null) colorsOut.add(b.color); }
        }
        return out;
    }

    private static boolean visible(FisheyeCamera cam, double X, double Y, double[] p) {
        cam.projectWorld(X, Y, 0, p);
        return p[2] <= MAX_THETA_DEG && p[0] >= 0 && p[0] <= cam.imgW && p[1] >= 0 && p[1] <= cam.imgH;
    }

    private static void flush(List<double[]> l, List<double[]> r, List<float[]> out) {
        if (l.size() >= 2) {
            float[] poly = new float[(l.size() + r.size()) * 2];
            int o = 0;
            for (double[] q : l) { poly[o++] = (float) q[0]; poly[o++] = (float) q[1]; }
            for (int i = r.size() - 1; i >= 0; i--) { poly[o++] = (float) r.get(i)[0]; poly[o++] = (float) r.get(i)[1]; }
            out.add(poly);
        }
        l.clear(); r.clear();
    }
}
