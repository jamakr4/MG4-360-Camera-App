package com.drivehub.kamera.camera;

import android.app.Application;

/** Restore the external gate before Activities, Services or Receivers can open cameras. */
public final class CameraHandoffApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        CameraHandoffController.initialize(this);
    }
}
