package com.drivehub.kamera.birdseye;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Arrays;

/** Independently implemented Rodrigues / equidistant fisheye ground projection.
 * World-to-camera: q = R * (x,y,0) + t. Inferred world units: millimetres.
 * Camera order: left, right, front, rear; reference pixels: unmirrored 720x480.
 * The header and two reserved doubles per camera remain uninterpreted.
 */
public final class BirdseyeCalibration {
    public static final int WIDTH = 720, HEIGHT = 480, BYTE_COUNT = 520;
    public static final double MAX_THETA = Math.toRadians(88);
    public static final String VEHICLE_PATH = "/configure/camera_calibdata.bin";
    public final double header, centerX, centerY;
    public final String sha256;
    private final Camera[] cameras;

    private static final class Camera {
        double fx, fy, cx, cy;
        final double[] k = new double[4], t = new double[3], center = new double[3];
        double[][] r;
        double radius(double theta) {
            double a = theta * theta;
            return theta * (1 + a * (k[0] + a * (k[1] + a * (k[2] + a * k[3]))));
        }
    }

    public static final class Projection {
        public final double u, v, weight;
        Projection(double u, double v, double weight) { this.u = u; this.v = v; this.weight = weight; }
    }

    private BirdseyeCalibration(Camera[] cameras, double header, String sha256) {
        this.cameras = cameras; this.header = header; this.sha256 = sha256;
        centerX = cameras[0].center[0]/2 + cameras[1].center[0]/2;
        centerY = cameras[2].center[1]/2 + cameras[3].center[1]/2;
    }

    public static BirdseyeCalibration load(Path path) throws IOException {
        // Bound the read even if a corrupted file is huge or changes during the read.
        try (InputStream in = Files.newInputStream(path)) {
            byte[] bytes = new byte[BYTE_COUNT+1];
            int count = 0, read;
            while (count < bytes.length && (read = in.read(bytes, count, bytes.length-count)) != -1) {
                if (read == 0) throw new IOException("Calibration read made no progress");
                count += read;
            }
            return parse(Arrays.copyOf(bytes, count));
        }
    }

