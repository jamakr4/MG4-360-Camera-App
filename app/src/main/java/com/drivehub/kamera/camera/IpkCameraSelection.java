package com.drivehub.kamera.camera;

/** Selects an IPK source from SignalService modes independently of the main-screen output. */
public final class IpkCameraSelection {
    private IpkCameraSelection() { }

    public static int selectVideo(int mode, boolean signalEnabled, boolean rearviewEnabled, boolean blocked) {
        if (blocked) return -1;
        switch (mode) {
            case 1:
                if (signalEnabled) return CameraIndex.LEFT.getVideoIndex();
                break;
            case 2:
                if (signalEnabled) return CameraIndex.RIGHT.getVideoIndex();
                break;
            case 4:
                break;
            default:
                // Includes reverse (3), off (0) and transition states.
                return -1;
        }
        return rearviewEnabled ? CameraIndex.REAR.getVideoIndex() : -1;
    }

    static boolean startsNewSession(int previousVideo, int nextVideo) {
        return previousVideo < 0 || (previousVideo == CameraIndex.REAR.getVideoIndex())
                != (nextVideo == CameraIndex.REAR.getVideoIndex());
    }
}
