package com.drivehub.kamera.rivela;

/**
 * Where the car body is in the world frame (mm; x right, y towards the rear, z down).
 * <p>
 * Contributed by andneg7 (Rivela 360). A practical way to get the centre is from the camera positions:
 * centre x = midpoint of the two side cameras, centre y = midpoint of the front and rear cameras
 * (the front and rear cameras sit almost on the bumpers).
 */
public final class CarFrame {
    /** MG4 Electric official body size (without mirrors). */
    public static final double MG4_LENGTH = 4287, MG4_WIDTH = 1836;

    public final double centerX, centerY, length, width;

    public CarFrame(double centerX, double centerY, double length, double width) {
        this.centerX = centerX; this.centerY = centerY; this.length = length; this.width = width;
    }

    /** Car centred between the side cameras (x) and between the front and rear cameras (y). */
    public static CarFrame fromCameras(FisheyeCamera left, FisheyeCamera right, FisheyeCamera front, FisheyeCamera rear,
                                       double length, double width) {
        return new CarFrame((left.C[0] + right.C[0]) / 2, (front.C[1] + rear.C[1]) / 2, length, width);
    }

    public double front() { return centerY - length / 2; }
    public double rear() { return centerY + length / 2; }
}
