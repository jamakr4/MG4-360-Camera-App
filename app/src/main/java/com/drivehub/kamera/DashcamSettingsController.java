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

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SEGMENT_MIN = "segmentMin";
    private static final String KEY_TOTAL_MIN = "totalMin";
    private static final String KEY_RECORDING_FPS = "recordingFps";
    private static final int REQ_STORAGE = 1337;
    private static final int DEFAULT_RECORDING_FPS = 15;
    private static final int MIN_RECORDING_FPS = 1;
    private static final int MAX_RECORDING_FPS = 60;

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
            TextView tvRecordsPath,
            Button btnExportUsb
    ) {
        int segmentMin = prefs.getInt(KEY_SEGMENT_MIN, 3);
        int totalMin = Math.max(segmentMin, prefs.getInt(KEY_TOTAL_MIN, 30));
        int recordingFps = getRecordingFps(prefs);

        if (swEnabled != null) {
            syncingEnabled = true;
            swEnabled.setChecked(prefs.getBoolean(KEY_ENABLED, false));
            syncingEnabled = false;
            swEnabled.setOnCheckedChangeListener((buttonView, checked) -> {
                if (syncingEnabled) return;
                prefs.edit().putBoolean(KEY_ENABLED, checked).apply();
                if (checked) {
                    if (!hasStoragePermission()) {
                        ActivityCompat.requestPermissions(
                                activity,
                                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                REQ_STORAGE
                        );
                        Toast.makeText(activity, R.string.settings_storage_permission_required, Toast.LENGTH_SHORT).show();
                    }
                    saveFields(prefs, etSegmentMin, etTotalMin, etRecordingFps, false);
                    RecordingService.startIfNeeded(activity);
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
        bindFields(prefs, etSegmentMin, etTotalMin, etRecordingFps);

        if (tvRecordsPath != null) {
            tvRecordsPath.setText(getRecordsBaseDir().getAbsolutePath());
        }
        if (btnExportUsb != null) {
            btnExportUsb.setVisibility(findMountedUsbRoot() == null ? android.view.View.GONE : android.view.View.VISIBLE);
            btnExportUsb.setOnClickListener(v ->
                    Toast.makeText(activity, R.string.settings_usb_export_todo, Toast.LENGTH_LONG).show()
            );
        }
    }

    private void bindFields(SharedPreferences prefs, EditText etSegmentMin, EditText etTotalMin,
                            EditText etRecordingFps) {
        android.text.TextWatcher watcher = new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                saveFields(prefs, etSegmentMin, etTotalMin, etRecordingFps, false);
            }
        };
        if (etSegmentMin != null) {
            etSegmentMin.addTextChangedListener(watcher);
            etSegmentMin.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) saveFields(prefs, etSegmentMin, etTotalMin, etRecordingFps, true);
            });
        }
        if (etTotalMin != null) {
            etTotalMin.addTextChangedListener(watcher);
            etTotalMin.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) saveFields(prefs, etSegmentMin, etTotalMin, etRecordingFps, true);
            });
        }
        if (etRecordingFps != null) {
            etRecordingFps.addTextChangedListener(watcher);
            etRecordingFps.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) saveFields(prefs, etSegmentMin, etTotalMin, etRecordingFps, true);
            });
        }
    }

    private void saveFields(SharedPreferences prefs, EditText etSegmentMin, EditText etTotalMin,
                            EditText etRecordingFps, boolean normalizeFields) {
        int segmentMin = parsePositiveInt(textOf(etSegmentMin), 3);
        int totalMin = parsePositiveInt(textOf(etTotalMin), 30);
        int recordingFps = clampRecordingFps(parsePositiveInt(textOf(etRecordingFps), DEFAULT_RECORDING_FPS));
        if (totalMin < segmentMin) totalMin = segmentMin;
        prefs.edit()
                .putInt(KEY_SEGMENT_MIN, segmentMin)
                .putInt(KEY_TOTAL_MIN, totalMin)
                .putInt(KEY_RECORDING_FPS, recordingFps)
                .apply();
        if (!normalizeFields) return;
        normalizeField(etSegmentMin, segmentMin);
        normalizeField(etTotalMin, totalMin);
        normalizeField(etRecordingFps, recordingFps);
    }

    static int getRecordingFps(SharedPreferences prefs) {
        return clampRecordingFps(prefs.getInt(KEY_RECORDING_FPS, DEFAULT_RECORDING_FPS));
    }

    private static int clampRecordingFps(int fps) {
        return Math.max(MIN_RECORDING_FPS, Math.min(MAX_RECORDING_FPS, fps));
    }

    private void normalizeField(EditText editText, int value) {
        if (editText == null) return;
        String normalized = String.valueOf(value);
        String current = textOf(editText);
        if (normalized.equals(current)) return;
        editText.setText(normalized);
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

    private boolean hasStoragePermission() {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private File getRecordsBaseDir() {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloads, "mg4_cam_records");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private File findMountedUsbRoot() {
        File storageDir = new File("/storage");
        if (!storageDir.exists()) return null;
        File[] roots = storageDir.listFiles();
        if (roots == null) return null;
        for (File root : roots) {
            if (root.isDirectory() && root.canRead() && root.canWrite()) {
                return root;
            }
        }
        return null;
    }

    private abstract static class SimpleTextWatcher implements android.text.TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }
}
