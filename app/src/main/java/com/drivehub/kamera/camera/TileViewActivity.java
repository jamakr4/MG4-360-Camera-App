package com.drivehub.kamera.camera;

import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.R;

import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.signal.SignalService;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;

public class TileViewActivity extends AppCompatActivity {

    private static volatile boolean sTileViewVisible = false;

    public static boolean isVisible() {
        return sTileViewVisible;
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
    }

    @Override
    protected void onStart() {
        super.onStart();
        sTileViewVisible = true;
        OverlayService.hideOverlay(this);
        // Reset SignalService internal state so currentMode doesn't stay at 4
        // and the later onStop() recheck can actually re-evaluate.
        SignalService.requestRecheck();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
    }

    @Override
    protected void onStop() {
        sTileViewVisible = false;
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        SignalService.requestRecheck();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (int i = 0; i < holders.length; i++) {
            if (holders[i] != null && callbacks[i] != null) {
                holders[i].removeCallback(callbacks[i]);
            }
        }
        CameraProbe.detachAllPreviews();
    }
}
