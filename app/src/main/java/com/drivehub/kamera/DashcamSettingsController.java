package com.drivehub.kamera;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.text.Editable;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;

final class DashcamSettingsController {

    static final String KEY_ENABLED = "enabled";
    static final String KEY_SEGMENT_MIN = "segmentMin";
    static final String KEY_TOTAL_MIN = "totalMin";
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

    DashcamSettingsController(MainActivity activity) {
        this.activity = activity;
    }

    void bind(
            SharedPreferences prefs,
            Switch swEnabled,
            EditText etSegmentMin,
            EditText etTotalMin,
            EditText etRecordingFps,
            EditText etSignature,
            Switch swShowSpeed,
            TextView tvRecordsPath,
            Button btnExportUsb) {
        int segmentMin = prefs.getInt(KEY_SEGMENT_MIN, 3);
        int totalMin = Math.max(segmentMin, prefs.getInt(KEY_TOTAL_MIN, 30));
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
                    saveFields(prefs, etSegmentMin, etTotalMin, etRecordingFps, etSignature, false);
                    RecordingService.startIfDashcamEnabled(activity);
                } else {
                    RecordingService.stopIfRunning(activity);
                }
            });
        }

        if (etSegmentMin != null) {
            etSegmentMin.setText(String.valueOf(segmentMin));
            etSegmentMin.setSelection(etSegmentMin.getText().length());
        }
        if (etTotalMin != null) {
            etTotalMin.setText(String.valueOf(totalMin));
            etTotalMin.setSelection(etTotalMin.getText().length());
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
        bindFields(prefs, etSegmentMin, etTotalMin, etRecordingFps, etSignature);

        if (tvRecordsPath != null) {
            tvRecordsPath.setText(getRecordsBaseDir().getAbsolutePath());
        }
        if (btnExportUsb != null) {
            btnExportUsb
                    .setVisibility(findMountedUsbRoot() == null ? android.view.View.GONE : android.view.View.VISIBLE);
            btnExportUsb.setOnClickListener(
                    v -> Toast.makeText(activity, R.string.settings_usb_export_todo, Toast.LENGTH_LONG).show());
        }
    }

    private void bindFields(SharedPreferences prefs, EditText etSegmentMin, EditText etTotalMin,
            EditText etRecordingFps, EditText etSignature) {
        android.text.TextWatcher watcher = new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                saveFields(prefs, etSegmentMin, etTotalMin, etRecordingFps, etSignature, false);
            }
        };
        Runnable normalize = () -> saveFields(prefs, etSegmentMin, etTotalMin, etRecordingFps, etSignature, true);
        bindEditText(etSegmentMin, watcher, normalize);
        bindEditText(etTotalMin, watcher, normalize);
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

    private void saveFields(SharedPreferences prefs, EditText etSegmentMin, EditText etTotalMin,
            EditText etRecordingFps, EditText etSignature, boolean normalizeFields) {
        int segmentMin = parsePositiveInt(textOf(etSegmentMin), 3);
        int totalMin = parsePositiveInt(textOf(etTotalMin), 30);
        int recordingFps = clampRecordingFps(parsePositiveInt(textOf(etRecordingFps), DEFAULT_RECORDING_FPS));
        String signature = normalizeSignature(textOf(etSignature));
        if (totalMin < segmentMin)
            totalMin = segmentMin;
        prefs.edit()
                .putInt(KEY_SEGMENT_MIN, segmentMin)
                .putInt(KEY_TOTAL_MIN, totalMin)
                .putInt(KEY_RECORDING_FPS, recordingFps)
                .putString(KEY_SIGNATURE, signature)
                .apply();
        if (!normalizeFields)
            return;
        normalizeField(etSegmentMin, segmentMin);
        normalizeField(etTotalMin, totalMin);
        normalizeField(etRecordingFps, recordingFps);
        normalizeField(etSignature, signature);
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

    private boolean hasStoragePermission() {
        return ContextCompat.checkSelfPermission(activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    static File getRecordsBaseDir() {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloads, RECORDS_DIR_NAME);
        // noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private File findMountedUsbRoot() {
        File storageDir = new File("/storage");
        if (!storageDir.exists())
            return null;
        File[] roots = storageDir.listFiles();
        if (roots == null)
            return null;
        for (File root : roots) {
            if (root.isDirectory() && root.canRead() && root.canWrite()) {
                return root;
            }
        }
        return null;
    }
}
