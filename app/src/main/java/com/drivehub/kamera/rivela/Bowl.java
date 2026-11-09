package com.drivehub.kamera.rivela;

/**
 * The "bowl" of the 3D view: a surface around the car onto which the four camera images are painted.
 * <p>
 * Contributed by andneg7 (Rivela 360). Shape: rings around the car body shaped like a rounded rectangle (a
 * superellipse, because the car is long and narrow), a FLAT floor up to {@code flatMm} from the body, then a wall that
 * rises with a parabola, steeper when {@code curveMm} is smaller. Rings are denser near the car (spacing grows with
 * t^2). On the flat floor distances are exact; on the wall tall far objects are stretched but stay recognisable.
 * Rivela 360 uses flat = 1500 and curve = 1500 (height = d^2 / 3000 beyond the flat part).
 * <p>
 * Each vertex stores its world position, its texture coordinates in all four camera images and a blending weight
 * per camera. Weights: the side camera beside the car, the front/rear camera ahead/behind it, and a smooth blend
 * around each corner (by angle, 30%..70% of the quarter turn). Every weight is also multiplied by
 * {@link FisheyeCamera#validity} so a camera fades out before the edge of its lens circle, then the four weights are
 * normalised. Draw it with {@link BowlShaders} and any perspective camera (orbit around the car).
 */
public final class Bowl {
    /** per vertex: x, y, z (mm, world) + 4 x (u, v) + 4 weights = 15 floats */
    public static final int STRIDE = 15;
    /** camera order inside the vertex: */
    public static final int LEFT = 0, RIGHT = 1, FRONT = 2, REAR = 3;
    public static final double FLAT_MM = 1500, CURVE_MM = 1500, MAX_MM = 11000;
    /** lens circle limit used for the weights (degrees from the optical axis) */
    public static final double MAX_THETA_DEG = 92;

    public final float[] vertices;
    public final short[] indices;
    public final int nx, ny;

    private Bowl(float[] v, short[] idx, int nx, int ny) { vertices = v; indices = idx; this.nx = nx; this.ny = ny; }

    /** cams in the order LEFT, RIGHT, FRONT, REAR. Rivela 360 uses nTheta = 144, nRing = 56. */
    public static Bowl build(FisheyeCamera[] cams, CarFrame car, int nTheta, int nRing, double flatMm, double curveMm) {
        int nx = nTheta + 1, ny = nRing;
        float[] v = new float[nx * ny * STRIDE];
        double[] p = new double[3];
        double front = car.front(), rear = car.rear(), halfWid = car.width / 2;
        double A = halfWid + 40, B = car.length / 2 + 40, ex = 2.0 / 6.0;   // superellipse of degree 6
        int o = 0;
        for (int j = 0; j < ny; j++) {
            double t = j / (double) (ny - 1);
            double dist = MAX_MM * t * t;
            double Z = dist <= flatMm ? 0 : -(dist - flatMm) * (dist - flatMm) / (2 * curveMm);   // z down: negative = up
            for (int i = 0; i <= nTheta; i++) {
                double th = 2 * Math.PI * i / nTheta, ct = Math.cos(th), st = Math.sin(th);
                double X = car.centerX + (A + dist) * Math.signum(ct) * Math.pow(Math.abs(ct), ex);
                double Y = car.centerY + (B + dist) * Math.signum(st) * Math.pow(Math.abs(st), ex);
                v[o] = (float) X; v[o + 1] = (float) Y; v[o + 2] = (float) Z;
                double[] pref = preference(car, X, Y, front, rear, halfWid);
                double sum = 0;
                double[] w = new double[4];
                for (int k = 0; k < 4; k++) {
                    cams[k].projectWorld(X, Y, Z, p);
                    v[o + 3 + 2 * k] = (float) (p[0] / cams[k].imgW);
                    v[o + 4 + 2 * k] = (float) (p[1] / cams[k].imgH);
                    w[k] = cams[k].validity(p, MAX_THETA_DEG) * (pref[k] + 0.002);
                    sum += w[k];
                }
                for (int k = 0; k < 4; k++) v[o + 11 + k] = (float) (sum > 0 ? w[k] / sum : 0);
                o += STRIDE;
            }
        }
        return new Bowl(v, grid(nx, ny), nx, ny);
    }

    /** Which camera "owns" a ground position: side, front/rear, or a smooth blend around the corners. */
    static double[] preference(CarFrame car, double X, double Y, double front, double rear, double halfWid) {
        double[] pref = new double[4];
        double exs = Math.abs(X - car.centerX) - halfWid, eyF = front - Y, eyR = Y - rear;
        int side = X < car.centerX ? LEFT : RIGHT;
        double ey = Math.max(eyF, eyR);
        int fr = eyF > 0 ? FRONT : REAR;
        if (ey <= 0) pref[side] = 1;
        else if (exs <= 0) pref[fr] = 1;
        else {
            double tt = Math.atan2(ey, exs) / (Math.PI / 2);
            double wf = smooth(0.3, 0.7, tt);
            pref[fr] = wf;
            pref[side] = 1 - wf;
        }
        return pref;
    }

    static double smooth(double e0, double e1, double x) {
        double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    /** two triangles per grid cell (16-bit indices: keep nx * ny below 65536) */
    static short[] grid(int nx, int ny) {
        short[] idx = new short[(nx - 1) * (ny - 1) * 6];
        int o = 0;
        for (int j = 0; j < ny - 1; j++)
            for (int i = 0; i < nx - 1; i++) {
                int a = j * nx + i, b = a + 1, c = a + nx, d = c + 1;
                idx[o++] = (short) a; idx[o++] = (short) c; idx[o++] = (short) b;
                idx[o++] = (short) b; idx[o++] = (short) c; idx[o++] = (short) d;
            }
        return idx;
    }
}
