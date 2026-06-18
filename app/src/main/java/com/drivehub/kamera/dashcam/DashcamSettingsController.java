package com.drivehub.kamera.dashcam;

import com.drivehub.kamera.R;

import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.settings.SegmentedControl;
import com.drivehub.kamera.settings.SimpleTextWatcher;
import com.drivehub.kamera.settings.UiPrefs;

import android.Manifest;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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
    public static final int DEFAULT_MAX_RETAINED_EVENT_DIRS = 5;
    public static final int MIN_RETENTION_CLIP_COUNT = 1;
    public static final int MAX_RETENTION_CLIP_COUNT = 500;
    public static final int MIN_MAX_RETAINED_EVENT_DIRS = 1;
    public static final int MAX_MAX_RETAINED_EVENT_DIRS = 50;
    private static final String KEY_RECORDS_PATH = "recordsPath";
    private static final String KEY_RECORDING_FPS = "recordingFps";
    private static final String KEY_SIGNATURE = "recordingSignature";
    private static final String KEY_SHOW_SPEED = "recordingShowSpeed";
    private static final String KEY_TEST_RECORD_ENABLED = "testRecordEnabled";
    private static final String KEY_TEST_RECORD_DURATION_SEC = "testRecordDurationSec";
    private static final String KEY_RETENTION_CLIP_COUNT = "devRetentionClipCount";
    private static final String KEY_MAX_RETAINED_EVENT_DIRS = "devMaxRetainedEventDirs";
    private static final int REQ_STORAGE = 1337;
    private static final int DEFAULT_RECORDING_FPS = 25;
    private static final int MIN_RECORDING_FPS = 1;
    private static final int MAX_RECORDING_FPS = 60;
    private static final int DEFAULT_TEST_RECORD_DURATION_SEC = 30;
    private static final int MIN_TEST_RECORD_DURATION_SEC = 0;
    private static final int MAX_TEST_RECORD_DURATION_SEC = 120;
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

    // Bundle of UI views for the storage target group. The segmented control's children must be
    // ordered Auto, USB, Internal — the child index maps directly to
    // DashcamStorageManager.TARGET_AUTO/USB_ONLY/INTERNAL_ONLY.
    public static final class StorageViews {
        public final SegmentedControl targetGroup;
        public final TextView statusText;
        public final TextView activePathText;
        public final Button ejectButton;
        public final TextView internalWarningText;
        public final EditText usbClipCount;
        public final EditText usbEventDirs;

        public StorageViews(SegmentedControl targetGroup, TextView statusText,
                TextView activePathText, Button ejectButton, TextView internalWarningText,
                EditText usbClipCount, EditText usbEventDirs) {
            this.targetGroup = targetGroup;
            this.statusText = statusText;
            this.activePathText = activePathText;
            this.ejectButton = ejectButton;
            this.internalWarningText = internalWarningText;
            this.usbClipCount = usbClipCount;
            this.usbEventDirs = usbEventDirs;
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
    private BroadcastReceiver usbEjectReceiver;

    public DashcamSettingsController(MainActivity activity) {
        this.activity = activity;
    }

    // ---------- View binding ----------

    // Wires all settings views; starts/stops RecordingService via the enabled toggle.
    public void bind(
            SharedPreferences prefs,
            Switch swEnabled,
            EditText etRecordingFps,
            EditText etSignature,
            Switch swShowSpeed,
            Switch swTestRecordEnabled,
            EditText etTestRecordDuration,
            StorageViews storageViews,
            BannerGroupViews eventBanner,
            BannerGroupViews pauseResumeBanner,
            BannerGroupViews errorRecoveredBanner) {
        int recordingFps = getRecordingFps(prefs);
        String signature = getRecordingSignature(prefs);
        int testRecordDurationSec = getTestRecordDurationSec(prefs);

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
                    saveFields(prefs, etRecordingFps, etSignature, etTestRecordDuration, false);
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
        if (swTestRecordEnabled != null) {
            swTestRecordEnabled.setChecked(isTestRecordEnabled(prefs));
            swTestRecordEnabled.setOnCheckedChangeListener((buttonView, checked) -> {
                prefs.edit().putBoolean(KEY_TEST_RECORD_ENABLED, checked).apply();
                activity.refreshTestRecordButtonState();
            });
        }
        if (etTestRecordDuration != null) {
            etTestRecordDuration.setText(String.valueOf(testRecordDurationSec));
            etTestRecordDuration.setSelection(etTestRecordDuration.getText().length());
        }
        bindStorageGroup(prefs, storageViews);

        bindBannerGroup(prefs, BannerGroup.EVENT, eventBanner);
        bindBannerGroup(prefs, BannerGroup.PAUSE_RESUME, pauseResumeBanner);
        bindBannerGroup(prefs, BannerGroup.ERROR_RECOVERED, errorRecoveredBanner);

        bindFields(prefs, etRecordingFps, etSignature, etTestRecordDuration);
    }

    // Wires storage target, USB limits, and eject button. Null-safe — pass null to skip the whole section.
    private void bindStorageGroup(SharedPreferences prefs, StorageViews views) {
        if (views == null) {
            return;
        }
        if (views.targetGroup != null && views.targetGroup.getChildCount() >= 3) {
            int initialTarget = DashcamStorageManager.getStorageTarget(prefs);
            views.targetGroup.check(views.targetGroup.getChildAt(initialTarget).getId());
            views.targetGroup.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
                if (!isChecked) return;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (g.getChildAt(i).getId() == checkedId) {
                        DashcamStorageManager.setStorageTarget(prefs, i);
                        refreshStorageStatus(prefs, views);
                        // USB-only without USB makes the recording loop end with a fatal error
                        // and stopSelf(); the service is then gone until the user toggles the
                        // dashcam off+on. Switching the target should be enough — kick the
                        // service back up if it's idle while the dashcam is enabled.
                        if (prefs.getBoolean(KEY_ENABLED, false) && !RecordingService.isRunning()) {
                            RecordingService.startIfDashcamEnabled(activity);
                        }
                        break;
                    }
                }
            });
        }
        if (views.usbClipCount != null) {
            views.usbClipCount.setText(String.valueOf(DashcamStorageManager.getUsbRetentionClipCount(prefs)));
            views.usbClipCount.setSelection(views.usbClipCount.getText().length());
            bindEditText(views.usbClipCount, new SimpleTextWatcher() {
                @Override
                public void afterTextChanged(Editable s) {
                    Integer value = parseIntOrNull(s);
                    if (value != null) {
                        DashcamStorageManager.setUsbRetentionClipCount(prefs, value);
                    }
                }
            }, () -> normalizeField(views.usbClipCount, DashcamStorageManager.getUsbRetentionClipCount(prefs)));
        }
        if (views.usbEventDirs != null) {
            views.usbEventDirs.setText(String.valueOf(DashcamStorageManager.getUsbMaxRetainedEventDirs(prefs)));
            views.usbEventDirs.setSelection(views.usbEventDirs.getText().length());
            bindEditText(views.usbEventDirs, new SimpleTextWatcher() {
                @Override
                public void afterTextChanged(Editable s) {
                    Integer value = parseIntOrNull(s);
                    if (value != null) {
                        DashcamStorageManager.setUsbMaxRetainedEventDirs(prefs, value);
                    }
                }
            }, () -> normalizeField(views.usbEventDirs, DashcamStorageManager.getUsbMaxRetainedEventDirs(prefs)));
        }
        if (views.ejectButton != null) {
            views.ejectButton.setOnClickListener(v -> showUsbEjectConfirmDialog());
        }
        refreshStorageStatus(prefs, views);
    }

    // ---------- Storage status ----------

    // Runs a USB write test on a worker thread and updates the status label + active path.
    private void refreshStorageStatus(SharedPreferences prefs, StorageViews views) {
        if (views == null || views.statusText == null) {
            return;
        }
        int target = DashcamStorageManager.getStorageTarget(prefs);
        if (views.internalWarningText != null) {
            views.internalWarningText.setVisibility(
                    target == DashcamStorageManager.TARGET_INTERNAL_ONLY ? android.view.View.VISIBLE
                            : android.view.View.GONE);
        }
        views.statusText.setText(R.string.settings_dashcam_storage_status_checking);
        if (views.activePathText != null) {
            views.activePathText.setText("");
        }
        final Context appContext = activity.getApplicationContext();
        new Thread(() -> {
            DashcamStorageManager.Resolution res = DashcamStorageManager.resolve(appContext);
            mainHandler.post(() -> applyStorageStatus(views, res));
        }, "DashcamStorageStatusProbe").start();
    }

    // Maps a resolved DashcamStorageManager.Resolution to the status label, path text, and eject button visibility.
    private void applyStorageStatus(StorageViews views, DashcamStorageManager.Resolution res) {
        if (views.statusText == null) {
            return;
        }
        int statusRes;
        if (res.usingUsb) {
            statusRes = R.string.settings_dashcam_storage_status_usb_active;
        } else if (res.usbState == DashcamStorageManager.UsbState.MULTIPLE_MEDIA) {
            // Applies to AUTO (falls back to internal) and USB_ONLY (no recording) alike —
            // the ambiguity itself is the message.
            statusRes = R.string.settings_dashcam_storage_status_multiple_media;
        } else if (res.target == DashcamStorageManager.TARGET_INTERNAL_ONLY) {
            statusRes = R.string.settings_dashcam_storage_status_internal_active;
        } else if (res.target == DashcamStorageManager.TARGET_USB_ONLY) {
            statusRes = R.string.settings_dashcam_storage_status_usb_required_missing;
        } else {
            // AUTO with unusable USB — explain why we are on internal.
            switch (res.usbState) {
                case NOT_WRITABLE:
                    statusRes = R.string.settings_dashcam_storage_status_usb_not_writable;
                    break;
                case WRITE_TEST_FAILED:
                    statusRes = R.string.settings_dashcam_storage_status_usb_write_test_failed;
                    break;
                default:
                    statusRes = R.string.settings_dashcam_storage_status_no_medium;
                    break;
            }
        }
        views.statusText.setText(colorizeStatusIcon(activity.getString(statusRes)));
        if (views.activePathText != null) {
            views.activePathText.setText(res.baseDir == null
                    ? ""
                    : activity.getString(R.string.settings_dashcam_storage_active_path,
                            res.baseDir.getAbsolutePath()));
        }
        if (views.ejectButton != null) {
            views.ejectButton.setVisibility(res.usingUsb ? View.VISIBLE : View.GONE);
        }
    }

    // ---------- USB eject flow ----------

    // Shows the USB eject confirmation dialog before initiating the eject sequence.
    private void showUsbEjectConfirmDialog() {
        showConfirmDialog(
                activity.getString(R.string.settings_dashcam_storage_eject_title),
                activity.getString(R.string.settings_dashcam_storage_eject_confirm_message),
                activity.getString(R.string.settings_dashcam_storage_eject_confirm_action),
                this::requestUsbEject);
    }

    // Registers a one-shot broadcast receiver for the eject result and sends the eject action to RecordingService.
    private void requestUsbEject() {
        unregisterUsbEjectReceiver();
        usbEjectReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null
                        || !RecordingService.ACTION_USB_EJECT_READY.equals(intent.getAction())) {
                    return;
                }
                unregisterUsbEjectReceiver();
                boolean safeToRemove = intent.getBooleanExtra(RecordingService.EXTRA_USB_EJECT_SAFE_TO_REMOVE, false);
                int messageRes = intent.getIntExtra(
                        RecordingService.EXTRA_USB_EJECT_MESSAGE_RES,
                        safeToRemove
                                ? R.string.settings_dashcam_storage_eject_ready_message
                                : R.string.settings_dashcam_storage_eject_unavailable_message);
                showMessageDialog(
                        activity.getString(R.string.settings_dashcam_storage_eject_title),
                        activity.getString(messageRes));
            }
        };
        ContextCompat.registerReceiver(
                activity,
                usbEjectReceiver,
                new IntentFilter(RecordingService.ACTION_USB_EJECT_READY),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
        RecordingService.requestUsbEject(activity);
    }

    // Called when the settings dialog closes — unregisters the USB eject receiver if still pending.
    public void onDismiss() {
        unregisterUsbEjectReceiver();
    }

    private void unregisterUsbEjectReceiver() {
        if (usbEjectReceiver == null) {
            return;
        }
        try {
            activity.unregisterReceiver(usbEjectReceiver);
        } catch (Throwable ignored) {
        }
        usbEjectReceiver = null;
    }

    // ---------- Dialog helpers ----------

    // Shows a confirm/cancel dialog reusing the OTA dialog layout.
    private void showConfirmDialog(String title, String message, String confirmText, Runnable onConfirm) {
        Dialog dialog = createBaseDialog();
        TextView titleView = dialog.findViewById(R.id.tvOtaRefreshTitle);
        TextView messageView = dialog.findViewById(R.id.tvOtaRefreshMessage);
        TextView confirmButton = dialog.findViewById(R.id.btnOtaRefresh);
        TextView closeButton = dialog.findViewById(R.id.btnOtaClose);
        if (titleView != null) {
            titleView.setText(title);
        }
        if (messageView != null) {
            messageView.setText(message);
        }
        if (confirmButton != null) {
            confirmButton.setText(confirmText);
            stylePrimaryButton(confirmButton);
            confirmButton.setOnClickListener(v -> {
                dialog.dismiss();
                if (onConfirm != null) {
                    onConfirm.run();
                }
            });
        }
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dialog.dismiss());
        }
        showCentered(dialog, 540);
    }

    // Shows an info-only dialog (no confirm button).
    private void showMessageDialog(String title, String message) {
        Dialog dialog = createBaseDialog();
        TextView titleView = dialog.findViewById(R.id.tvOtaRefreshTitle);
        TextView messageView = dialog.findViewById(R.id.tvOtaRefreshMessage);
        View confirmButton = dialog.findViewById(R.id.btnOtaRefresh);
        TextView closeButton = dialog.findViewById(R.id.btnOtaClose);
        if (titleView != null) {
            titleView.setText(title);
        }
        if (messageView != null) {
            messageView.setText(message);
        }
        if (confirmButton != null) {
            confirmButton.setVisibility(View.GONE);
        }
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dialog.dismiss());
        }
        showCentered(dialog, 540);
    }

    private Dialog createBaseDialog() {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_ota_refresh);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        return dialog;
    }

    private void showCentered(Dialog dialog, int widthDp) {
        dialog.show();
        Window window = dialog.getWindow();
        if (window == null) return;
        float density = activity.getResources().getDisplayMetrics().density;
        window.setLayout((int) (widthDp * density), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void stylePrimaryButton(TextView button) {
        int accentColor = UiPrefs.getAccentColorInt(UiPrefs.getPrefs(activity));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(14f));
        background.setColor(accentColor);
        if (UiPrefs.isLightColor(accentColor)) {
            background.setStroke((int) dp(1f), 0x33000000);
            button.setTextColor(0xFF111111);
        } else {
            background.setStroke(0, Color.TRANSPARENT);
            button.setTextColor(Color.WHITE);
        }
        button.setBackground(background);
    }

    private float dp(float value) {
        return value * activity.getResources().getDisplayMetrics().density;
    }

    /** Tints a leading ✓ green / ✗ red while keeping the rest of the text untouched. */
    private CharSequence colorizeStatusIcon(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        char first = text.charAt(0);
        int colorRes;
        if (first == '✓') {
            colorRes = R.color.settings_status_ok_icon;
        } else if (first == '✗') {
            colorRes = R.color.settings_status_error_icon;
        } else {
            return text;
        }
        android.text.SpannableString spannable = new android.text.SpannableString(text);
        spannable.setSpan(
                new android.text.style.ForegroundColorSpan(ContextCompat.getColor(activity, colorRes)),
                0, 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spannable;
    }

    private Integer parseIntOrNull(Editable s) {
        if (s == null) return null;
        String text = s.toString().trim();
        if (text.isEmpty()) return null;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // ---------- Banner group binding ----------

    // Wires toggle, S/M/L size selector, volume slider, and test button for one banner group.
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

    // Fires a forced banner preview for the settings test button.
    // Paired groups (PAUSE_RESUME, ERROR_RECOVERED) show both banners with a 2 s gap.
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

    // ---------- Field persistence ----------

    // Attaches text watchers to the three editable recording fields (FPS, signature, duration).
    private void bindFields(SharedPreferences prefs, EditText etRecordingFps, EditText etSignature,
            EditText etTestRecordDuration) {
        android.text.TextWatcher watcher = new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                saveFields(prefs, etRecordingFps, etSignature, etTestRecordDuration, false);
            }
        };
        Runnable normalize = () -> saveFields(prefs, etRecordingFps, etSignature, etTestRecordDuration, true);
        bindEditText(etRecordingFps, watcher, normalize);
        bindEditText(etSignature, watcher, normalize);
        bindEditText(etTestRecordDuration, watcher, normalize);
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

    // Reads, clamps, and persists all three editable fields.
    // When normalizeFields is true the EditTexts are also updated to their clamped values.
    private void saveFields(SharedPreferences prefs, EditText etRecordingFps, EditText etSignature,
            EditText etTestRecordDuration, boolean normalizeFields) {
        int recordingFps = clampRecordingFps(parsePositiveInt(textOf(etRecordingFps), DEFAULT_RECORDING_FPS));
        String signature = normalizeSignature(textOf(etSignature));
        int testRecordDurationSec = clampTestRecordDurationSec(
                parseBoundedInt(textOf(etTestRecordDuration), DEFAULT_TEST_RECORD_DURATION_SEC));
        prefs.edit()
                .putInt(KEY_RECORDING_FPS, recordingFps)
                .putString(KEY_SIGNATURE, signature)
                .putInt(KEY_TEST_RECORD_DURATION_SEC, testRecordDurationSec)
                .apply();
        activity.refreshTestRecordButtonState();
        if (!normalizeFields)
            return;
        normalizeField(etRecordingFps, recordingFps);
        normalizeField(etSignature, signature);
        normalizeField(etTestRecordDuration, testRecordDurationSec);
    }

    // ---------- Prefs getters / setters ----------

    // Returns the fixed segment duration (30 s); not user-configurable.
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

    public static boolean isTestRecordEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_TEST_RECORD_ENABLED, true);
    }

    public static int getTestRecordDurationSec(SharedPreferences prefs) {
        return clampTestRecordDurationSec(
                prefs.getInt(KEY_TEST_RECORD_DURATION_SEC, DEFAULT_TEST_RECORD_DURATION_SEC));
    }

    // Rolling clip count for the ring buffer (dev setting).
    public static int getRetentionClipCount(SharedPreferences prefs) {
        return clampRetentionClipCount(prefs.getInt(KEY_RETENTION_CLIP_COUNT, DEFAULT_RETENTION_CLIP_COUNT));
    }

    public static void setRetentionClipCount(SharedPreferences prefs, int count) {
        prefs.edit().putInt(KEY_RETENTION_CLIP_COUNT, clampRetentionClipCount(count)).apply();
    }

    // Maximum number of saved event directories (dev setting).
    public static int getMaxRetainedEventDirs(SharedPreferences prefs) {
        return clampMaxRetainedEventDirs(
                prefs.getInt(KEY_MAX_RETAINED_EVENT_DIRS, DEFAULT_MAX_RETAINED_EVENT_DIRS));
    }

    public static void setMaxRetainedEventDirs(SharedPreferences prefs, int count) {
        prefs.edit().putInt(KEY_MAX_RETAINED_EVENT_DIRS, clampMaxRetainedEventDirs(count)).apply();
    }

    public static int clampRetentionClipCount(int count) {
        return Math.max(MIN_RETENTION_CLIP_COUNT, Math.min(MAX_RETENTION_CLIP_COUNT, count));
    }

    public static int clampMaxRetainedEventDirs(int count) {
        return Math.max(MIN_MAX_RETAINED_EVENT_DIRS, Math.min(MAX_MAX_RETAINED_EVENT_DIRS, count));
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

    // Custom records path override (dev setting); empty string means use the default Downloads/dashcam.
    public static String getConfiguredRecordsPath(SharedPreferences prefs) {
        return normalizeRecordsPath(prefs.getString(KEY_RECORDS_PATH, ""));
    }

    public static void setConfiguredRecordsPath(SharedPreferences prefs, String recordsPath) {
        prefs.edit().putString(KEY_RECORDS_PATH, normalizeRecordsPath(recordsPath)).apply();
    }

    // Resolves the active records directory — custom path if set, otherwise Downloads/dashcam.
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

    private static int clampTestRecordDurationSec(int durationSec) {
        return Math.max(MIN_TEST_RECORD_DURATION_SEC, Math.min(MAX_TEST_RECORD_DURATION_SEC, durationSec));
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

    private int parseBoundedInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
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
