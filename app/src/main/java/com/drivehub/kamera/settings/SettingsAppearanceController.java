package com.drivehub.kamera.settings;

import com.drivehub.kamera.R;

import com.drivehub.kamera.ui.ColorHueStripView;
import com.drivehub.kamera.ui.ColorSaturationValueView;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public final class SettingsAppearanceController {

    private final AppCompatActivity activity;
    private boolean isNormalizingAccentColor;
    private SharedPreferences prefs;
    private ImageButton dialogClose;
    private SeekBar seekCorner;
    private View accentRow;
    private View accentPreview;
    private EditText etAccentColor;
    private int activeTab;
    private SeekBar[] tintedSeekBars;
    private Switch[] tintedSwitches;
    private TextView[] tabs;

    public SettingsAppearanceController(AppCompatActivity activity) {
        this.activity = activity;
    }

    public void applyMainUiIconColors() {
        ImageButton btnSettings = activity.findViewById(R.id.btnSettings);
        if (btnSettings != null) {
            btnSettings.setColorFilter(Color.WHITE);
        }
        int accentColor = UiPrefs.getAccentColorInt(UiPrefs.getPrefs(activity));
        styleMainActionButton(activity.findViewById(R.id.btnTriggerEventSave), accentColor);
        styleMainActionButton(activity.findViewById(R.id.btnRecordTestClip), accentColor);
    }

    void bindSettingsAppearance(
            SharedPreferences prefs,
            Switch[] tintedSwitches,
            ImageButton dialogClose,
            SeekBar[] tintedSeekBars,
            SeekBar seekCorner,
            EditText etCorner,
            View accentRow,
            View accentPreview,
            EditText etAccentColor,
            TextView tabUpdate,
            TextView tabSettings,
            TextView tabSignalCamera,
            TextView tabDashcam,
            TextView tabCredits,
            TextView tabDev,
            TextView tabDevStatus
    ) {
        this.prefs = prefs;
        this.dialogClose = dialogClose;
        this.seekCorner = seekCorner;
        this.accentRow = accentRow;
        this.accentPreview = accentPreview;
        this.etAccentColor = etAccentColor;
        this.activeTab = 1;
        this.tintedSeekBars = tintedSeekBars;
        this.tintedSwitches = tintedSwitches;
        this.tabs = new TextView[]{
                tabUpdate,
                tabSettings,
                tabSignalCamera,
                tabDashcam,
                tabCredits,
                tabDev,
                tabDevStatus
        };

        int savedRadius = UiPrefs.getTileCornerRadiusSetting(prefs);
        int accentColor = UiPrefs.getAccentColorInt(prefs);

        seekCorner.setMax(UiPrefs.MAX_TILE_CORNER_RADIUS);
        seekCorner.setProgress(savedRadius);
        etCorner.setText(String.valueOf(savedRadius));
        etAccentColor.setText(UiPrefs.getAccentColorSetting(prefs));
        accentPreview.setOnClickListener(v -> showAccentColorPicker());

        applyAccentColorToSettingsDialog(accentColor);

        seekCorner.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                prefs.edit().putInt(UiPrefs.KEY_TILE_CORNER_RADIUS, progress).apply();
                etCorner.setText(String.valueOf(progress));
                etCorner.setSelection(etCorner.getText().length());
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        etCorner.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() == 0) return;
                try {
                    int value = Math.min(UiPrefs.MAX_TILE_CORNER_RADIUS, Math.max(0, Integer.parseInt(s.toString())));
                    String normalized = String.valueOf(value);
                    if (!normalized.contentEquals(s)) {
                        etCorner.setText(normalized);
                        etCorner.setSelection(etCorner.getText().length());
                        return;
                    }
                    prefs.edit().putInt(UiPrefs.KEY_TILE_CORNER_RADIUS, value).apply();
                    if (seekCorner.getProgress() != value) {
                        seekCorner.setProgress(value);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        });

        etAccentColor.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;
            String normalized = UiPrefs.normalizeAccentColor(etAccentColor.getText().toString());
            prefs.edit().putString(UiPrefs.KEY_ACCENT_COLOR, normalized).apply();
            isNormalizingAccentColor = true;
            etAccentColor.setText(normalized);
            etAccentColor.setSelection(etAccentColor.getText().length());
            isNormalizingAccentColor = false;
            int color = UiPrefs.getAccentColorInt(prefs);
            applyAccentColorToSettingsDialog(color);
            applyMainUiIconColors();
        });

        etAccentColor.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (isNormalizingAccentColor) return;
                Integer parsed = UiPrefs.tryParseAccentColorOrNull(s == null ? null : s.toString());
                if (parsed == null) return;
                String normalized = UiPrefs.normalizeAccentColor(s.toString());
                prefs.edit().putString(UiPrefs.KEY_ACCENT_COLOR, normalized).apply();
                applyAccentColorToSettingsDialog(parsed);
                applyMainUiIconColors();
            }
        });
    }

    void reapplyForActiveTab(int activeTab) {
        this.activeTab = activeTab;
        applyAccentColorToSettingsDialog(UiPrefs.getAccentColorInt(prefs));
    }

    void styleSettingsTab(TextView tab, boolean active) {
        int activeColor = UiPrefs.getAccentColorInt(prefs);
        int inactiveColor = ContextCompat.getColor(activity, R.color.settings_tab_inactive);
        tab.setTextColor(active ? activeColor : inactiveColor);
        tab.setTypeface(tab.getTypeface(), Typeface.BOLD);
        tab.setBackgroundResource(active ? R.drawable.bg_settings_tab_active : 0);
    }

    private void showAccentColorPicker() {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_color_picker);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        TextView tvHex = dialog.findViewById(R.id.tvColorPickerHex);
        View preview = dialog.findViewById(R.id.viewColorPickerPreview);
        ColorSaturationValueView saturationValueView = dialog.findViewById(R.id.viewColorSaturationValue);
        ColorHueStripView hueView = dialog.findViewById(R.id.viewColorHue);
        TextView btnCancel = dialog.findViewById(R.id.btnColorPickerCancel);
        TextView btnApply = dialog.findViewById(R.id.btnColorPickerApply);
        GradientDrawable applyButtonBackground = new GradientDrawable();
        applyButtonBackground.setCornerRadius(dp(12f));
        btnApply.setBackground(applyButtonBackground);

        float[] hsv = new float[3];
        Color.colorToHSV(UiPrefs.getAccentColorInt(prefs), hsv);
        final int[] selectedColor = new int[]{UiPrefs.getAccentColorInt(prefs)};

        hueView.setHue(hsv[0]);
        saturationValueView.setColor(hsv[0], hsv[1], hsv[2]);
        updateColorPickerUi(tvHex, preview, btnApply, applyButtonBackground, selectedColor[0]);

        hueView.setOnHueChangedListener(hue -> {
            hsv[0] = hue;
            saturationValueView.setColor(hue, hsv[1], hsv[2]);
            selectedColor[0] = Color.HSVToColor(hsv);
            updateColorPickerUi(tvHex, preview, btnApply, applyButtonBackground, selectedColor[0]);
        });
        saturationValueView.setOnColorPositionChangedListener((saturation, value) -> {
            hsv[1] = saturation;
            hsv[2] = value;
            selectedColor[0] = Color.HSVToColor(hsv);
            updateColorPickerUi(tvHex, preview, btnApply, applyButtonBackground, selectedColor[0]);
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnApply.setOnClickListener(v -> {
            applySelectedAccentColor(selectedColor[0]);
            dialog.dismiss();
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            float density = activity.getResources().getDisplayMetrics().density;
            dialog.getWindow().setLayout((int) (430 * density), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void updateColorPickerUi(
            TextView tvHex,
            View preview,
            TextView btnApply,
            GradientDrawable applyButtonBackground,
            int color
    ) {
        String hex = String.format("#%06X", 0xFFFFFF & color);
        tvHex.setText(hex);
        Drawable previewBackground = preview.getBackground();
        if (previewBackground instanceof GradientDrawable) {
            ((GradientDrawable) previewBackground.mutate()).setColor(color);
        }
        applyButtonBackground.setColor(color);
        if (UiPrefs.isLightColor(color)) {
            applyButtonBackground.setStroke((int) dp(1f), 0x33000000);
            btnApply.setTextColor(0xFF111111);
        } else {
            applyButtonBackground.setStroke(0, Color.TRANSPARENT);
            btnApply.setTextColor(Color.WHITE);
        }
    }

    private void applySelectedAccentColor(int color) {
        String normalized = String.format("#%06X", 0xFFFFFF & color);
        prefs.edit().putString(UiPrefs.KEY_ACCENT_COLOR, normalized).apply();
        isNormalizingAccentColor = true;
        etAccentColor.setText(normalized);
        etAccentColor.setSelection(etAccentColor.getText().length());
        isNormalizingAccentColor = false;
        applyAccentColorToSettingsDialog(color);
        applyMainUiIconColors();
    }

    private void applyAccentColorToSettingsDialog(int accentColor) {
        if (dialogClose != null) {
            dialogClose.setColorFilter(Color.WHITE);
        }
        ColorStateList accentTint = ColorStateList.valueOf(accentColor);
        if (tintedSeekBars != null) {
            for (SeekBar bar : tintedSeekBars) {
                if (bar == null) continue;
                bar.setProgressTintList(accentTint);
                bar.setThumbTintList(accentTint);
            }
        }
        ColorStateList toggleTint = buildToggleTrackTint(accentColor);
        if (tintedSwitches != null) {
            for (Switch sw : tintedSwitches) {
                if (sw != null) {
                    sw.setTrackTintList(toggleTint);
                }
            }
        }
        if (accentPreview != null) {
            Drawable background = accentPreview.getBackground();
            if (background instanceof GradientDrawable) {
                ((GradientDrawable) background.mutate()).setColor(accentColor);
            }
        }
        int inactiveColor = ContextCompat.getColor(activity, R.color.settings_tab_inactive);
        if (tabs != null) {
            for (int i = 0; i < tabs.length; i++) {
                if (tabs[i] != null) {
                    tabs[i].setTextColor(i == activeTab ? accentColor : inactiveColor);
                }
            }
        }
    }

    private ColorStateList buildToggleTrackTint(int accentColor) {
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{
                        accentColor,
                        ContextCompat.getColor(activity, R.color.settings_slider_track_bg)
                }
        );
    }

    private void styleMainActionButton(Button button, int accentColor) {
        if (button == null) {
            return;
        }
        int pressedColor = blendWithBlack(accentColor, 0.16f);
        int textColor = UiPrefs.isLightColor(accentColor) ? 0xFF111111 : Color.WHITE;
        ColorStateList tint = new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{android.R.attr.state_pressed},
                        new int[]{}
                },
                new int[]{
                        blendWithBlack(accentColor, 0.35f),
                        pressedColor,
                        accentColor
                }
        );
        ColorStateList textTint = new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{}
                },
                new int[]{
                        textColor,
                        textColor
                }
        );
        button.setBackgroundTintList(tint);
        button.setTextColor(textTint);
    }

    private int blendWithBlack(int color, float amount) {
        amount = Math.max(0f, Math.min(1f, amount));
        int r = Math.round(Color.red(color) * (1f - amount));
        int g = Math.round(Color.green(color) * (1f - amount));
        int b = Math.round(Color.blue(color) * (1f - amount));
        return Color.rgb(r, g, b);
    }

    private float dp(float value) {
        return value * activity.getResources().getDisplayMetrics().density;
    }
}
