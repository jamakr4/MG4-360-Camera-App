package com.drivehub.kamera.signal;

import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.settings.SimpleTextWatcher;
import com.drivehub.kamera.settings.UiPrefs;

import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;

import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;

public final class SignalCameraSettingsController {

    private final MainActivity activity;

    public SignalCameraSettingsController(MainActivity activity) {
        this.activity = activity;
    }

    public void bind(
            SharedPreferences prefs,
            Switch swOverlay,
            Switch swLaneChangesOnly,
            SeekBar seekLaneChangeMinSpeed,
            EditText etLaneChangeMinSpeedValue,
            Switch swDigitalRearview,
            Switch swRearviewTapToHide,
            EditText etRearviewTapToHideDurationSec,
            Switch swSignalTapToHide,
            EditText etSignalTapToHideDurationSec,
            Switch swRotateToDrivingDirection,
            SeekBar seekOverlayHideDelay,
            EditText etOverlayHideDelayValue,
            SeekBar seekOverlayMinShow,
            EditText etOverlayMinShowValue
    ) {
        if (swOverlay != null) {
            swOverlay.setChecked(UiPrefs.isOverlayOnSignalEnabled(prefs));
            swOverlay.setOnCheckedChangeListener((btn, checked) -> {
                prefs.edit().putBoolean(UiPrefs.KEY_OVERLAY_ON_SIGNAL, checked).apply();
                updateLaneChangeControls(
                        swLaneChangesOnly,
                        seekLaneChangeMinSpeed,
                        etLaneChangeMinSpeedValue,
                        checked
                );
                SignalService.requestRecheck();
            });
        }

        bindLaneChangeMode(
                prefs,
                swLaneChangesOnly,
                seekLaneChangeMinSpeed,
                etLaneChangeMinSpeedValue
        );

        if (swDigitalRearview != null) {
            swDigitalRearview.setChecked(UiPrefs.isDigitalRearviewEnabled(prefs));
            swDigitalRearview.setOnCheckedChangeListener((btn, checked) -> {
                UiPrefs.setDigitalRearviewEnabled(prefs, checked);
                SignalService.requestRecheck();
            });
        }

        if (swRearviewTapToHide != null) {
            swRearviewTapToHide.setChecked(UiPrefs.isDigitalRearviewTapToHideEnabled(prefs));
            swRearviewTapToHide.setOnCheckedChangeListener((btn, checked) ->
                    prefs.edit().putBoolean(UiPrefs.KEY_DIGITAL_REARVIEW_TAP_TO_HIDE_ENABLED, checked).apply());
        }

        if (etRearviewTapToHideDurationSec != null) {
            int saved = UiPrefs.getDigitalRearviewTapToHideDurationSec(prefs);
            etRearviewTapToHideDurationSec.setText(String.valueOf(saved));
            etRearviewTapToHideDurationSec.setSelection(etRearviewTapToHideDurationSec.getText().length());
            etRearviewTapToHideDurationSec.addTextChangedListener(new SimpleTextWatcher() {
                @Override
                public void afterTextChanged(android.text.Editable s) {
                    if (s == null) return;
                    String text = s.toString().trim();
                    if (text.isEmpty()) return;
                    try {
                        int v = UiPrefs.clampRearviewTapToHideDurationSec(Integer.parseInt(text));
                        prefs.edit().putInt(UiPrefs.KEY_DIGITAL_REARVIEW_TAP_TO_HIDE_DURATION_SEC, v).apply();
                    } catch (NumberFormatException ignored) {
                    }
                }
            });
            etRearviewTapToHideDurationSec.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) return;
                int clamped = UiPrefs.getDigitalRearviewTapToHideDurationSec(prefs);
                etRearviewTapToHideDurationSec.setText(String.valueOf(clamped));
                etRearviewTapToHideDurationSec.setSelection(etRearviewTapToHideDurationSec.getText().length());
            });
        }

        if (swSignalTapToHide != null) {
            swSignalTapToHide.setChecked(UiPrefs.isSignalOverlayTapToHideEnabled(prefs));
            swSignalTapToHide.setOnCheckedChangeListener((btn, checked) ->
                    prefs.edit().putBoolean(UiPrefs.KEY_SIGNAL_OVERLAY_TAP_TO_HIDE_ENABLED, checked).apply());
        }

        if (etSignalTapToHideDurationSec != null) {
            int saved = UiPrefs.getSignalOverlayTapToHideDurationSec(prefs);
            etSignalTapToHideDurationSec.setText(String.valueOf(saved));
            etSignalTapToHideDurationSec.setSelection(etSignalTapToHideDurationSec.getText().length());
            etSignalTapToHideDurationSec.addTextChangedListener(new SimpleTextWatcher() {
                @Override
                public void afterTextChanged(android.text.Editable s) {
                    if (s == null) return;
                    String text = s.toString().trim();
                    if (text.isEmpty()) return;
                    try {
                        int v = UiPrefs.clampSignalTapToHideDurationSec(Integer.parseInt(text));
                        prefs.edit().putInt(UiPrefs.KEY_SIGNAL_OVERLAY_TAP_TO_HIDE_DURATION_SEC, v).apply();
                    } catch (NumberFormatException ignored) {
                    }
                }
            });
            etSignalTapToHideDurationSec.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) return;
                int clamped = UiPrefs.getSignalOverlayTapToHideDurationSec(prefs);
                etSignalTapToHideDurationSec.setText(String.valueOf(clamped));
                etSignalTapToHideDurationSec.setSelection(etSignalTapToHideDurationSec.getText().length());
            });
        }

        if (swRotateToDrivingDirection != null) {
            swRotateToDrivingDirection.setChecked(
                    UiPrefs.isOverlayRotationToDrivingDirectionEnabled(prefs)
            );
            swRotateToDrivingDirection.setOnCheckedChangeListener((btn, checked) ->
                    prefs.edit()
                            .putBoolean(UiPrefs.KEY_OVERLAY_ROTATE_TO_DRIVING_DIRECTION, checked)
                            .apply()
            );
        }

        bindMsSlider(
                prefs,
                seekOverlayHideDelay,
                etOverlayHideDelayValue,
                UiPrefs.KEY_OVERLAY_HIDE_DELAY_MS,
                UiPrefs.MAX_OVERLAY_HIDE_DELAY_MS,
                UiPrefs.OVERLAY_HIDE_DELAY_STEP_MS,
                UiPrefs::getOverlayHideDelayMs,
                UiPrefs::clampOverlayHideDelayMs
        );
        bindMsSlider(
                prefs,
                seekOverlayMinShow,
                etOverlayMinShowValue,
                UiPrefs.KEY_OVERLAY_MIN_SHOW_MS,
                UiPrefs.MAX_OVERLAY_MIN_SHOW_MS,
                UiPrefs.OVERLAY_MIN_SHOW_STEP_MS,
                UiPrefs::getOverlayMinShowMs,
                UiPrefs::clampOverlayMinShowMs
        );
    }

    private void bindLaneChangeMode(
            SharedPreferences prefs,
            Switch toggle,
            SeekBar seek,
            EditText edit
    ) {
        final boolean[] syncing = {false};

        if (seek != null) {
            seek.setMax(UiPrefs.MAX_LANE_CHANGE_SPEED_KMH);
            seek.setProgress(UiPrefs.getLaneChangeMinSpeedKmh(prefs));
        }
        if (edit != null) {
            int saved = UiPrefs.getLaneChangeMinSpeedKmh(prefs);
            edit.setText(String.valueOf(saved));
            edit.setSelection(edit.getText().length());
            edit.addTextChangedListener(new SimpleTextWatcher() {
                @Override
                public void afterTextChanged(android.text.Editable s) {
                    if (syncing[0] || s == null) return;
                    String text = s.toString().trim();
                    if (text.isEmpty()) return;
                    try {
                        int speedKmh = UiPrefs.clampLaneChangeSpeedKmh(Integer.parseInt(text));
                        prefs.edit().putInt(UiPrefs.KEY_LANE_CHANGE_MIN_SPEED_KMH, speedKmh).apply();
                        if (seek != null && seek.getProgress() != speedKmh) {
                            seek.setProgress(speedKmh);
                        }
                        SignalService.requestRecheck();
                    } catch (NumberFormatException ignored) {
                    }
                }
            });
            edit.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) return;
                int speedKmh = UiPrefs.getLaneChangeMinSpeedKmh(prefs);
                syncing[0] = true;
                edit.setText(String.valueOf(speedKmh));
                edit.setSelection(edit.getText().length());
                syncing[0] = false;
            });
        }
        if (seek != null) {
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    int speedKmh = UiPrefs.clampLaneChangeSpeedKmh(progress);
                    prefs.edit().putInt(UiPrefs.KEY_LANE_CHANGE_MIN_SPEED_KMH, speedKmh).apply();
                    if (edit != null) {
                        syncing[0] = true;
                        edit.setText(String.valueOf(speedKmh));
                        edit.setSelection(edit.getText().length());
                        syncing[0] = false;
                    }
                    SignalService.requestRecheck();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }
        if (toggle != null) {
            toggle.setChecked(UiPrefs.isLaneChangesOnlyEnabled(prefs));
            toggle.setOnCheckedChangeListener((button, checked) -> {
                prefs.edit().putBoolean(UiPrefs.KEY_LANE_CHANGES_ONLY, checked).apply();
                updateLaneChangeControls(
                        toggle,
                        seek,
                        edit,
                        UiPrefs.isOverlayOnSignalEnabled(prefs)
                );
                SignalService.requestRecheck();
            });
        }
        updateLaneChangeControls(
                toggle,
                seek,
                edit,
                UiPrefs.isOverlayOnSignalEnabled(prefs)
        );
    }

    private static void updateLaneChangeControls(
            Switch toggle,
            SeekBar seek,
            EditText edit,
            boolean overlayEnabled
    ) {
        if (toggle != null) {
            toggle.setEnabled(overlayEnabled);
            toggle.setAlpha(overlayEnabled ? 1f : 0.45f);
        }
        boolean thresholdEnabled = overlayEnabled && toggle != null && toggle.isChecked();
        if (seek != null) {
            seek.setEnabled(thresholdEnabled);
            seek.setAlpha(thresholdEnabled ? 1f : 0.45f);
        }
        if (edit != null) {
            edit.setEnabled(thresholdEnabled);
            edit.setAlpha(thresholdEnabled ? 1f : 0.45f);
        }
    }

    private void bindMsSlider(
            SharedPreferences prefs,
            SeekBar seek,
            EditText edit,
            String key,
            int maxMs,
            int stepMs,
            ToIntFunction<SharedPreferences> getter,
            IntUnaryOperator clamp
    ) {
        if (seek == null) return;

        final boolean[] syncing = {false};
        int savedValueMs = getter.applyAsInt(prefs);
        seek.setMax(maxMs / stepMs);
        seek.setProgress(savedValueMs / stepMs);

        if (edit != null) {
            edit.setText(String.valueOf(savedValueMs));
            edit.setSelection(edit.getText().length());
            edit.addTextChangedListener(new SimpleTextWatcher() {
                @Override
                public void afterTextChanged(android.text.Editable s) {
                    if (syncing[0] || s == null) return;
                    String text = s.toString().trim();
                    if (text.isEmpty()) return;
                    try {
                        int valueMs = clamp.applyAsInt(Integer.parseInt(text));
                        prefs.edit().putInt(key, valueMs).apply();
                        int progress = valueMs / stepMs;
                        if (seek.getProgress() != progress) {
                            seek.setProgress(progress);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            });
            edit.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) return;
                String text = edit.getText() == null ? "" : edit.getText().toString().trim();
                int valueMs;
                try {
                    valueMs = text.isEmpty() ? getter.applyAsInt(prefs) : Integer.parseInt(text);
                } catch (NumberFormatException ignored) {
                    valueMs = getter.applyAsInt(prefs);
                }
                valueMs = clamp.applyAsInt(valueMs);
                prefs.edit().putInt(key, valueMs).apply();
                syncing[0] = true;
                edit.setText(String.valueOf(valueMs));
                edit.setSelection(edit.getText().length());
                syncing[0] = false;
                int progress = valueMs / stepMs;
                if (seek.getProgress() != progress) {
                    seek.setProgress(progress);
                }
            });
        }

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int valueMs = clamp.applyAsInt(progress * stepMs);
                prefs.edit().putInt(key, valueMs).apply();
                if (edit == null) return;
                String currentValue = edit.getText() == null ? "" : edit.getText().toString();
                String nextValue = String.valueOf(valueMs);
                if (!nextValue.equals(currentValue)) {
                    syncing[0] = true;
                    edit.setText(nextValue);
                    edit.setSelection(edit.getText().length());
                    syncing[0] = false;
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }
}
