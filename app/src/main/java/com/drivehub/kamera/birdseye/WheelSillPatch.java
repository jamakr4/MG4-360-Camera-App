package com.drivehub.kamera.birdseye;

import com.drivehub.kamera.side.SideCameraGeometry;

/** Vehicle-aligned angular view. Longitudinal lines stay vertical, including above-ground sills.
 * Unlike a road-plane warp this retains near-field wheel/body rays instead of stretching them
 * to distant ground intersections. Only Advanced wheel views use this projection.
 */
public final class WheelSillPatch {
    // The near-field camera image also contains rays just beyond the front hemisphere.
    // Keep a bounded 190-degree view; never extend the shared ground-projection limit.
    public static final double MAX_THETA = Math.toRadians(95);
    private static final double BETA_LIMIT = Math.toRadians(85);
    private WheelSillPatch() {}

    private static double radius(double[] k, double theta) {
        double a = theta * theta;
        return theta * (1 + a * (k[0] + a * (k[1] + a * (k[2] + a * k[3]))));
    }

    private static final class Frame {
        final BirdseyeCalibration.CameraParameters camera;
        final double[] rotation, distortion, rear, outward, origin;
        final double down, maxTheta;
        Frame(BirdseyeCalibration calibration, int side) {
            if (side < 0 || side > 1) throw new IllegalArgumentException("Side camera required");
            camera = calibration.cameraParameters(side);
            rotation = camera.rotation(); distortion = camera.distortion();
            // Inversion must be unique throughout the bounded near-field lens interval.
            double limit = MAX_THETA;
            for (int i = 0; i <= 128; i++) {
                double t = MAX_THETA * i / 128, a = t * t;
                double slope = 1 + a * (3 * distortion[0] + a * (5 * distortion[1]
                        + a * (7 * distortion[2] + a * 9 * distortion[3])));
                if (!Double.isFinite(slope) || slope <= 0) {
                    limit = MAX_THETA * Math.max(0, i - 1) / 128;
                    break;
                }
            }
            maxTheta = Math.max(BirdseyeCalibration.MAX_THETA, limit);
            double[] front = calibration.cameraCenter(2), back = calibration.cameraCenter(3);
            double dx = back[0] - front[0], dy = back[1] - front[1], length = Math.hypot(dx, dy);
            if (!Double.isFinite(length) || length < 1e-6) throw new IllegalArgumentException("Vehicle axis unavailable");
            rear = new double[]{dx / length, dy / length};
            origin = new double[]{(front[0] + back[0]) / 2, (front[1] + back[1]) / 2};
            double sign = side == 0 ? -1 : 1;
            outward = new double[]{sign * rear[1], -sign * rear[0]};
            double height = calibration.cameraCenter(side)[2];
            if (Math.abs(height) < 50) throw new IllegalArgumentException("Side camera height unavailable");
            down = -Math.signum(height);
        }

        double[] angles(double u, double v) {
            if (u < 0 || u >= 719 || v < 0 || v >= 479) return null;
            double x = (u - camera.cx) / camera.fx, y = (v - camera.cy) / camera.fy;
            double r = Math.hypot(x, y);
            if (r >= radius(distortion, maxTheta)) return null;
            double low = 0, high = maxTheta;
            for (int i = 0; i < 45; i++) {
                double middle = (low + high) / 2;
                if (radius(distortion, middle) < r) low = middle; else high = middle;
            }
            double theta = (low + high) / 2, scale = r < 1e-12 ? 0 : Math.sin(theta) / r;
            double qx = x * scale, qy = y * scale, qz = Math.cos(theta);
            // Inverse rotation: camera direction -> world direction. Translation cancels for rays.
            double wx = rotation[0] * qx + rotation[3] * qy + rotation[6] * qz;
            double wy = rotation[1] * qx + rotation[4] * qy + rotation[7] * qz;
            double wz = rotation[2] * qx + rotation[5] * qy + rotation[8] * qz;
            double lateral = outward[0] * wx + outward[1] * wy, vertical = down * wz;
            return new double[]{Math.atan2(lateral, vertical),
                    Math.atan2(rear[0] * wx + rear[1] * wy, Math.hypot(lateral, vertical))};
        }
    }

    public static final class Map {
        public final int width, height, covered;
        public final double innerAngle, outerAngle, frontAngle, rearAngle;
        private final float[] uv;
        private final Frame frame;
        private final int side;

