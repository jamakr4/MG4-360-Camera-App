package com.drivehub.kamera;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

final class SettingsAppearanceController {

    private final AppCompatActivity activity;
    private boolean isNormalizingAccentColor;
    private SharedPreferences prefs;
    private Switch swOverlay;
    private Switch swDashcamEnabled;
    private Switch swDashcamShowSpeed;
    private Switch swSafetyWarning;
    private Switch swAllowBetaUpdates;
    private ImageButton dialogClose;
    private SeekBar seekOverlayHideDelay;
    private SeekBar seekOverlayMinShow;
    private SeekBar seekCorner;
    private EditText etCorner;
    private View accentRow;
    private View accentPreview;
    private EditText etAccentColor;
    private TextView tabUpdate;
    private TextView tabSettings;
    private TextView tabSignalCamera;
    private TextView tabDashcam;
    private TextView tabOptik;
    private TextView tabCredits;
    private int activeTab;

    SettingsAppearanceController(AppCompatActivity activity) {
        this.activity = activity;
    }

    void applyMainUiIconColors() {
        ImageButton btnSettings = activity.findViewById(R.id.btnSettings);
        if (btnSettings != null) {
            btnSettings.setColorFilter(Color.WHITE);
        }
    }

    void bindSettingsAppearance(
            SharedPreferences prefs,
            Switch swOverlay,
            Switch swDashcamEnabled,
            Switch swDashcamShowSpeed,
            Switch swSafetyWarning,
            Switch swAllowBetaUpdates,
            ImageButton dialogClose,
            SeekBar seekOverlayHideDelay,
            SeekBar seekOverlayMinShow,
            SeekBar seekCorner,
            EditText etCorner,
            View accentRow,
            View accentPreview,
            EditText etAccentColor,
            TextView tabUpdate,
            TextView tabSettings,
            TextView tabSignalCamera,
            TextView tabDashcam,
            TextView tabOptik,
            TextView tabCredits
    ) {
        this.prefs = prefs;
        this.swOverlay = swOverlay;
        this.swDashcamEnabled = swDashcamEnabled;
        this.swDashcamShowSpeed = swDashcamShowSpeed;
        this.swSafetyWarning = swSafetyWarning;
        this.swAllowBetaUpdates = swAllowBetaUpdates;
        this.dialogClose = dialogClose;
        this.seekOverlayHideDelay = seekOverlayHideDelay;
        this.seekOverlayMinShow = seekOverlayMinShow;
        this.seekCorner = seekCorner;
        this.etCorner = etCorner;
        this.accentRow = accentRow;
        this.accentPreview = accentPreview;
        this.etAccentColor = etAccentColor;
        this.tabUpdate = tabUpdate;
        this.tabSettings = tabSettings;
        this.tabSignalCamera = tabSignalCamera;
        this.tabDashcam = tabDashcam;
        this.tabOptik = tabOptik;
        this.tabCredits = tabCredits;
        this.activeTab = 1;

        int savedRadius = UiPrefs.getTileCornerRadiusSetting(prefs);
        int accentColor = UiPrefs.getAccentColorInt(prefs);

        seekCorner.setMax(UiPrefs.MAX_TILE_CORNER_RADIUS);
        seekCorner.setProgress(savedRadius);
        etCorner.setText(String.valueOf(savedRadius));
        etAccentColor.setText(UiPrefs.getAccentColorSetting(prefs));
        syncAccentRowToSliderInset();
        // The preview square doubles as the entry point for the visual picker dialog.
        accentPreview.setOnClickListener(v -> showAccentColorPicker());

        applyAccentColorToSettingsDialog(accentColor);

        seekCorner.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefs.edit().putInt(UiPrefs.KEY_TILE_CORNER_RADIUS, progress).apply();
                if (fromUser) {
                    etCorner.setText(String.valueOf(progress));
                    etCorner.setSelection(etCorner.getText().length());
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        etCorner.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
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
                } catch (NumberFormatException ignored) {}
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

        etAccentColor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
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
        int activeColor = UiPrefs.getAccentColorInt(UiPrefs.getPrefs(activity));
        tab.setTextColor(active ? activeColor : 0xFF777777);
        tab.setTextSize(20f);
        tab.setTypeface(tab.getTypeface(), Typeface.BOLD);
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

        // Seed both picker surfaces from the currently saved accent so reopening feels stateful.
        hueView.setHue(hsv[0]);
        saturationValueView.setColor(hsv[0], hsv[1], hsv[2]);
        updateColorPickerUi(tvHex, preview, btnApply, applyButtonBackground, selectedColor[0]);

        hueView.setOnHueChangedListener(hue -> {
            hsv[0] = hue;
            // When hue changes, keep the current saturation/value and rebuild the final color.
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
        // Very bright accents need dark text and a border so the Apply button stays visible.
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
        // Reuse the same stored string format as the manual hex field so both inputs stay in sync.
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
        if (seekCorner != null) {
            seekCorner.setProgressTintList(ColorStateList.valueOf(accentColor));
            seekCorner.setThumbTintList(ColorStateList.valueOf(accentColor));
        }
        if (seekOverlayHideDelay != null) {
            seekOverlayHideDelay.setProgressTintList(ColorStateList.valueOf(accentColor));
            seekOverlayHideDelay.setThumbTintList(ColorStateList.valueOf(accentColor));
        }
        if (seekOverlayMinShow != null) {
            seekOverlayMinShow.setProgressTintList(ColorStateList.valueOf(accentColor));
            seekOverlayMinShow.setThumbTintList(ColorStateList.valueOf(accentColor));
        }
        if (swOverlay != null) {
            swOverlay.setTrackTintList(buildToggleTrackTint(accentColor));
        }
        if (swDashcamEnabled != null) {
            swDashcamEnabled.setTrackTintList(buildToggleTrackTint(accentColor));
        }
        if (swDashcamShowSpeed != null) {
            swDashcamShowSpeed.setTrackTintList(buildToggleTrackTint(accentColor));
        }
        if (swSafetyWarning != null) {
            swSafetyWarning.setTrackTintList(buildToggleTrackTint(accentColor));
        }
        if (swAllowBetaUpdates != null) {
            swAllowBetaUpdates.setTrackTintList(buildToggleTrackTint(accentColor));
        }
        if (accentPreview != null) {
            Drawable background = accentPreview.getBackground();
            if (background instanceof GradientDrawable) {
                ((GradientDrawable) background.mutate()).setColor(accentColor);
            }
        }
        if (tabUpdate != null) tabUpdate.setTextColor(activeTab == 0 ? accentColor : 0xFF777777);
        if (tabSettings != null) tabSettings.setTextColor(activeTab == 1 ? accentColor : 0xFF777777);
        if (tabSignalCamera != null) tabSignalCamera.setTextColor(activeTab == 2 ? accentColor : 0xFF777777);
        if (tabDashcam != null) tabDashcam.setTextColor(activeTab == 3 ? accentColor : 0xFF777777);
        if (tabOptik != null) tabOptik.setTextColor(activeTab == 4 ? accentColor : 0xFF777777);
        if (tabCredits != null) tabCredits.setTextColor(activeTab == 5 ? accentColor : 0xFF777777);
    }

    private ColorStateList buildToggleTrackTint(int accentColor) {
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{
                        accentColor,
                        0xFF383838
                }
        );
    }

    private void syncAccentRowToSliderInset() {
        if (accentRow == null || seekCorner == null) return;
        accentRow.post(() -> accentRow.setPaddingRelative(
                seekCorner.getPaddingLeft(),
                accentRow.getPaddingTop(),
                accentRow.getPaddingEnd(),
                accentRow.getPaddingBottom()
        ));
    }

    private float dp(float value) {
        return value * activity.getResources().getDisplayMetrics().density;
    }

}
