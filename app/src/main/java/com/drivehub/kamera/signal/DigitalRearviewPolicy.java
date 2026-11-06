package com.drivehub.kamera.signal;

/** The mirror's two outputs share an enabled state, but only the main output can be tapped away. */
public final class DigitalRearviewPolicy {
    public static final int DISPLAY_INFOTAINMENT = 0;
    public static final int DISPLAY_IPK = 1;
    public static final int DISPLAY_BOTH = 2;

    private DigitalRearviewPolicy() { }

    public static final class Outputs {
        public final boolean infotainment, ipk;

        private Outputs(boolean infotainment, boolean ipk) {
            this.infotainment = infotainment;
            this.ipk = ipk;
        }
    }

    public static Outputs selectOutputs(boolean enabled, int target, boolean temporarilyHidden,
            boolean appUiVisible, boolean vehicleBlocked) {
        // Our own app/settings only replace the infotainment overlay. The IPK
        // is an independent output and must not be interrupted by those screens.
        return new Outputs(
                showOnInfotainment(enabled, target, temporarilyHidden, vehicleBlocked || appUiVisible),
                showOnIpk(enabled, target, vehicleBlocked));
    }

    public static int normalizeDisplayTarget(int target) {
        return target >= DISPLAY_INFOTAINMENT && target <= DISPLAY_BOTH
                ? target : DISPLAY_INFOTAINMENT;
    }

    public static boolean usesInfotainment(int target) {
        return normalizeDisplayTarget(target) != DISPLAY_IPK;
    }

    public static boolean usesIpk(int target) {
        return normalizeDisplayTarget(target) != DISPLAY_INFOTAINMENT;
    }

    public static boolean showOnInfotainment(boolean enabled, int target,
            boolean temporarilyHidden, boolean blocked) {
        return enabled && usesInfotainment(target) && !temporarilyHidden && !blocked;
    }

    public static boolean showOnIpk(boolean enabled, int target, boolean blocked) {
        return enabled && usesIpk(target) && !blocked;
    }
}
