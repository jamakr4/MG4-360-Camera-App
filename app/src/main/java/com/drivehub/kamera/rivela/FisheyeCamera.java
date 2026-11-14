package com.drivehub.kamera.rivela;

/**
 * Generic fisheye camera (OpenCV fisheye model) placed in a car-centred world.
 * <p>
 * Contributed by andneg7 (Rivela 360). No car-specific data inside: you provide the intrinsics
 * (fx, fy, cx, cy, k1..k4) and the pose (R, t) of each camera, obtained your own way
 * (e.g. a checkerboard calibration with {@code cv::fisheye::calibrate} and {@code cv::solvePnP}).
 * <p>
 * World frame used by all the classes in this package: millimetres, x to the right of the car,
 * y towards the REAR, z DOWN, ground at z = 0. The camera frame is OpenCV's (x right, y down, z forward).
 */
public final class FisheyeCamera {
    /** Image size the intrinsics refer to (the MG4 side/front/rear feeds are 720 x 480). */
    public final double imgW, imgH;
    public final double fx, fy, cx, cy;
    public final double[] k = new double[4];
    /** world -> camera rotation, row-major 3x3 */
    public final double[] R = new double[9];
    /** world -> camera translation (mm) */
    public final double[] t = new double[3];
    /** camera position in the world (mm) */
    public final double[] C = new double[3];
    /** optical axis in the world (unit vector) */
    public final double[] axis = new double[3];

    public FisheyeCamera(double imgW, double imgH, double fx, double fy, double cx, double cy,
                         double[] k4, double[] rotation3x3, double[] translation) {
        this.imgW = imgW; this.imgH = imgH;
        this.fx = fx; this.fy = fy; this.cx = cx; this.cy = cy;
        System.arraycopy(k4, 0, k, 0, 4);
        System.arraycopy(rotation3x3, 0, R, 0, 9);
        System.arraycopy(translation, 0, t, 0, 3);
        for (int r = 0; r < 3; r++) {
            C[r] = -(R[r] * t[0] + R[3 + r] * t[1] + R[6 + r] * t[2]);   // C = -R^T t
            axis[r] = R[6 + r];                                            // R^T (0,0,1)
        }
    }

    /** Same, with the rotation given as a Rodrigues vector (as returned by OpenCV). */
    public static FisheyeCamera fromRvec(double imgW, double imgH, double fx, double fy, double cx, double cy,
                                         double[] k4, double[] rvec, double[] tvec) {
        double[] R = new double[9];
        rodrigues(rvec[0], rvec[1], rvec[2], R);
        return new FisheyeCamera(imgW, imgH, fx, fy, cx, cy, k4, R, tvec);
    }

    public static void rodrigues(double rx, double ry, double rz, double[] R) {
        double th = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (th < 1e-12) { R[0] = R[4] = R[8] = 1; R[1] = R[2] = R[3] = R[5] = R[6] = R[7] = 0; return; }
        double x = rx / th, y = ry / th, z = rz / th, c = Math.cos(th), s = Math.sin(th), v = 1 - c;
        R[0] = c + x * x * v;     R[1] = x * y * v - z * s; R[2] = x * z * v + y * s;
        R[3] = y * x * v + z * s; R[4] = c + y * y * v;     R[5] = y * z * v - x * s;
        R[6] = z * x * v - y * s; R[7] = z * y * v + x * s; R[8] = c + z * z * v;
    }

    /** Ray in the camera frame -> out = {u, v, theta in degrees from the optical axis}. */
    public void projectRay(double x, double y, double z, double[] out) {
        double r = Math.sqrt(x * x + y * y);
        double th = Math.atan2(r, z);
        double t2 = th * th;
        double thd = th * (1 + k[0] * t2 + k[1] * t2 * t2 + k[2] * t2 * t2 * t2 + k[3] * t2 * t2 * t2 * t2);
        double sc = r > 1e-9 ? thd / r : 0;
        out[0] = fx * x * sc + cx;
        out[1] = fy * y * sc + cy;
        out[2] = Math.toDegrees(th);
    }

    /** World point (mm) -> out = {u, v, theta in degrees}. */
    public void projectWorld(double X, double Y, double Z, double[] out) {
        double x = R[0] * X + R[1] * Y + R[2] * Z + t[0];
        double y = R[3] * X + R[4] * Y + R[5] * Z + t[1];
        double z = R[6] * X + R[7] * Y + R[8] * Z + t[2];
        projectRay(x, y, z, out);
    }

    /**
     * How usable a projected point is, 0..1: fades out over the last 4 degrees before maxThetaDeg
     * (the edge of the lens circle) and over the last pixels of the image borders.
     * Used as the blending weight between cameras.
     */
    public double validity(double[] p, double maxThetaDeg) {
        double w = clamp((maxThetaDeg - p[2]) / 4.0);
        double m = 3;
        w *= clamp((p[0] - m) / 6) * clamp((imgW - m - p[0]) / 6);
        w *= clamp((p[1] - m) / 6) * clamp((imgH - m - p[1]) / 6);
        return w;
    }

    static double clamp(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
