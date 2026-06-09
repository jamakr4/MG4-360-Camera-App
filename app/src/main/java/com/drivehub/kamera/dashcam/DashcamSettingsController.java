package com.drivehub.kamera.dashcam;

import com.drivehub.kamera.R;

import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.settings.SegmentedControl;
import com.drivehub.kamera.settings.SimpleTextWatcher;
import com.drivehub.kamera.settings.UiPrefs;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;

public final class DashcamSettingsController {

    private static final String TAG = "DashcamSettings";
    public static final String KEY_ENABLED = "enabled";
    public static final int DEFAULT_SEGMENT_SEC = 30;
    public static final int DEFAULT_RETENTION_CLIP_COUNT = 10;
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

    // ---------- Banner group settings ----------
    public static final int BANNER_SIZE_SMALL = 0;
    public static final int BANNER_SIZE_MEDIUM = 1;
    public static final int BANNER_SIZE_LARGE = 2;
    private static final int DEFAULT_BANNER_SIZE = BANNER_SIZE_SMALL;
    private static final int DEFAULT_BANNER_VOLUME = 80;
    // Delay between the two banners when testing a paired group (Pause→Resume, Error→Recovered).
    private static final long PAIRED_TEST_DELAY_MS = 2_000L;

    public enum BannerGroup {
        EVENT("banner_event"),
        PAUSE_RESUME("banner_pause_resume"),
        ERROR_RECOVERED("banner_error_recovered");

        final String prefix;

        BannerGroup(String prefix) {
            this.prefix = prefix;
        }

        String enabledKey() {
            return prefix + "_enabled";
        }

        String sizeKey() {
            return prefix + "_size";
        }

        String volumeKey() {
            return prefix + "_volume";
        }
    }

    // Bundle of UI views per banner group (toggle, S/M/L segmented control, volume slider, test
    // button). The segmented control's children must be ordered S, M, L — the child index maps
    // directly to BANNER_SIZE_SMALL/MEDIUM/LARGE.
    public static final class BannerGroupViews {
        public final Switch toggle;
        public final SegmentedControl sizeGroup;
        public final SeekBar volumeSeek;
        public final TextView volumeValue;
        public final Button testButton;

        public BannerGroupViews(Switch toggle, SegmentedControl sizeGroup,
                SeekBar volumeSeek, TextView volumeValue, Button testButton) {
            this.toggle = toggle;
            this.sizeGroup = sizeGroup;
            this.volumeSeek = volumeSeek;
            this.volumeValue = volumeValue;
            this.testButton = testButton;
        }
    }

    private final MainActivity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean syncingEnabled;

    public DashcamSettingsController(MainActivity activity) {
        this.activity = activity;
    }

    public void bind(
            SharedPreferences prefs,
            Switch swEnabled,
            EditText etRecordingFps,
            EditText etSignature,
            Switch swShowSpeed,
            BannerGroupViews eventBanner,
            BannerGroupViews pauseResumeBanner,
            BannerGroupViews errorRecoveredBanner) {
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

        bindBannerGroup(prefs, BannerGroup.EVENT, eventBanner);
        bindBannerGroup(prefs, BannerGroup.PAUSE_RESUME, pauseResumeBanner);
        bindBannerGroup(prefs, BannerGroup.ERROR_RECOVERED, errorRecoveredBanner);

        bindFields(prefs, etRecordingFps, etSignature);
    }

    private void bindBannerGroup(SharedPreferences prefs, BannerGroup group, BannerGroupViews views) {
        if (views == null) {
            return;
        }
        if (views.toggle != null) {
            views.toggle.setChecked(isBannerEnabled(prefs, group));
            views.toggle.setOnCheckedChangeListener(
                    (buttonView, checked) -> prefs.edit().putBoolean(group.enabledKey(), checked).apply());
        }
        if (views.sizeGroup != null && views.sizeGroup.getChildCount() >= 3) {
            int initialSize = getBannerSize(prefs, group);
            views.sizeGroup.check(views.sizeGroup.getChildAt(initialSize).getId());
            views.sizeGroup.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
                if (!isChecked) return;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (g.getChildAt(i).getId() == checkedId) {
                        prefs.edit().putInt(group.sizeKey(), clampBannerSize(i)).apply();
                        break;
                    }
                }
            });
        }
        if (views.volumeSeek != null) {
            views.volumeSeek.setMax(100);
            int initialVolume = getBannerVolume(prefs, group);
            views.volumeSeek.setProgress(initialVolume);
            updateBannerVolumeValue(views.volumeValue, initialVolume);
            views.volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    updateBannerVolumeValue(views.volumeValue, progress);
                    if (!fromUser) return;
                    prefs.edit().putInt(group.volumeKey(), clampVolume(progress)).apply();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }
        if (views.testButton != null) {
            views.testButton.setOnClickListener(v -> triggerTestBanner(group));
        }
    }

    private void updateBannerVolumeValue(TextView view, int progress) {
        if (view != null) {
            view.setText(progress + "%");
        }
    }

    private void triggerTestBanner(BannerGroup group) {
        Context ctx = activity;
        switch (group) {
            case EVENT:
                DashcamEventOverlayService.showConfirmationForced(ctx);
                break;
            case PAUSE_RESUME:
                DashcamEventOverlayService.showOemPauseForced(ctx);
                mainHandler.postDelayed(
                        () -> DashcamEventOverlayService.showOemResumeForced(ctx),
                        PAIRED_TEST_DELAY_MS);
                break;
            case ERROR_RECOVERED:
                DashcamEventOverlayService.showRecordingErrorForced(
                        ctx,
                        R.string.dashcam_recording_error_overlay_subtitle_generic,
                        R.string.notification_dashcam_recording_error_text);
                mainHandler.postDelayed(
                        () -> DashcamEventOverlayService.showRecordingRecoveredForced(ctx),
                        PAIRED_TEST_DELAY_MS);
                break;
        }
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

    public static boolean isBannerEnabled(SharedPreferences prefs, BannerGroup group) {
        return prefs.getBoolean(group.enabledKey(), true);
    }

    public static int getBannerSize(SharedPreferences prefs, BannerGroup group) {
        return clampBannerSize(prefs.getInt(group.sizeKey(), DEFAULT_BANNER_SIZE));
    }

    public static int getBannerVolume(SharedPreferences prefs, BannerGroup group) {
        return clampVolume(prefs.getInt(group.volumeKey(), DEFAULT_BANNER_VOLUME));
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
        if (!dir.mkdirs() && !dir.exists()) {
            Log.w(TAG, "Failed to create records dir: " + dir.getAbsolutePath());
        }
        return dir;
    }

    private static int clampRecordingFps(int fps) {
        return Math.max(MIN_RECORDING_FPS, Math.min(MAX_RECORDING_FPS, fps));
    }

    private static int clampVolume(int volume) {
        return Math.max(0, Math.min(100, volume));
    }

    private static int clampBannerSize(int size) {
        return Math.max(BANNER_SIZE_SMALL, Math.min(BANNER_SIZE_LARGE, size));
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
