package com.drivehub.kamera;

import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;

import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;

final class SignalCameraSettingsController {

    private final MainActivity activity;

    SignalCameraSettingsController(MainActivity activity) {
        this.activity = activity;
    }

    void bind(
            SharedPreferences prefs,
            Switch swOverlay,
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
                if (!checked) {
                    OverlayService.hideOverlay(activity);
                }
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
                        prefs.edit().putLong(key, valueMs).apply();
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
                prefs.edit().putLong(key, valueMs).apply();
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
                prefs.edit().putLong(key, valueMs).apply();
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
