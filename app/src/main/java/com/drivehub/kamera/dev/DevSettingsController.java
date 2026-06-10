package com.drivehub.kamera.dev;

import com.drivehub.kamera.R;

import com.drivehub.kamera.dashcam.DashcamSettingsController;
import com.drivehub.kamera.settings.SimpleTextWatcher;
import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.signal.SignalService;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;

public final class DevSettingsController {

    public DevSettingsController() {
    }

    public void bind(
            SharedPreferences prefs,
            SeekBar seekOverlayTopInsetPx,
            EditText etOverlayTopInsetPx,
            EditText etForegroundModePollMs,
            EditText etDefaultPollMs,
            EditText etSignalOffPollMs,
            EditText etDashcamRetentionClipCount,
            EditText etDashcamMaxEventDirs,
            EditText etDashcamRecordsPath,
            TextView tvDashcamRecordsPath,
            Button btnBrowseFolder,
            Button btnResetDefaults
    ) {
        bindIntSlider(
                prefs,
                seekOverlayTopInsetPx,
                etOverlayTopInsetPx,
                UiPrefs.KEY_DEV_OVERLAY_TOP_INSET_PX,
                UiPrefs.MAX_DEV_OVERLAY_TOP_INSET_PX,
                1,
                UiPrefs::getDevOverlayTopInsetPx,
                UiPrefs::clampDevOverlayTopInsetPx
        );
        bindPollingField(
                prefs,
                etForegroundModePollMs,
                UiPrefs.KEY_DEV_FOREGROUND_MODE_POLL_MS,
                UiPrefs.getDevForegroundModePollMs(prefs)
        );
        bindPollingField(
                prefs,
                etDefaultPollMs,
                UiPrefs.KEY_DEV_DEFAULT_POLL_MS,
                UiPrefs.getDevDefaultPollMs(prefs)
        );
        bindPollingField(
                prefs,
                etSignalOffPollMs,
                UiPrefs.KEY_DEV_SIGNAL_OFF_POLL_MS,
                UiPrefs.getDevSignalOffPollMs(prefs)
        );
        bindCapacityField(
                prefs,
                etDashcamRetentionClipCount,
                DashcamSettingsController.getRetentionClipCount(prefs),
                DashcamSettingsController::clampRetentionClipCount,
                DashcamSettingsController::setRetentionClipCount
        );
        bindCapacityField(
                prefs,
                etDashcamMaxEventDirs,
                DashcamSettingsController.getMaxRetainedEventDirs(prefs),
                DashcamSettingsController::clampMaxRetainedEventDirs,
                DashcamSettingsController::setMaxRetainedEventDirs
        );
        bindDashcamStorageOverride(prefs, etDashcamRecordsPath, tvDashcamRecordsPath, btnBrowseFolder);
        bindResetDefaultsButton(
                prefs,
                seekOverlayTopInsetPx,
                etOverlayTopInsetPx,
                etForegroundModePollMs,
                etDefaultPollMs,
                etSignalOffPollMs,
                etDashcamRetentionClipCount,
                etDashcamMaxEventDirs,
                btnResetDefaults);
    }

    private interface CapacityWriter {
        void write(SharedPreferences prefs, int value);
    }

