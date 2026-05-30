package com.drivehub.kamera.dashcam;

import com.drivehub.kamera.R;

import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.settings.SimpleTextWatcher;
import com.drivehub.kamera.settings.UiPrefs;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.text.Editable;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;

public final class DashcamSettingsController {

    public static final String KEY_ENABLED = "enabled";
    public static final int DEFAULT_SEGMENT_SEC = 30;
    public static final int DEFAULT_TOTAL_RETENTION_MIN = 5;
    private static final String KEY_RECORDS_PATH = "recordsPath";
    private static final String KEY_RECORDING_FPS = "recordingFps";
    private static final String KEY_SIGNATURE = "recordingSignature";
    private static final String KEY_SHOW_SPEED = "recordingShowSpeed";
    private static final int REQ_STORAGE = 1337;
    private static final int DEFAULT_RECORDING_FPS = 25;
    private static final int MIN_RECORDING_FPS = 1;
    private static final int MAX_RECORDING_FPS = 60;
    private static final int MAX_SIGNATURE_LENGTH = 40;
    private static final String RECORDS_DIR_NAME = "dashcam";

    private final MainActivity activity;
    private boolean syncingEnabled;

    public DashcamSettingsController(MainActivity activity) {
        this.activity = activity;
    }

    public void bind(
            SharedPreferences prefs,
            Switch swEnabled,
            EditText etRecordingFps,
            EditText etSignature,
            Switch swShowSpeed) {
        int recordingFps = getRecordingFps(prefs);
        String signature = getRecordingSignature(prefs);

        if (swEnabled != null) {
            syncingEnabled = true;
            swEnabled.setChecked(prefs.getBoolean(KEY_ENABLED, false));
            syncingEnabled = false;
            swEnabled.setOnCheckedChangeListener((buttonView, checked) -> {
                if (syncingEnabled)
                    return;
                prefs.edit().putBoolean(KEY_ENABLED, checked).apply();
                if (checked) {
                    if (!hasStoragePermission()) {
                        ActivityCompat.requestPermissions(
                                activity,
                                new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE },
                                REQ_STORAGE);
                        Toast.makeText(activity, R.string.settings_storage_permission_required, Toast.LENGTH_SHORT)
                                .show();
                    }
                    saveFields(prefs, etRecordingFps, etSignature, false);
                    RecordingService.startIfDashcamEnabled(activity);
                } else {
                    RecordingService.stopIfRunning(activity);
                }
            });
        }

        if (etRecordingFps != null) {
            etRecordingFps.setText(String.valueOf(recordingFps));
            etRecordingFps.setSelection(etRecordingFps.getText().length());
        }
        if (etSignature != null) {
            etSignature.setText(signature);
            etSignature.setSelection(etSignature.getText().length());
        }
        if (swShowSpeed != null) {
            swShowSpeed.setChecked(shouldShowSpeed(prefs));
            swShowSpeed.setOnCheckedChangeListener(
                    (buttonView, checked) -> prefs.edit().putBoolean(KEY_SHOW_SPEED, checked).apply());
        }
        bindFields(prefs, etRecordingFps, etSignature);
    }

    private void bindFields(SharedPreferences prefs, EditText etRecordingFps, EditText etSignature) {
        android.text.TextWatcher watcher = new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                saveFields(prefs, etRecordingFps, etSignature, false);
            }
        };
        Runnable normalize = () -> saveFields(prefs, etRecordingFps, etSignature, true);
        bindEditText(etRecordingFps, watcher, normalize);
        bindEditText(etSignature, watcher, normalize);
    }

    private void bindEditText(EditText editText, android.text.TextWatcher watcher, Runnable onBlur) {
        if (editText == null)
            return;
        editText.addTextChangedListener(watcher);
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus)
                onBlur.run();
        });
    }

    private void saveFields(SharedPreferences prefs, EditText etRecordingFps, EditText etSignature,
            boolean normalizeFields) {
        int recordingFps = clampRecordingFps(parsePositiveInt(textOf(etRecordingFps), DEFAULT_RECORDING_FPS));
        String signature = normalizeSignature(textOf(etSignature));
        prefs.edit()
                .putInt(KEY_RECORDING_FPS, recordingFps)
                .putString(KEY_SIGNATURE, signature)
                .apply();
        if (!normalizeFields)
            return;
        normalizeField(etRecordingFps, recordingFps);
        normalizeField(etSignature, signature);
    }

    static int getSegmentDurationSec() {
        return DEFAULT_SEGMENT_SEC;
    }

    static int getRecordingFps(SharedPreferences prefs) {
        return clampRecordingFps(prefs.getInt(KEY_RECORDING_FPS, DEFAULT_RECORDING_FPS));
    }

    static String getRecordingSignature(SharedPreferences prefs) {
        return normalizeSignature(prefs.getString(KEY_SIGNATURE, ""));
    }

    static boolean shouldShowSpeed(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_SHOW_SPEED, true);
    }

    public static String getConfiguredRecordsPath(SharedPreferences prefs) {
        return normalizeRecordsPath(prefs.getString(KEY_RECORDS_PATH, ""));
    }

    public static void setConfiguredRecordsPath(SharedPreferences prefs, String recordsPath) {
        prefs.edit().putString(KEY_RECORDS_PATH, normalizeRecordsPath(recordsPath)).apply();
    }

    public static File getRecordsBaseDir(Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        String customPath = getConfiguredRecordsPath(prefs);
        File dir = customPath.isEmpty() ? getDefaultRecordsBaseDir() : new File(customPath);
        // noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private static int clampRecordingFps(int fps) {
        return Math.max(MIN_RECORDING_FPS, Math.min(MAX_RECORDING_FPS, fps));
    }

    private void normalizeField(EditText editText, int value) {
        if (editText == null)
            return;
        String normalized = String.valueOf(value);
        String current = textOf(editText);
        if (normalized.equals(current))
            return;
        editText.setText(normalized);
        editText.setSelection(editText.getText().length());
    }

    private void normalizeField(EditText editText, String value) {
        if (editText == null)
            return;
        String current = textOf(editText);
        if (value.equals(current))
            return;
        editText.setText(value);
        editText.setSelection(editText.getText().length());
    }

    private String textOf(EditText editText) {
        return editText == null || editText.getText() == null ? "" : editText.getText().toString();
    }

    private int parsePositiveInt(String s, int def) {
        try {
            int value = Integer.parseInt(s.trim());
            return Math.max(1, value);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static String normalizeSignature(String value) {
        if (value == null)
            return "";
        String trimmed = value.trim();
        if (trimmed.length() <= MAX_SIGNATURE_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_SIGNATURE_LENGTH);
    }

    private static String normalizeRecordsPath(String value) {
        if (value == null)
            return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty())
            return "";
        while (trimmed.endsWith("/") && trimmed.length() > 1) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private boolean hasStoragePermission() {
        return ContextCompat.checkSelfPermission(activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private static File getDefaultRecordsBaseDir() {
        return new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), RECORDS_DIR_NAME);
    }
}
