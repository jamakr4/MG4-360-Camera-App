package com.drivehub.kamera.side;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.view.ViewCompat;

import com.drivehub.kamera.R;
import com.drivehub.kamera.settings.UiPrefs;

/** The 540 x 640 reference module, scaled uniformly in physical pixels. */
public final class SideCameraPanel extends ViewGroup {
    private final SharedPreferences prefs;
    private final SideCameraView cameraView;
    private final TextView leftFailure, rightFailure;
    private final Button wheelsButton, sidesButton;
    private boolean wheels;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesListener = (p, key) -> {
        if (UiPrefs.KEY_MAIN_SIDE_CAMERA_WHEELS.equals(key) || UiPrefs.KEY_ACCENT_COLOR.equals(key)) {
            updateMode();
        } else if (UiPrefs.KEY_PARKING_FRONT_WHEEL_ALIGNMENT.equals(key)
                || UiPrefs.KEY_PARKING_REAR_WHEEL_ALIGNMENT.equals(key)
                || UiPrefs.KEY_PARKING_HIGHLIGHT_MISSING_AREAS.equals(key)
                || UiPrefs.KEY_DEV_PARKING_FRONT_WHEEL_ANGLE.equals(key)
                || UiPrefs.KEY_DEV_PARKING_REAR_WHEEL_ANGLE.equals(key)) {
            updateWheelAlignment();
        } else if (UiPrefs.KEY_OEM_AVM_ACTIVE.equals(key) || UiPrefs.KEY_OEM_AVM_COEXIST.equals(key) || UiPrefs.KEY_EXTERNAL_CAMERA_PAUSED.equals(key)
                || UiPrefs.KEY_OEM_CAMERA_ACCESS_BLOCKED.equals(key)) {
            updateOemState();
        }
    };

    public SideCameraPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(Color.BLACK);
        LayoutInflater.from(context).inflate(R.layout.view_side_cameras, this, true);
        prefs = UiPrefs.getPrefs(context);
        cameraView = findViewById(R.id.sideCameraView);
        leftFailure = findViewById(R.id.leftCameraFailure);
        rightFailure = findViewById(R.id.rightCameraFailure);
        wheelsButton = findViewById(R.id.modeWheels);
        sidesButton = findViewById(R.id.modeSides);
        cameraView.setListener(mask -> {
            leftFailure.setVisibility((mask & 1) == 0 ? VISIBLE : GONE);
            rightFailure.setVisibility((mask & 2) == 0 ? VISIBLE : GONE);
        });
        wheelsButton.setOnClickListener(v -> selectMode(true));
        sidesButton.setOnClickListener(v -> selectMode(false));
        updateMode();
        updateWheelAlignment();
    }

    public void resumeDisplay() {
        prefs.registerOnSharedPreferenceChangeListener(preferencesListener);
        updateMode();
        updateWheelAlignment();
        updateOemState();
        cameraView.resumeDisplay();
    }

    public void pauseDisplay() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferencesListener);
        cameraView.pauseDisplay();
    }

    private void selectMode(boolean value) {
        prefs.edit().putBoolean(UiPrefs.KEY_MAIN_SIDE_CAMERA_WHEELS, value).apply();
        updateMode();
    }

    private void updateMode() {
        wheels = prefs.getBoolean(UiPrefs.KEY_MAIN_SIDE_CAMERA_WHEELS, false);
        cameraView.setWheelsMode(wheels);
        styleButton(wheelsButton, wheels);
        styleButton(sidesButton, !wheels);
    }

    private void updateOemState() {
        boolean yielded = UiPrefs.isOemCameraAccessBlocked(prefs);
        leftFailure.setText(yielded ? R.string.side_camera_oem_paused : R.string.side_camera_left_missing);
        rightFailure.setText(yielded ? R.string.side_camera_oem_paused : R.string.side_camera_right_missing);
        cameraView.yieldToOem(yielded);
    }

    private void updateWheelAlignment() {
        SideCameraNative.setWheelAlignment(UiPrefs.isParkingFrontWheelAlignmentEnabled(prefs),
                UiPrefs.isParkingRearWheelAlignmentEnabled(prefs),
                UiPrefs.getDevParkingFrontWheelAngle(prefs), UiPrefs.getDevParkingRearWheelAngle(prefs));
        SideCameraNative.setHighlightMissingAreas(UiPrefs.isParkingHighlightMissingAreasEnabled(prefs));
    }

    private void styleButton(Button button, boolean selected) {
        int accent = UiPrefs.getAccentColorInt(prefs);
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected ? accent : Color.TRANSPARENT);
        background.setCornerRadius(8 * getResources().getDisplayMetrics().density);
        background.setStroke(Math.max(1, Math.round(getResources().getDisplayMetrics().density)), accent);
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), background, null));
        button.setTextColor(selected ? (UiPrefs.isLightColor(accent) ? 0xFF111111 : Color.WHITE) : accent);
        button.setSelected(selected);
        ViewCompat.setStateDescription(button,
                getContext().getString(selected ? R.string.side_camera_selected : R.string.side_camera_not_selected));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        float scale = Math.min(width / 540f, height / 640f);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int[] rect = bounds(child.getId());
            child.measure(MeasureSpec.makeMeasureSpec(Math.round(rect[2] * scale), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(rect[3] * scale), MeasureSpec.EXACTLY));
            if (child instanceof TextView) {
                ((TextView) child).setTextSize(TypedValue.COMPLEX_UNIT_PX, 26 * scale);
            } else if (child.getId() == R.id.btnClose || child.getId() == R.id.btnSettings) {
                int padding = Math.round((child.getId() == R.id.btnClose ? 8 : 12) * scale);
                child.setPadding(padding, padding, padding, padding);
            }
        }
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        float scale = Math.min((r - l) / 540f, (b - t) / 640f);
        int offsetX = Math.round(((r - l) - 540 * scale) / 2);
        int offsetY = Math.round(((b - t) - 640 * scale) / 2);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int[] rect = bounds(child.getId());
            int x = offsetX + Math.round(rect[0] * scale);
            int y = offsetY + Math.round(rect[1] * scale);
            child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
        }
    }

    private int[] bounds(int id) {
        // Keep the close hit target inside the header, above the first camera tile at y=76.
        if (id == R.id.btnClose) return new int[]{12, 0, 64, 64};
        // The settings icon shares the mode buttons' top/bottom edges and has a wide hit area.
        if (id == R.id.btnSettings) return new int[]{12, 560, 112, 64};
        if (id == R.id.modeWheels) return new int[]{140, 560, 188, 64};
        if (id == R.id.modeSides) return new int[]{336, 560, 188, 64};
        if (id == R.id.leftCameraFailure) return new int[]{12, 76, 254, 456};
        if (id == R.id.rightCameraFailure) return new int[]{274, 76, 254, 456};
        return new int[]{0, 0, 540, 640};
    }
}
