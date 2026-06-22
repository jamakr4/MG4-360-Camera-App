package com.drivehub.kamera.maneuver;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * Transparent activity that intercepts media and volume key events
 * while maneuver mode is active, preventing the system from acting
 * on stick-key presses (volume change, track skip, etc.).
 *
 * Touch events pass through to whatever is behind this activity.
 * The camera preview overlay (TYPE_APPLICATION_OVERLAY) floats above.
 */
public final class ManeuverKeyActivity extends Activity {

    private static volatile ManeuverKeyActivity sInstance;

    public static void start(android.content.Context context) {
        Intent i = new Intent(context, ManeuverKeyActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        context.startActivity(i);
    }

    public static void finishIfActive() {
        ManeuverKeyActivity inst = sInstance;
        if (inst != null && !inst.isFinishing() && !inst.isDestroyed()) {
            inst.finish();
        }
    }

    public static boolean isActive() {
        ManeuverKeyActivity inst = sInstance;
        return inst != null && !inst.isFinishing() && !inst.isDestroyed();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = this;

        // Touch events pass through; we only care about key events.
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        getWindow().setAttributes(params);

        // If maneuver mode is no longer active by the time we start, bail out.
        if (!ManeuverController.shouldInterceptManeuverKeys(this)) {
            finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Brought back to front — bail out if maneuver is no longer active.
        if (!ManeuverController.shouldInterceptManeuverKeys(this)) {
            finish();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!ManeuverController.shouldInterceptManeuverKeys(this)) {
            finish();
            return super.dispatchKeyEvent(event);
        }
        if ((event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP)
                && isMediaOrVolumeKey(event.getKeyCode())) {
            return true; // consume — prevent system from acting on stick keys
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sInstance == this) {
            sInstance = null;
        }
    }

    private static boolean isMediaOrVolumeKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
                return true;
            default:
                return false;
        }
    }
}