    private void bindCapacityField(
            SharedPreferences prefs,
            EditText editText,
            int initialValue,
            IntUnaryOperator clamp,
            CapacityWriter writer
    ) {
        if (editText == null) return;
        setIntFieldValue(editText, initialValue);
        editText.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (s == null) return;
                String text = s.toString().trim();
                if (text.isEmpty()) return;
                try {
                    int value = clamp.applyAsInt(Integer.parseInt(text));
                    writer.write(prefs, value);
                } catch (NumberFormatException ignored) {
                }
            }
        });
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;
            String text = editText.getText() == null ? "" : editText.getText().toString().trim();
            int value;
            try {
                value = text.isEmpty() ? initialValue : clamp.applyAsInt(Integer.parseInt(text));
            } catch (NumberFormatException ignored) {
                value = initialValue;
            }
            writer.write(prefs, value);
            String normalized = String.valueOf(value);
            if (!normalized.contentEquals(editText.getText())) {
                editText.setText(normalized);
                editText.setSelection(editText.getText().length());
            }
        });
    }

    private void bindDashcamStorageOverride(
            SharedPreferences prefs,
            EditText etDashcamRecordsPath,
            TextView tvDashcamRecordsPath,
            Button btnBrowseFolder
    ) {
        if (etDashcamRecordsPath != null) {
            String configuredPath = DashcamSettingsController.getConfiguredRecordsPath(prefs);
            etDashcamRecordsPath.setText(configuredPath);
            etDashcamRecordsPath.setSelection(etDashcamRecordsPath.getText().length());
            etDashcamRecordsPath.addTextChangedListener(new SimpleTextWatcher() {
                @Override
                public void afterTextChanged(Editable s) {
                    DashcamSettingsController.setConfiguredRecordsPath(prefs, s == null ? "" : s.toString());
                    refreshDashcamStoragePath(tvDashcamRecordsPath, etDashcamRecordsPath.getContext());
                }
            });
            etDashcamRecordsPath.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) return;
                String normalized = DashcamSettingsController.getConfiguredRecordsPath(prefs);
                if (!normalized.contentEquals(etDashcamRecordsPath.getText())) {
                    etDashcamRecordsPath.setText(normalized);
                    etDashcamRecordsPath.setSelection(etDashcamRecordsPath.getText().length());
                }
                refreshDashcamStoragePath(tvDashcamRecordsPath, v.getContext());
            });
        }
        refreshDashcamStoragePath(tvDashcamRecordsPath, btnBrowseFolder != null
                ? btnBrowseFolder.getContext()
                : etDashcamRecordsPath != null ? etDashcamRecordsPath.getContext() : null);
        if (btnBrowseFolder != null) {
            btnBrowseFolder.setOnClickListener(v -> DashcamFolderPicker.show(
                    v.getContext(),
                    etDashcamRecordsPath == null ? "" : editableToString(etDashcamRecordsPath),
                    new DashcamFolderPicker.Listener() {
                        @Override
                        public void onFolderSelected(String absolutePath) {
                            applyDashcamStoragePath(prefs, etDashcamRecordsPath, tvDashcamRecordsPath, v.getContext(), absolutePath);
                        }

                        @Override
                        public void onUseDefault() {
                            applyDashcamStoragePath(prefs, etDashcamRecordsPath, tvDashcamRecordsPath, v.getContext(), "");
                        }
                    }));
        }
    }

    private void bindResetDefaultsButton(
            SharedPreferences prefs,
            SeekBar seekOverlayTopInsetPx,
            EditText etOverlayTopInsetPx,
            EditText etForegroundModePollMs,
            EditText etDefaultPollMs,
            EditText etSignalOffPollMs,
            EditText etDashcamRetentionClipCount,
            EditText etDashcamMaxEventDirs,
            Button btnResetDefaults
    ) {
        if (btnResetDefaults == null) return;
        btnResetDefaults.setOnClickListener(v -> {
            prefs.edit()
                    .putInt(UiPrefs.KEY_DEV_OVERLAY_TOP_INSET_PX, UiPrefs.DEFAULT_DEV_OVERLAY_TOP_INSET_PX)
                    .putInt(UiPrefs.KEY_DEV_FOREGROUND_MODE_POLL_MS, UiPrefs.DEFAULT_DEV_FOREGROUND_MODE_POLL_MS)
                    .putInt(UiPrefs.KEY_DEV_DEFAULT_POLL_MS, UiPrefs.DEFAULT_DEV_DEFAULT_POLLING_MS)
                    .putInt(UiPrefs.KEY_DEV_SIGNAL_OFF_POLL_MS, UiPrefs.DEFAULT_DEV_SIGNAL_OFF_POLLING_MS)
                    .apply();
            DashcamSettingsController.setRetentionClipCount(prefs,
                    DashcamSettingsController.DEFAULT_RETENTION_CLIP_COUNT);
            DashcamSettingsController.setMaxRetainedEventDirs(prefs,
                    DashcamSettingsController.DEFAULT_MAX_RETAINED_EVENT_DIRS);
            setIntFieldValue(etOverlayTopInsetPx, UiPrefs.DEFAULT_DEV_OVERLAY_TOP_INSET_PX);
            if (seekOverlayTopInsetPx != null) {
                seekOverlayTopInsetPx.setProgress(UiPrefs.DEFAULT_DEV_OVERLAY_TOP_INSET_PX);
            }
            setPollingFieldValue(etForegroundModePollMs, UiPrefs.DEFAULT_DEV_FOREGROUND_MODE_POLL_MS);
            setPollingFieldValue(etDefaultPollMs, UiPrefs.DEFAULT_DEV_DEFAULT_POLLING_MS);
            setPollingFieldValue(etSignalOffPollMs, UiPrefs.DEFAULT_DEV_SIGNAL_OFF_POLLING_MS);
            setIntFieldValue(etDashcamRetentionClipCount, DashcamSettingsController.DEFAULT_RETENTION_CLIP_COUNT);
            setIntFieldValue(etDashcamMaxEventDirs, DashcamSettingsController.DEFAULT_MAX_RETAINED_EVENT_DIRS);
            SignalService.requestRecheck();
            Toast.makeText(v.getContext(), R.string.settings_dev_defaults_reset, Toast.LENGTH_SHORT).show();
        });
    }

    private void setIntFieldValue(EditText editText, int value) {
        if (editText == null) return;
        editText.setText(String.valueOf(value));
        editText.setSelection(editText.getText().length());
    }

    private void setPollingFieldValue(EditText editText, int valueMs) {
        if (editText == null) return;
        editText.setText(String.valueOf(valueMs));
        editText.setSelection(editText.getText().length());
    }

    private void bindIntSlider(
            SharedPreferences prefs,
            SeekBar seek,
            EditText edit,
            String key,
            int maxValue,
            int step,
            ToIntFunction<SharedPreferences> getter,
            IntUnaryOperator clamp
    ) {
        if (seek == null) return;

        final boolean[] syncing = {false};
        int savedValue = getter.applyAsInt(prefs);
        seek.setMax(maxValue / step);
        seek.setProgress(savedValue / step);

        if (edit != null) {
            setIntFieldValue(edit, savedValue);
            edit.addTextChangedListener(new SimpleTextWatcher() {
                @Override
                public void afterTextChanged(Editable s) {
                    if (syncing[0] || s == null) return;
                    String text = s.toString().trim();
                    if (text.isEmpty()) return;
                    try {
                        int value = clamp.applyAsInt(Integer.parseInt(text));
                        prefs.edit().putInt(key, value).apply();
                        int progress = value / step;
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
                int value;
                try {
                    value = text.isEmpty() ? getter.applyAsInt(prefs) : Integer.parseInt(text);
                } catch (NumberFormatException ignored) {
                    value = getter.applyAsInt(prefs);
                }
                value = clamp.applyAsInt(value);
                prefs.edit().putInt(key, value).apply();
                syncing[0] = true;
                setIntFieldValue(edit, value);
                syncing[0] = false;
                int progress = value / step;
                if (seek.getProgress() != progress) {
                    seek.setProgress(progress);
                }
            });
        }

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int value = clamp.applyAsInt(progress * step);
                prefs.edit().putInt(key, value).apply();
                if (edit == null) return;
                String currentValue = edit.getText() == null ? "" : edit.getText().toString();
                String nextValue = String.valueOf(value);
                if (!nextValue.equals(currentValue)) {
                    syncing[0] = true;
                    setIntFieldValue(edit, value);
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

    private void bindPollingField(
            SharedPreferences prefs,
            EditText editText,
            String key,
            int initialValueMs
    ) {
        if (editText == null) return;
        setPollingFieldValue(editText, initialValueMs);

        editText.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                Integer valueMs = parsePollingMsOrNull(s == null ? null : s.toString());
                if (valueMs == null) return;
                prefs.edit().putInt(key, valueMs).apply();
                SignalService.requestRecheck();
            }
        });
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;
            Integer parsed = parsePollingMsOrNull(
                    editText.getText() == null ? null : editText.getText().toString()
            );
            int valueMs = parsed != null ? parsed : initialValueMs;
            valueMs = UiPrefs.clampDevPollingMs(valueMs);
            prefs.edit().putInt(key, valueMs).apply();
            String normalized = String.valueOf(valueMs);
            if (!normalized.contentEquals(editText.getText())) {
                editText.setText(normalized);
                editText.setSelection(editText.getText().length());
            }
            SignalService.requestRecheck();
        });
    }

    private Integer parsePollingMsOrNull(String value) {
        try {
            if (value == null) return null;
            String trimmed = value.trim();
            if (trimmed.isEmpty()) return null;
            return UiPrefs.clampDevPollingMs(Integer.parseInt(trimmed));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void applyDashcamStoragePath(
            SharedPreferences prefs,
            EditText etDashcamRecordsPath,
            TextView tvDashcamRecordsPath,
            Context context,
            String path
    ) {
        DashcamSettingsController.setConfiguredRecordsPath(prefs, path);
        String normalized = DashcamSettingsController.getConfiguredRecordsPath(prefs);
        if (etDashcamRecordsPath != null && !normalized.contentEquals(etDashcamRecordsPath.getText())) {
            etDashcamRecordsPath.setText(normalized);
            etDashcamRecordsPath.setSelection(etDashcamRecordsPath.getText().length());
        }
        refreshDashcamStoragePath(tvDashcamRecordsPath, context);
    }

    private void refreshDashcamStoragePath(TextView tvDashcamRecordsPath, Context context) {
        if (tvDashcamRecordsPath == null || context == null) return;
        tvDashcamRecordsPath.setText(DashcamSettingsController.getRecordsBaseDir(context).getAbsolutePath());
    }

    private String editableToString(EditText editText) {
        return editText == null || editText.getText() == null ? "" : editText.getText().toString();
    }

}
