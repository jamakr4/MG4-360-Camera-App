package com.drivehub.kamera.maneuver;

import com.drivehub.kamera.settings.SimpleTextWatcher;
import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.signal.SignalService;

import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.Switch;

public final class ManeuverSettingsController {

    public void bind(
            SharedPreferences prefs,
            Switch swManeuverMode,
            Switch swThreshold,
            EditText etThresholdKmh,
            Switch swAllowOemAvm
    ) {
        if (swManeuverMode != null) {
            swManeuverMode.setChecked(UiPrefs.isManeuverModeEnabled(prefs));
            swManeuverMode.setOnCheckedChangeListener((btn, checked) -> {
                UiPrefs.setManeuverModeEnabled(prefs, checked);
                ManeuverController.requestRefresh();
                SignalService.requestRecheck();
            });
        }

        if (swThreshold != null) {
            swThreshold.setChecked(UiPrefs.isManeuverSpeedThresholdEnabled(prefs));
            swThreshold.setOnCheckedChangeListener((btn, checked) -> {
                UiPrefs.setManeuverSpeedThresholdEnabled(prefs, checked);
                ManeuverController.requestRefresh();
                SignalService.requestRecheck();
            });
        }

        if (etThresholdKmh != null) {
            int saved = UiPrefs.getManeuverSpeedThresholdKmh(prefs);
            etThresholdKmh.setText(String.valueOf(saved));
            etThresholdKmh.setSelection(etThresholdKmh.getText().length());
            etThresholdKmh.addTextChangedListener(new SimpleTextWatcher() {
                @Override
                public void afterTextChanged(android.text.Editable s) {
                    if (s == null) return;
                    String text = s.toString().trim();
                    if (text.isEmpty()) return;
                    try {
                        int value = UiPrefs.clampManeuverSpeedThresholdKmh(Integer.parseInt(text));
                        prefs.edit().putInt(UiPrefs.KEY_MANEUVER_SPEED_THRESHOLD_KMH, value).apply();
                        ManeuverController.requestRefresh();
                        SignalService.requestRecheck();
                    } catch (NumberFormatException ignored) {
                    }
                }
            });
            etThresholdKmh.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) return;
                int clamped = UiPrefs.getManeuverSpeedThresholdKmh(prefs);
                etThresholdKmh.setText(String.valueOf(clamped));
                etThresholdKmh.setSelection(etThresholdKmh.getText().length());
            });
        }

        if (swAllowOemAvm != null) {
            swAllowOemAvm.setChecked(UiPrefs.isManeuverOemAvmAllowed(prefs));
            swAllowOemAvm.setOnCheckedChangeListener((btn, checked) -> {
                UiPrefs.setManeuverOemAvmAllowed(prefs, checked);
                ManeuverController.requestRefresh();
                SignalService.requestRecheck();
            });
        }
    }
}
