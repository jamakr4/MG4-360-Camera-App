package com.drivehub.kamera.rivela;

import java.util.ArrayList;
import java.util.List;

/**
 * Rear parking-sensor bars at their real distance, as curved bands on the ground behind the bumper.
 * <p>
 * Contributed by andneg7 (Rivela 360). The same geometry is drawn in three places: the top view, the 3D view and
 * the camera image (see {@link GroundProjector}). Because the bars are computed on the ground in millimetres,
 * they line up with the real obstacle in every view.
 *
 * <h3>Geometry</h3>
 * Four sectors (left to right) share one centre on the car's axis, {@link #R0} mm in front of the rear bumper,
 * so each bar is an arc roughly parallel to the bumper. The four sectors cover 76 degrees (19 each) with a
 * {@link #GAP_DEG} gap between them. Each bar is {@link #BAR_WIDTH} mm thick and its OUTER edge sits at the
 * sector's distance from the bumper.
 *
 * <h3>Levels and distances</h3>
 * The car reports one level per sector (1 = closest ... 9 = farthest, 0 = nothing) and, separately, the exact
 * distance in cm of the closest obstacle. Rivela 360 uses that exact distance for the sector(s) at the closest level,
 * and a level-to-cm table for the others. The table is NOT included here (it comes from our analysis of the stock
 * app): pass your own mapping, e.g. measured with a cardboard box at known distances. Colour bands:
 * level 1 red, 2-6 yellow, 7-9 green.
 */
public final class PdcBars {
    /** radius of the arc centre in front of the rear bumper (mm): bars follow the bumper curve */
    public static final double R0 = 1500;
    /** bar thickness on the ground (mm) */
    public static final double BAR_WIDTH = 120;
    /** gap between two sectors (degrees) */
    public static final double GAP_DEG = 2;
    /** ARGB colours of the three bands: near (red), middle (yellow), far (green) */
    public static final int[] BAND_COLOR = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00};

    /** Level (1..9) -> distance from the bumper in cm. Supply your own. */
    public interface LevelToCm { int cm(int level); }

    /** One bar: colour and the centre line of the band on the ground (x0,y0,x1,y1,... in mm). */
    public static final class Bar {
        public final int color;
        public final float widthMm;
        public final double[] xy;
        Bar(int color, float widthMm, double[] xy) { this.color = color; this.widthMm = widthMm; this.xy = xy; }
    }

    /** 0 red, 1 yellow, 2 green, -1 nothing */
    public static int band(int level) {
        return level == 1 ? 0 : (level >= 2 && level <= 6) ? 1 : (level >= 7 && level <= 9) ? 2 : -1;
    }

    /**
     * Target distance (mm, -1 = off) and band of each sector. Sectors at the closest level use the exact distance
     * reported by the car (closestCm, ignored when <= 0 or >= 255).
     */
    public static void targets(int[] levels, int closestCm, LevelToCm table, double[] dmm, int[] bands) {
        int vMin = 99;
        for (int v : levels) if (v >= 1 && v <= 9) vMin = Math.min(vMin, v);
        for (int s = 0; s < 4; s++) {
            int v = levels[s];
            bands[s] = band(v);
            dmm[s] = bands[s] < 0 ? -1 : (v == vMin && closestCm > 0 && closestCm < 255 ? closestCm : table.cm(v)) * 10.0;
        }
    }

    /** Bars at the given distances (mm per sector, < 0 = off). widthScale scales the bar thickness. */
    public static List<Bar> at(CarFrame car, double[] dmm, int[] bands, float widthScale) {
        List<Bar> out = new ArrayList<>();
        final double cx = car.centerX, cy = car.rear() - R0;
        final double bw = BAR_WIDTH * widthScale;
        final int n = 16;
        for (int s = 0; s < 4; s++) {
            if (bands[s] < 0 || dmm[s] < 0) continue;
            double rc = R0 + dmm[s] - bw / 2;                                        // centre of the band
            double a0 = -38 + 19 * s + GAP_DEG / 2, a1 = -38 + 19 * (s + 1) - GAP_DEG / 2;
            double[] xy = new double[(n + 1) * 2];
            for (int i = 0; i <= n; i++) {
                double a = Math.toRadians(a0 + (a1 - a0) * i / (double) n);
                xy[2 * i] = cx + rc * Math.sin(a);
                xy[2 * i + 1] = cy + rc * Math.cos(a);
            }
            out.add(new Bar(BAND_COLOR[bands[s]], (float) bw, xy));
        }
        return out;
    }

    /**
     * Smooth movement: the car reports levels in steps of 15-20 cm, so each bar glides to its new position in about
     * a quarter of a second instead of jumping. A sector that turns on starts directly at its position.
     * Call {@link #set} when new sensor data arrives (e.g. every 100 ms) and {@link #bars} from the drawing code
     * (any frame rate).
     */
    public static final class Anim {
        private final double[] tgt = new double[4], cur = new double[4];
        private final int[] bands = {-1, -1, -1, -1};
        private long last;
        /** time constant (ms): after 250 ms about 6% of the way is left */
        public static final double TAU_MS = 90;

        public synchronized void set(int[] levels, int closestCm, LevelToCm table) {
            double[] d = new double[4]; int[] b = new int[4];
            targets(levels, closestCm, table, d, b);
            for (int i = 0; i < 4; i++) {
                if (b[i] < 0 || bands[i] < 0) cur[i] = d[i];   // off, or just turned on: no glide
                tgt[i] = d[i]; bands[i] = b[i];
            }
        }

        /** true while a bar is still gliding (keep redrawing) */
        public synchronized boolean moving() {
            for (int i = 0; i < 4; i++) if (bands[i] >= 0 && Math.abs(tgt[i] - cur[i]) > 2) return true;
            return false;
        }

        public synchronized List<Bar> bars(CarFrame car, long nowMs, float widthScale) {
            double dt = last == 0 ? 0 : Math.min(200, nowMs - last);
            last = nowMs;
            double k = 1 - Math.exp(-dt / TAU_MS);
            for (int i = 0; i < 4; i++) cur[i] += (tgt[i] - cur[i]) * k;
            return at(car, cur.clone(), bands.clone(), widthScale);
        }
    }
}
