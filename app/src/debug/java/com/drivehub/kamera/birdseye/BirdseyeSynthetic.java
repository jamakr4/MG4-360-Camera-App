package com.drivehub.kamera.birdseye;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Debug-only flat ground, no vehicle images or calibration. Uses the live UYVY/GLES contract. */
public final class BirdseyeSynthetic implements BirdseyeRenderer.Frames {
    public final BirdseyeCalibration calibration = BirdseyeCalibration.parse(calibrationBytes());
    // Immutable test pixels are shared; enable flags, timestamps and delays stay per instance.
    private final byte[][] fields = Pattern.FIELDS;
    public volatile boolean enabled = true;
    public volatile boolean throwOnCopy;
    public volatile int missingMask;
    public volatile long fixedClockUs;
    public final long[] delayedUs = new long[4];

    private static final class Pattern {
        static final byte[][] FIELDS=create();
        private static byte[][] create() {
            byte[][] fields=new byte[4][720*480*2];
            BirdseyeCalibration calibration=BirdseyeCalibration.parse(calibrationBytes());
            for (int c = 0; c < 4; c++) for (int y = 0; y < 480; y++) for (int x = 0; x < 720; x += 2) {
                int a = pixel(calibration,c, x, y), b = pixel(calibration,c, x+1, y);
                double[] ya = yuv(a), yb = yuv(b);
                int i = (y*720+x)*2;
                fields[c][i] = channel((ya[1]+yb[1])/2);
                fields[c][i+1] = channel(ya[0]);
                fields[c][i+2] = channel((ya[2]+yb[2])/2);
                fields[c][i+3] = channel(yb[0]);
            }
            return fields;
        }
    }

    public static byte[] calibrationBytes() {
        ByteBuffer b = ByteBuffer.allocate(520).order(ByteOrder.LITTLE_ENDIAN);
        b.putDouble(42); // Deliberately uninterpreted.
        double[][] centers = {{-900,0,900}, {900,0,900}, {0,-2100,900}, {0,2100,900}};
        for (double[] c : centers) {
            double[] a = {180,180,360,240, -13,27, 0,0,0,0, Math.PI,0,0, -c[0],c[1],c[2]};
            for (double v : a) b.putDouble(v);
        }
        return b.array();
    }
    private static int pixel(BirdseyeCalibration calibration,int c, double u, double v) {
        double[] p = calibration.groundPoint(c,u,v);
        return p == null ? 0x192029 : groundColor(p[0],p[1]);
    }
    /** Smooth directional reference: red increases to the right, green towards the rear. */
    public static int groundColor(double x, double y) {
        return (clamp(128+x*0.025)<<16) | (clamp(128+y*0.018)<<8) | 170;
    }
    private static int clamp(double v) { return (int)Math.max(0,Math.min(255,Math.round(v))); }
    private static byte channel(double v) { return (byte)clamp(v); }
    private static double[] yuv(int rgb) {
        int r=(rgb>>16)&255, g=(rgb>>8)&255, b=rgb&255;
        return new double[]{16+(65.481*r+128.553*g+24.966*b)/255,
                128+(-37.797*r-74.203*g+112*b)/255, 128+(112*r-93.786*g-18.214*b)/255};
    }
    @Override public long copy(int camera, ByteBuffer output, long afterUs) {
        if (throwOnCopy) throw new IllegalStateException("Injected window upload failure");
        long t = clockUs()-delayedUs[camera];
        if (!enabled || (missingMask & (1<<camera)) != 0 || t <= afterUs) return 0;
        output.position(0); output.put(fields[camera]); output.position(0);
        return t;
    }
    @Override public long clockUs() { return fixedClockUs > 0 ? fixedClockUs : System.nanoTime()/1000; }
    @Override public boolean allowed() { return enabled; }
}
