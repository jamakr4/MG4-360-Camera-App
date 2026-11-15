package com.drivehub.kamera.signal;

import android.content.Intent;
import com.drivehub.kamera.MainActivity;

/** Use the AVM UI's existing hard-key close contract; never stop/disable its package or service. */
public final class OemAvmControl {
    private OemAvmControl() {}
    public static Intent closeCameraIntent() {
        return new Intent(MainActivity.ACTION_HARD_KEY_CLOSE)
                .setPackage("com.saicmotor.hmi.aroundview")
                .putExtra(MainActivity.EXTRA_CLOSE_PACKAGE,"com.saicmotor.hmi.aroundview");
    }
}