    public static BirdseyeCalibration parse(byte[] bytes) {
        if (bytes.length != BYTE_COUNT) throw new IllegalArgumentException("Expected 520 calibration bytes");
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        double[] a = new double[65];
        for (int i = 0; i < a.length; i++) {
            a[i] = b.getDouble();
            if (!Double.isFinite(a[i])) throw new IllegalArgumentException("Non-finite calibration field " + i);
        }
        Camera[] cameras = new Camera[4];
        for (int i = 0; i < 4; i++) {
            Camera c = new Camera(); int start = 1 + i * 16;
            c.fx = a[start]; c.fy = a[start+1]; c.cx = a[start+2]; c.cy = a[start+3];
            if (c.fx <= 0 || c.fy <= 0 || c.cx < 0 || c.cx >= WIDTH || c.cy < 0 || c.cy >= HEIGHT)
                throw new IllegalArgumentException("Invalid camera intrinsics " + i);
            System.arraycopy(a, start+6, c.k, 0, 4);
            c.r = rotation(a[start+10], a[start+11], a[start+12]);
            System.arraycopy(a, start+13, c.t, 0, 3);
            for (int axis = 0; axis < 3; axis++) {
                for (int row = 0; row < 3; row++) c.center[axis] -= c.r[row][axis] * c.t[row];
                if (!Double.isFinite(c.center[axis])) throw new IllegalArgumentException("Invalid camera position " + i);
            }
            // Validate the derived matrix too; finite input can overflow in the calculations.
            for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
                double dot = 0;
                for (int k = 0; k < 3; k++) dot += c.r[row][k] * c.r[col][k];
                if (!Double.isFinite(dot) || Math.abs(dot - (row == col ? 1 : 0)) > 1e-9)
                    throw new IllegalArgumentException("Invalid rotation " + i);
            }
            double previous = 0;
            for (int j = 1; j <= 180; j++) {
                double radius = c.radius(MAX_THETA * j / 180);
                if (!Double.isFinite(radius) || radius <= previous)
                    throw new IllegalArgumentException("Non-monotonic fisheye polynomial " + i);
                previous = radius;
            }
            cameras[i] = c;
        }
        try {
            StringBuilder hash = new StringBuilder();
            for (byte v : MessageDigest.getInstance("SHA-256").digest(bytes))
                hash.append(String.format(Locale.ROOT, "%02x", v & 255));
            return new BirdseyeCalibration(cameras, a[0], hash.toString());
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static double[][] rotation(double x, double y, double z) {
        double angle = Math.hypot(Math.hypot(x, y), z);
        if (!Double.isFinite(angle)) throw new IllegalArgumentException("Rotation overflow");
        if (angle < 1e-12) return new double[][]{{1,0,0}, {0,1,0}, {0,0,1}};
        x /= angle; y /= angle; z /= angle;
        double c = Math.cos(angle), s = Math.sin(angle), q = 1-c;
        return new double[][]{{c+x*x*q, x*y*q-z*s, x*z*q+y*s},
                {x*y*q+z*s, c+y*y*q, y*z*q-x*s}, {x*z*q-y*s, y*z*q+x*s, c+z*z*q}};
    }

    /** Immutable validated parameters; array access returns defensive copies. */
    public static final class CameraParameters {
        public final double fx, fy, cx, cy;
        private final double[] distortion, rotation, translation;
        private CameraParameters(Camera c) {
            fx=c.fx; fy=c.fy; cx=c.cx; cy=c.cy;
            distortion=c.k.clone(); translation=c.t.clone(); rotation=new double[9];
            for(int row=0;row<3;row++) for(int col=0;col<3;col++) rotation[row*3+col]=c.r[row][col];
        }
        public double[] distortion() { return distortion.clone(); }
        public double[] rotation() { return rotation.clone(); }
        public double[] translation() { return translation.clone(); }
    }
    public CameraParameters cameraParameters(int camera) { return new CameraParameters(cameras[camera]); }

    public double[] cameraCenter(int camera) { return cameras[camera].center.clone(); }

    /** Coordinates in reference pixels. Zero weight explicitly means outside coverage. */
    public Projection project(int camera, double x, double y) {
        Camera c = cameras[camera];
        double qx = c.r[0][0]*x + c.r[0][1]*y + c.t[0];
        double qy = c.r[1][0]*x + c.r[1][1]*y + c.t[1];
        double qz = c.r[2][0]*x + c.r[2][1]*y + c.t[2];
        double radial = Math.hypot(qx, qy), theta = Math.atan2(radial, qz);
        double scale = radial < 1e-12 ? 0 : c.radius(theta) / radial;
        double u = c.cx + c.fx*qx*scale, v = c.cy + c.fy*qy*scale;
        if (!Double.isFinite(u) || !Double.isFinite(v)) return new Projection(0, 0, 0);
        double border = Math.min(Math.min(u, WIDTH-1-u), Math.min(v, HEIGHT-1-v));
        double weight = theta >= MAX_THETA || border <= 0 ? 0
                : Math.pow(Math.cos(theta), 4) * Math.min(1, border/16);
        return new Projection(u, v, weight);
    }

    /** Inverse fisheye ray intersected with z=0, for independent synthetic inputs/tests. */
    public double[] groundPoint(int camera, double u, double v) {
        Camera c = cameras[camera];
        double nx = (u-c.cx)/c.fx, ny = (v-c.cy)/c.fy, radius = Math.hypot(nx, ny);
        if (radius >= c.radius(MAX_THETA)) return null;
        double lo = 0, hi = MAX_THETA;
        for (int j = 0; j < 50; j++) {
            double mid = (lo+hi)/2;
            if (c.radius(mid) < radius) lo = mid; else hi = mid;
        }
        double theta = (lo+hi)/2, scale = radius < 1e-12 ? 0 : Math.sin(theta)/radius;
        double[] local = {nx*scale, ny*scale, Math.cos(theta)}, ray = new double[3];
        for (int axis = 0; axis < 3; axis++) for (int row = 0; row < 3; row++) ray[axis] += c.r[row][axis]*local[row];
        if (Math.abs(ray[2]) < 1e-12) return null;
        double t = -c.center[2]/ray[2];
        if (!Double.isFinite(t) || t <= 0) return null;
        return new double[]{c.center[0]+t*ray[0], c.center[1]+t*ray[1]};
    }
}
