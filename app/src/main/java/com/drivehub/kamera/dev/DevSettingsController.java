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
import android.widget.TextView;
import android.widget.Toast;

public final class DevSettingsController {

    public DevSettingsController() {
    }

    public void bind(
            SharedPreferences prefs,
            EditText etDefaultPollMs,
            EditText etSignalOffPollMs,
            EditText etDashcamRecordsPath,
            TextView tvDashcamRecordsPath,
            Button btnBrowseFolder,
            Button btnResetDefaults
    ) {
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
        bindDashcamStorageOverride(prefs, etDashcamRecordsPath, tvDashcamRecordsPath, btnBrowseFolder);
        bindResetDefaultsButton(prefs, etDefaultPollMs, etSignalOffPollMs, btnResetDefaults);
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
            EditText etDefaultPollMs,
            EditText etSignalOffPollMs,
            Button btnResetDefaults
    ) {
        if (btnResetDefaults == null) return;
        btnResetDefaults.setOnClickListener(v -> {
            prefs.edit()
                    .putInt(UiPrefs.KEY_DEV_DEFAULT_POLL_MS, UiPrefs.DEFAULT_DEV_DEFAULT_POLLING_MS)
                    .putInt(UiPrefs.KEY_DEV_SIGNAL_OFF_POLL_MS, UiPrefs.DEFAULT_DEV_SIGNAL_OFF_POLLING_MS)
                    .apply();
            setPollingFieldValue(etDefaultPollMs, UiPrefs.DEFAULT_DEV_DEFAULT_POLLING_MS);
            setPollingFieldValue(etSignalOffPollMs, UiPrefs.DEFAULT_DEV_SIGNAL_OFF_POLLING_MS);
            SignalService.requestRecheck();
            Toast.makeText(v.getContext(), R.string.settings_dev_defaults_reset, Toast.LENGTH_SHORT).show();
        });
    }

    private void setPollingFieldValue(EditText editText, int valueMs) {
        if (editText == null) return;
        editText.setText(String.valueOf(valueMs));
        editText.setSelection(editText.getText().length());
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
