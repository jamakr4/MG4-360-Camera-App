package com.drivehub.kamera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.drivehub.kamera.R;
import com.drivehub.kamera.birdseye.BirdseyeCarColor;

/** A single row of accessible paint swatches, shared by Parking and the debug preview. */
public final class BirdseyeCarColorPicker extends LinearLayout {
    private final SharedPreferences prefs;
    private final LinearLayout row;
    private final TextView selectedLabel;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (p, key) -> {
        if (UiPrefs.KEY_PARKING_BIRDSEYE_CAR_COLOR.equals(key) || UiPrefs.KEY_ACCENT_COLOR.equals(key)) refresh();
    };

    public BirdseyeCarColorPicker(Context context) { this(context, null); }

    public BirdseyeCarColorPicker(Context context, AttributeSet attrs) {
        super(context, attrs);
        prefs = UiPrefs.getPrefs(context);
        setOrientation(VERTICAL);
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        for (BirdseyeCarColor color : BirdseyeCarColor.values()) {
            Swatch swatch = new Swatch(context, color);
            LayoutParams p = new LayoutParams(dp(48), dp(48));
            p.setMarginEnd(dp(6));
            row.addView(swatch, p);
        }
        scroll.addView(row);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));
        selectedLabel = new TextView(context);
        selectedLabel.setTextAppearance(R.style.DrivehubSettingsCaption);
        selectedLabel.setGravity(Gravity.START);
        selectedLabel.setPadding(dp(6), dp(4), dp(6), 0);
        selectedLabel.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(selectedLabel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        refresh();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        prefs.registerOnSharedPreferenceChangeListener(listener);
        refresh();
    }

    @Override protected void onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
        super.onDetachedFromWindow();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void refresh() {
        BirdseyeCarColor selected = UiPrefs.getBirdseyeCarColor(prefs);
        for (int i = 0; i < row.getChildCount(); i++) {
            Swatch swatch = (Swatch) row.getChildAt(i);
            swatch.setSelected(swatch.color == selected);
            swatch.invalidate();
        }
        selectedLabel.setText(selected.label());
    }

    private final class Swatch extends View {
        final BirdseyeCarColor color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path check = new Path();

        Swatch(Context context, BirdseyeCarColor color) {
            super(context);
            this.color = color;
            setTag(color.id);
            setContentDescription(color.label());
            setFocusable(true);
            setClickable(true);
            setOnClickListener(v -> UiPrefs.setBirdseyeCarColor(prefs, color));
        }

        @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float x = getWidth() / 2f, y = getHeight() / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color.swatch);
            canvas.drawCircle(x, y, dp(14), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0xFF9DA7B1);
            canvas.drawCircle(x, y, dp(14), paint);
            if (isSelected() || isFocused() || isPressed()) {
                paint.setStrokeWidth(dp(2));
                paint.setColor(UiPrefs.getAccentColorInt(prefs));
                canvas.drawCircle(x, y, dp(19), paint);
            }
            if (isSelected()) {
                paint.setColor(UiPrefs.isLightColor(color.swatch) ? Color.BLACK : Color.WHITE);
                paint.setStrokeWidth(dp(2));
                check.reset();
                check.moveTo(x-dp(6), y); check.lineTo(x-dp(1), y+dp(5)); check.lineTo(x+dp(7), y-dp(5));
                canvas.drawPath(check, paint);
            }
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.RadioButton");
            info.setCheckable(true);
            info.setChecked(isSelected());
        }
    }
}