        public Map(BirdseyeCalibration calibration, int side, boolean front, int width, int height) {
            if (width < 2 || width > 1024 || height < 2 || height > 1024)
                throw new IllegalArgumentException("Invalid bounded wheel map");
            this.width = width; this.height = height; this.side = side;
            frame = new Frame(calibration, side);
            double[] center = calibration.cameraCenter(side);
            double cameraOutward = frame.outward[0] * (center[0] - frame.origin[0])
                    + frame.outward[1] * (center[1] - frame.origin[1]);
            double bodyAngle = Math.atan2(1836 / 2.0 - cameraOutward, Math.abs(center[2]));
            innerAngle = Math.max(Math.toRadians(-80), bodyAngle - Math.toRadians(front ? 32 : 58));
            outerAngle = Math.min(Math.toRadians(70), bodyAngle + Math.toRadians(45));

            // Preserve the legacy axle's longitudinal framing, measured in camera rays rather
            // than rectilinear image-corner averages. No image fitting or private coordinates.
            SideCameraGeometry.Crop crop = SideCameraGeometry.wheel(side == 0, front);
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            int valid = 0;
            for (int row = 0; row <= 32; row++) for (int col = 0; col <= 32; col++) {
                double px = (crop.left + (crop.right - crop.left) * col / 32) * 479;
                double py = (crop.top + (crop.bottom - crop.top) * row / 32) * 719;
                double[] a = frame.angles(side == 0 ? 719 - py : py, side == 0 ? px : 479 - px);
                if (a == null || a[0] < innerAngle || a[0] > outerAngle
                        || (front ? a[1] >= 0 : a[1] <= 0)) continue;
                min = Math.min(min, a[1]); max = Math.max(max, a[1]); valid++;
            }
            if (valid < 16 || max - min < Math.toRadians(5)) {
                // A nonstandard optical orientation (e.g. the downward synthetic rig) need
                // not share the legacy pixel crop. Frame the approximate axle directly.
                double cameraAlong = frame.rear[0] * (center[0] - frame.origin[0])
                        + frame.rear[1] * (center[1] - frame.origin[1]);
                double beta = Math.atan2((front ? -1350 : 1350) - cameraAlong,
                        Math.hypot(1836 / 2.0 - cameraOutward, Math.max(50, Math.abs(center[2]) - 300)));
                min = beta - Math.toRadians(30); max = beta + Math.toRadians(30);
            }
            frontAngle = Math.max(-BETA_LIMIT, min);
            rearAngle = Math.min(BETA_LIMIT, max);
            if (rearAngle - frontAngle < Math.toRadians(5))
                throw new IllegalArgumentException("Wheel crop outside calibrated coverage");

            uv = new float[width * height * 2];
            int count = 0;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                double s = x / (double) (width - 1), t = y / (double) (height - 1);
                double alpha = innerAngle + (outerAngle - innerAngle) * (side == 0 ? 1 - s : s);
                double beta = frontAngle + (rearAngle - frontAngle) * t;
                double cross = Math.cos(beta), lateral = Math.sin(alpha) * cross;
                double wx = frame.outward[0] * lateral + frame.rear[0] * Math.sin(beta);
                double wy = frame.outward[1] * lateral + frame.rear[1] * Math.sin(beta);
                double wz = frame.down * Math.cos(alpha) * cross;
                double[] r = frame.rotation;
                double qx = r[0] * wx + r[1] * wy + r[2] * wz;
                double qy = r[3] * wx + r[4] * wy + r[5] * wz;
                double qz = r[6] * wx + r[7] * wy + r[8] * wz;
                double radial = Math.hypot(qx, qy), theta = Math.atan2(radial, qz);
                double scale = radial < 1e-12 ? 0 : radius(frame.distortion, theta) / radial;
                double u = frame.camera.cx + frame.camera.fx * qx * scale;
                double v = frame.camera.cy + frame.camera.fy * qy * scale;
                int index = (y * width + x) * 2;
                if (theta <= frame.maxTheta && Double.isFinite(u) && Double.isFinite(v)
                        && u >= 0 && u < 719 && v >= 0 && v < 479) {
                    uv[index] = (float) u; uv[index + 1] = (float) v; count++;
                } else { uv[index] = Float.NaN; uv[index + 1] = Float.NaN; }
            }
            covered = count;
            if (covered == 0) throw new IllegalArgumentException("Empty wheel coverage");
        }

        public float[] coordinates() { return uv.clone(); }
        public double[] sourcePixel(int x, int y) {
            int index = (y * width + x) * 2;
            return new double[]{uv[index], uv[index + 1]};
        }
        /** Inverse for diagnostic markers and geometric checks; normalized output coordinates. */
        public double[] outputPixel(double u, double v) {
            double[] a = frame.angles(u, v);
            if (a == null) return null;
            double s = (a[0] - innerAngle) / (outerAngle - innerAngle);
            return new double[]{(side == 0 ? 1 - s : s) * (width - 1),
                    (a[1] - frontAngle) / (rearAngle - frontAngle) * (height - 1)};
        }
        public int[] render(int[] source) { return SideLensPatch.sample(source, width, height, uv); }
    }
}
