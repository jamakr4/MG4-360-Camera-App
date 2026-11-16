package com.drivehub.kamera.settings;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Switch;
import com.drivehub.kamera.R;

/** Parking-only controls; changes are observed by the existing SignalService. */
final class ParkingAutomationSettings {
    private ParkingAutomationSettings() {}

    static void bind(Dialog dialog, SharedPreferences prefs) {
        Switch close = dialog.findViewById(R.id.switchParkingAutoClose);
        Switch drive = dialog.findViewById(R.id.switchParkingAutoOpenDrive);
        Switch park = dialog.findViewById(R.id.switchParkingAutoCloseOnPark);
        EditText speed = dialog.findViewById(R.id.etParkingAutoCloseSpeed);
        speed.setText(String.valueOf(UiPrefs.getParkingAutoCloseSpeedKmh(prefs)));
        close.setChecked(UiPrefs.isParkingAutoCloseEnabled(prefs));
        drive.setChecked(UiPrefs.isParkingAutoOpenDriveEnabled(prefs));
        park.setChecked(UiPrefs.isParkingAutoCloseOnParkEnabled(prefs));
        enabled(speed, close.isChecked());
        close.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(UiPrefs.KEY_PARKING_AUTO_CLOSE, checked).apply();
            enabled(speed, checked);
        });
        drive.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(UiPrefs.KEY_PARKING_AUTO_OPEN_DRIVE, checked).apply());
        park.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(UiPrefs.KEY_PARKING_AUTO_CLOSE_ON_PARK, checked).apply());
        speed.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(android.text.Editable text) {
                try {
                    int value = UiPrefs.clampParkingAutoCloseSpeedKmh(Integer.parseInt(text.toString().trim()));
                    prefs.edit().putInt(UiPrefs.KEY_PARKING_AUTO_CLOSE_SPEED, value).apply();
                } catch (NumberFormatException ignored) {
                    // Empty/incomplete edits retain the last valid saved value.
                }
            }
        });
        speed.setOnFocusChangeListener((view, focused) -> {
            if (!focused) speed.setText(String.valueOf(UiPrefs.getParkingAutoCloseSpeedKmh(prefs)));
        });
        speed.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            speed.clearFocus();
            return false;
        });
    }

    private static void enabled(EditText speed, boolean enabled) {
        speed.setEnabled(enabled);
        speed.setAlpha(enabled ? 1f : .45f);
    }
}
