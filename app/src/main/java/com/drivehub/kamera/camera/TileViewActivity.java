package com.drivehub.kamera.camera;

import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.R;

import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.signal.SignalService;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;

public class TileViewActivity extends AppCompatActivity {

    private static final String ACTION_MANEUVER_SHOW =
            "com.drivehub.kamera.action.MANEUVER_TILE_SHOW";
    private static final String ACTION_MANEUVER_HIDE =
            "com.drivehub.kamera.action.MANEUVER_TILE_HIDE";
    private static final String EXTRA_MANEUVER_CAMERA_INDEX = "camera_index";

    private static volatile boolean sTileViewVisible = false;
    private static volatile boolean sManeuverVisible = false;

    public static boolean isVisible() {
        return sTileViewVisible;
    }

    public static boolean isManeuverVisible() {
        return sManeuverVisible;
    }

    public static void showManeuver(Context context, int cameraIndex) {
        if (context == null) return;
        Intent i = new Intent(context, TileViewActivity.class);
        i.setAction(ACTION_MANEUVER_SHOW);
        i.putExtra(EXTRA_MANEUVER_CAMERA_INDEX, cameraIndex);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        context.startActivity(i);
    }

    public static void hideManeuver(Context context) {
        if (context == null) return;
        Intent i = new Intent(ACTION_MANEUVER_HIDE);
        i.setPackage(context.getPackageName());
        context.sendBroadcast(i);
    }

    private static final int[] SURFACE_IDS = { R.id.sfFront, R.id.sfRight, R.id.sfLeft, R.id.sfRear };
    private static final int[] TILE_IDS = { R.id.tileFront, R.id.tileRight, R.id.tileLeft, R.id.tileRear };
    private static final int[] CAMERA_INDICES = {
        CameraIndex.FRONT.getVideoIndex(),
        CameraIndex.RIGHT.getVideoIndex(),
        CameraIndex.LEFT.getVideoIndex(),
        CameraIndex.REAR.getVideoIndex()
    };

    private final SurfaceHolder[] holders = new SurfaceHolder[4];
    private final SurfaceHolder.Callback[] callbacks = new SurfaceHolder.Callback[4];
    private android.content.SharedPreferences prefs;
    private BroadcastReceiver maneuverReceiver;
    private boolean maneuverSession = false;
    private int activeManeuverCameraIndex = -1;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener prefListener = (sharedPreferences,
            key) -> {
        if (UiPrefs.KEY_TILE_CORNER_RADIUS.equals(key)) {
            applyCornerRadius();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tile_view);
        prefs = UiPrefs.getPrefs(this);

        applyCornerRadius();
        setupManeuverReceiver();
        handleManeuverIntent(getIntent());

        for (int i = 0; i < SURFACE_IDS.length; i++) {
            final int cameraIndex = CAMERA_INDICES[i];
            SurfaceView sv = findViewById(SURFACE_IDS[i]);
            SurfaceHolder holder = sv.getHolder();
            holders[i] = holder;

            callbacks[i] = new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder h) {
                    CameraProbe.attachPreview(cameraIndex, h.getSurface());
                }

                @Override
                public void surfaceChanged(SurfaceHolder h, int format, int w, int h2) {
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder h) {
                    CameraProbe.detachPreview(cameraIndex);
                }
            };
            holder.addCallback(callbacks[i]);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleManeuverIntent(intent);
    }

    private void applyCornerRadius() {
        View container = findViewById(R.id.tileContainer);
        container.post(() -> applyCornerRadiusToLaidOutViews(container));
    }

    private void applyCornerRadiusToLaidOutViews(View container) {
        float containerRadiusPx = UiPrefs.getCornerRadiusPx(container, prefs);
        if (container.getBackground() instanceof GradientDrawable) {
            GradientDrawable background = (GradientDrawable) container.getBackground().mutate();
            background.setCornerRadius(containerRadiusPx);
        }

        for (int tileId : TILE_IDS) {
            MaterialCardView card = findViewById(tileId);
            if (card != null) {
                card.setRadius(UiPrefs.getCornerRadiusPx(card, prefs));
            }
        }
        applyActiveManeuverCamera();
    }

    private void setupManeuverReceiver() {
        if (maneuverReceiver != null) return;
        maneuverReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !ACTION_MANEUVER_HIDE.equals(intent.getAction())) return;
                if (maneuverSession) {
                    finish();
                }
            }
        };
    }

    private void handleManeuverIntent(Intent intent) {
        if (intent == null || !ACTION_MANEUVER_SHOW.equals(intent.getAction())) {
            applyActiveManeuverCamera();
            return;
        }
        maneuverSession = true;
        sManeuverVisible = true;
        int cameraIndex = intent.getIntExtra(EXTRA_MANEUVER_CAMERA_INDEX, activeManeuverCameraIndex);
        if (isKnownCameraIndex(cameraIndex)) {
            activeManeuverCameraIndex = cameraIndex;
        }
        applyActiveManeuverCamera();
    }

    private void applyActiveManeuverCamera() {
        for (int i = 0; i < TILE_IDS.length; i++) {
            MaterialCardView card = findViewById(TILE_IDS[i]);
            if (card == null) continue;
            boolean active = maneuverSession && CAMERA_INDICES[i] == activeManeuverCameraIndex;
            card.setStrokeColor(active ? Color.WHITE : Color.TRANSPARENT);
            card.setStrokeWidth(active ? dp(3f) : 0);
            card.setAlpha(!maneuverSession || active ? 1f : 0.74f);
        }
    }

    private boolean isKnownCameraIndex(int cameraIndex) {
        for (int known : CAMERA_INDICES) {
            if (known == cameraIndex) return true;
        }
        return false;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onStart() {
        super.onStart();
        sTileViewVisible = true;
        if (maneuverSession) {
            sManeuverVisible = true;
        }
        OverlayService.hideOverlay(this);
        // Reset SignalService internal state so currentMode doesn't stay at 4
        // and the later onStop() recheck can actually re-evaluate.
        SignalService.requestRecheck();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        try {
            ContextCompat.registerReceiver(
                    this,
                    maneuverReceiver,
                    new IntentFilter(ACTION_MANEUVER_HIDE),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onStop() {
        sTileViewVisible = false;
        if (maneuverSession) {
            sManeuverVisible = false;
        }
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        try {
            unregisterReceiver(maneuverReceiver);
        } catch (Throwable ignored) {
        }
        SignalService.requestRecheck();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (maneuverSession) {
            sManeuverVisible = false;
        }
        for (int i = 0; i < holders.length; i++) {
            if (holders[i] != null && callbacks[i] != null) {
                holders[i].removeCallback(callbacks[i]);
            }
        }
        CameraProbe.detachAllPreviews();
    }
}
