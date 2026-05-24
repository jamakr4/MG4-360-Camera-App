package com.drivehub.kamera;

import android.app.Dialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.util.Locale;

final class OtaController {

    private final AppCompatActivity activity;

    private OtaUpdateManager.UpdateInfo lastCheckInfo;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;
    private Dialog progressDialog;
    private enum VerificationState { IDLE, IN_FLIGHT, PASSED, FAILED }

    private long verificationDownloadId = -1L;
    private VerificationState verificationState = VerificationState.IDLE;
    private OtaUpdateManager.UpdateInfo activeDownloadInfo;
    private TextView releaseTitleView;
    private TextView releaseChannelView;
    private TextView changelogView;
    private TextView githubStatusView;
    private TextView gitlabStatusView;
    private Switch betaSwitch;
    private boolean suppressBetaToggleCallback = false;

    OtaController(AppCompatActivity activity) {
        this.activity = activity;
    }

    // Called once per settings dialog open.
    void setup(
            Dialog settingsDialog,
            TextView updateTag,
            Switch allowBetaSwitch,
            TextView releaseTitle,
            TextView releaseChannel,
            TextView changelog,
            TextView githubStatus,
            TextView gitlabStatus
    ) {
        lastCheckInfo = null;
        releaseTitleView = releaseTitle;
        releaseChannelView = releaseChannel;
        changelogView = changelog;
        githubStatusView = githubStatus;
        gitlabStatusView = gitlabStatus;
        betaSwitch = allowBetaSwitch;

        SharedPreferences prefs = UiPrefs.getPrefs(activity);
        if (betaSwitch != null) {
            suppressBetaToggleCallback = true;
            betaSwitch.setChecked(UiPrefs.isBetaUpdatesEnabled(prefs));
            suppressBetaToggleCallback = false;
            betaSwitch.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
                if (suppressBetaToggleCallback) return;
                prefs.edit().putBoolean(UiPrefs.KEY_ALLOW_BETA_UPDATES, isChecked).apply();
                triggerCheck(settingsDialog, updateTag, true);
            });
        }

        if (updateTag == null) return;
        renderTagState(updateTag, null, true);
        renderUpdateSection(null, true);
        updateTag.setClickable(true);
        updateTag.setFocusable(true);
        updateTag.setOnClickListener(v -> {
            OtaUpdateManager.UpdateInfo info =
                    (OtaUpdateManager.UpdateInfo) updateTag.getTag(R.id.tag_ota_update_info);
            if (info == null) info = lastCheckInfo;
            if (info != null && info.success && info.updateAvailable) {
                maybeStartDownload(info);
                return;
            }
            showRefreshDialog(settingsDialog, updateTag);
        });
        triggerCheck(settingsDialog, updateTag, false);
    }

    // Called from onStop and onDestroy.
    void stop() {
        stopProgressWatcher();
    }

    // -------------------------------------------------------------------------
    // Download flow
    // -------------------------------------------------------------------------

    private void maybeStartDownload(OtaUpdateManager.UpdateInfo info) {
        if (info == null || info.expectedSha256 == null || info.expectedSha256.trim().isEmpty()) {
            OtaDialogs.showMessageDialog(
                    activity,
                    info != null && info.message != null && !info.message.trim().isEmpty()
                            ? info.message
                            : activity.getString(R.string.ota_error_no_hash)
            );
            return;
        }
        OtaDialogs.showConfirmDialog(
                activity,
                activity.getString(R.string.ota_dialog_mobile_warning_message, info.latestVersion),
                activity.getString(R.string.ota_action_download_anyway),
                () -> startDownload(info)
        );
    }

    private void startDownload(OtaUpdateManager.UpdateInfo info) {
        try {
            // Clear any stale watcher state before we bind the new release metadata to the dialog.
            stopProgressWatcher();
            long downloadId = OtaUpdateManager.enqueueDownload(activity, info);
            activeDownloadInfo = info;
            showProgressDialog(info, downloadId);
        } catch (Throwable t) {
            OtaDialogs.showMessageDialog(
                    activity,
                    activity.getString(R.string.ota_dialog_download_failed_message, t.getClass().getSimpleName())
            );
        }
    }

    private void showProgressDialog(OtaUpdateManager.UpdateInfo info, long downloadId) {
        OtaDialogs.ProgressDialogHandle handle = OtaDialogs.showProgressDialog(
                activity,
                activity.getString(R.string.ota_dialog_download_started_message, info.latestVersion),
                () -> retryDownload(downloadId),
                this::openDownloadsFolder
        );
        progressDialog = handle.dialog;
        progressDialog.setOnDismissListener(d -> stopProgressWatcher());
        verificationDownloadId = -1L;
        verificationState = VerificationState.IDLE;

        final TextView statusView = handle.statusView;
        final ProgressBar progressBar = handle.progressBar;
        final View retryButton = handle.retryButton;
        final View installButton = handle.installButton;
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                boolean cont = tickProgress(downloadId, progressBar, statusView, retryButton, installButton);
                if (cont && progressDialog != null && progressDialog.isShowing()) {
                    progressHandler.postDelayed(this, 500L);
                }
            }
        };
        progressHandler.post(progressRunnable);
    }

    private boolean tickProgress(long downloadId, ProgressBar progressBar, TextView statusView,
                                 View retryButton, View installButton) {
        DownloadManager dm = activity.getSystemService(DownloadManager.class);
        if (dm == null) {
            if (statusView != null) statusView.setText(R.string.ota_progress_unavailable);
            if (retryButton != null) retryButton.setVisibility(View.GONE);
            if (installButton != null) installButton.setVisibility(View.GONE);
            return false;
        }

        try (Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(downloadId))) {
            if (cursor == null || !cursor.moveToFirst()) {
                if (statusView != null) statusView.setText(R.string.ota_progress_missing);
                if (retryButton != null) retryButton.setVisibility(View.VISIBLE);
                if (installButton != null) installButton.setVisibility(View.GONE);
                return false;
            }

            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            int pct = (total > 0L) ? (int) ((downloaded * 100L) / total) : 0;
            int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));

            updateProgressBar(progressBar, total, pct);
            if (statusView != null && status != DownloadManager.STATUS_SUCCESSFUL) {
                statusView.setText(progressStatusText(status, pct, downloaded, total, reason));
            }

            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                if (retryButton != null) retryButton.setVisibility(View.GONE);
                if (progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setMax(100);
                    progressBar.setProgress(100);
                }
                if (verificationState == VerificationState.PASSED || verificationState == VerificationState.FAILED) {
                    if (installButton != null) {
                        installButton.setVisibility(verificationState == VerificationState.PASSED ? View.VISIBLE : View.GONE);
                    }
                    return false;
                }
                if (installButton != null) installButton.setVisibility(View.GONE);
                if (verificationState != VerificationState.IN_FLIGHT || verificationDownloadId != downloadId) {
                    startVerification(downloadId, statusView, installButton);
                } else if (statusView != null) {
                    statusView.setText(R.string.ota_progress_verifying);
                }
                return true;
            }

            if (retryButton != null) {
                retryButton.setVisibility(status == DownloadManager.STATUS_FAILED ? View.VISIBLE : View.GONE);
            }
            if (installButton != null) installButton.setVisibility(View.GONE);
            return status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_RUNNING
                    || status == DownloadManager.STATUS_PAUSED;

        } catch (Throwable t) {
            if (statusView != null) {
                statusView.setText(activity.getString(R.string.ota_progress_failed_reason, t.getClass().getSimpleName()));
            }
            if (retryButton != null) retryButton.setVisibility(View.VISIBLE);
            if (installButton != null) installButton.setVisibility(View.GONE);
            return false;
        }
    }

    private void updateProgressBar(ProgressBar bar, long total, int pct) {
        if (bar == null) return;
        if (total > 0L) {
            bar.setIndeterminate(false);
            bar.setMax(100);
            bar.setProgress(Math.max(0, Math.min(100, pct)));
        } else {
            bar.setIndeterminate(true);
        }
    }

    // -------------------------------------------------------------------------
    // Verification
    // -------------------------------------------------------------------------

    private void startVerification(long downloadId, TextView statusView, View installButton) {
        if (verificationState == VerificationState.IN_FLIGHT && verificationDownloadId == downloadId) return;
        verificationDownloadId = downloadId;
        verificationState = VerificationState.IN_FLIGHT;
        if (installButton != null) installButton.setVisibility(View.GONE);
        if (statusView != null) statusView.setText(R.string.ota_progress_verifying);

        Uri apkUri;
        try {
            apkUri = resolveDownloadedApkUri(downloadId);
        } catch (Exception e) {
            verificationState = VerificationState.FAILED;
            if (statusView != null) {
                statusView.setText(activity.getString(
                        R.string.ota_progress_integrity_failed, e.getClass().getSimpleName()));
            }
            return;
        }

        OtaUpdateManager.verifyDownloadedApk(activity, downloadId, apkUri, activeDownloadInfo, (success, computedSha256, message) -> {
            if (downloadId != verificationDownloadId) return;
            verificationState = success ? VerificationState.PASSED : VerificationState.FAILED;
            if (statusView != null) {
                if (success) {
                    statusView.setText(R.string.ota_progress_verified);
                } else {
                    statusView.setText(activity.getString(R.string.ota_progress_integrity_failed, message));
                }
            }
            if (installButton != null) {
                installButton.setVisibility(success ? View.VISIBLE : View.GONE);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Install
    // -------------------------------------------------------------------------

    private void openDownloadsFolder() {
        try {
            Intent downloadsIntent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
            downloadsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            android.content.pm.PackageManager pm = activity.getPackageManager();
            if (pm != null && downloadsIntent.resolveActivity(pm) != null) {
                activity.startActivity(downloadsIntent);
                return;
            }
            throw new IllegalStateException("Downloads app not available");
        } catch (Exception e) {
            OtaDialogs.showMessageDialog(
                    activity,
                    activity.getString(R.string.ota_dialog_open_downloads_failed_message, e.getClass().getSimpleName())
            );
        }
    }

    private Uri resolveDownloadedApkUri(long downloadId) throws Exception {
        DownloadManager dm = activity.getSystemService(DownloadManager.class);
        if (dm == null) return null;
        Uri downloadedUri = dm.getUriForDownloadedFile(downloadId);
        if (downloadedUri != null) {
            return downloadedUri;
        }
        try (Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(downloadId))) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            String localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
            if (localUri == null || localUri.isEmpty()) return null;
            return Uri.parse(localUri);
        }
    }

    // -------------------------------------------------------------------------
    // Retry & cleanup
    // -------------------------------------------------------------------------

    private void retryDownload(long failedDownloadId) {
        OtaUpdateManager.UpdateInfo info = activeDownloadInfo;
        cleanupFailedDownload(failedDownloadId);
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        if (info != null) {
            startDownload(info);
        } else {
            OtaDialogs.showMessageDialog(activity, activity.getString(R.string.ota_status_check_failed));
        }
    }

    private void cleanupFailedDownload(long downloadId) {
        if (downloadId <= 0L) return;
        try {
            DownloadManager dm = activity.getSystemService(DownloadManager.class);
            if (dm != null) dm.remove(downloadId);
        } catch (Throwable ignored) {
        }
    }

    private void stopProgressWatcher() {
        if (progressRunnable != null) {
            progressHandler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
        verificationDownloadId = -1L;
        verificationState = VerificationState.IDLE;
        activeDownloadInfo = null;
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        progressDialog = null;
    }

    // -------------------------------------------------------------------------
    // Check flow
    // -------------------------------------------------------------------------

    private void showRefreshDialog(Dialog settingsDialog, TextView updateTag) {
        OtaDialogs.showRefreshDialog(
                activity,
                lastCheckInfo,
                () -> triggerCheck(settingsDialog, updateTag, true)
        );
    }

    private void triggerCheck(Dialog settingsDialog, TextView updateTag, boolean showToast) {
        if (showToast) {
            Toast.makeText(activity, R.string.ota_toast_checking, Toast.LENGTH_SHORT).show();
        }
        renderTagState(updateTag, null, true);
        renderUpdateSection(null, true);
        SharedPreferences prefs = UiPrefs.getPrefs(activity);
        boolean allowBetaUpdates = UiPrefs.isBetaUpdatesEnabled(prefs);
        OtaUpdateManager.checkForUpdates(activity, allowBetaUpdates, info -> {
            lastCheckInfo = info;
            if (settingsDialog == null || !settingsDialog.isShowing()) return;
            renderTagState(updateTag, info, false);
            renderUpdateSection(info, false);
        });
    }

    // -------------------------------------------------------------------------
    // Tag rendering
    // -------------------------------------------------------------------------

    private void renderTagState(TextView tag, OtaUpdateManager.UpdateInfo info, boolean checking) {
        if (tag == null) return;
        tag.setTag(R.id.tag_ota_update_info, info);
        if (checking) {
            tag.setText(R.string.ota_status_checking);
            tag.setTextColor(ContextCompat.getColor(activity, R.color.settings_update_error_text));
            tag.setBackgroundResource(R.drawable.bg_settings_footer_tag_neutral);
            return;
        }
        if (info != null && info.success && info.updateAvailable) {
            tag.setText(activity.getString(R.string.ota_status_update_available, info.latestVersion));
            tag.setTextColor(ContextCompat.getColor(activity, R.color.settings_update_available_text));
            tag.setBackgroundResource(R.drawable.bg_settings_footer_tag_update);
            return;
        }
        if (info != null && info.success) {
            tag.setText(R.string.ota_status_up_to_date);
            tag.setTextColor(ContextCompat.getColor(activity, R.color.settings_update_ok_text));
            tag.setBackgroundResource(R.drawable.bg_settings_footer_tag_ok);
            return;
        }
        tag.setText(R.string.ota_status_check_failed);
        tag.setTextColor(ContextCompat.getColor(activity, R.color.settings_update_error_text));
        tag.setBackgroundResource(R.drawable.bg_settings_footer_tag_neutral);
    }

    private void renderUpdateSection(OtaUpdateManager.UpdateInfo info, boolean checking) {
        if (releaseTitleView != null) {
            if (checking) {
                releaseTitleView.setText(R.string.ota_update_section_loading);
            } else if (info != null && info.success) {
                String versionLabel = info.prerelease
                        ? activity.getString(R.string.ota_release_title_beta, info.latestVersion)
                        : activity.getString(R.string.ota_release_title_stable, info.latestVersion);
                releaseTitleView.setText(OtaReleaseAssets.firstNonEmpty(info.releaseName, versionLabel));
            } else {
                releaseTitleView.setText(R.string.ota_update_section_unavailable);
            }
        }

        if (releaseChannelView != null) {
            if (checking) {
                releaseChannelView.setText(R.string.ota_update_channel_loading);
            } else if (info != null && info.success) {
                int channelString = info.betaAllowed
                        ? R.string.ota_update_channel_beta_enabled
                        : R.string.ota_update_channel_stable_only;
                String releaseType = activity.getString(
                        info.prerelease ? R.string.ota_release_type_beta : R.string.ota_release_type_stable
                );
                releaseChannelView.setText(activity.getString(channelString, releaseType));
            } else {
                releaseChannelView.setText(R.string.ota_update_channel_error);
            }
        }

        if (changelogView != null) {
            if (checking) {
                changelogView.setText(R.string.ota_update_changelog_loading);
            } else if (info != null && info.success) {
                String notes = OtaReleaseAssets.normalizeReleaseNotes(info.releaseNotes);
                changelogView.setText(notes.isEmpty()
                        ? activity.getString(R.string.ota_update_changelog_empty)
                        : notes);
            } else {
                changelogView.setText(R.string.ota_update_changelog_error);
            }
        }

        OtaUpdateManager.SourceStatus githubStatus = checking
                ? null
                : (info != null ? info.githubStatus : null);
        OtaUpdateManager.SourceStatus gitlabStatus = checking
                ? null
                : (info != null ? info.gitlabStatus : null);
        renderSourceStatus(githubStatusView, githubStatus, checking);
        renderSourceStatus(gitlabStatusView, gitlabStatus, checking);
    }

    private void renderSourceStatus(TextView view, OtaUpdateManager.SourceStatus status, boolean checking) {
        if (view == null) return;

        if (checking) {
            view.setText(R.string.ota_source_status_loading);
            view.setTextColor(ContextCompat.getColor(activity, R.color.settings_text_secondary));
            return;
        }

        if (status != null && status.available) {
            view.setText(activity.getString(R.string.ota_source_status_available, status.sourceName));
            view.setTextColor(ContextCompat.getColor(activity, R.color.settings_update_ok_text));
            return;
        }

        String sourceName = status != null && status.sourceName != null && !status.sourceName.trim().isEmpty()
                ? status.sourceName
                : "";
        view.setText(activity.getString(R.string.ota_source_status_unavailable, sourceName));
        view.setTextColor(ContextCompat.getColor(activity, R.color.settings_beta));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private CharSequence progressStatusText(int status, int pct, long downloaded, long total, int reason) {
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            return activity.getString(R.string.ota_progress_complete);
        }
        if (status == DownloadManager.STATUS_FAILED) {
            String r = reasonText(status, reason);
            return r == null
                    ? activity.getString(R.string.ota_progress_failed)
                    : activity.getString(R.string.ota_progress_failed_reason, r);
        }
        if (status == DownloadManager.STATUS_PAUSED) {
            String r = reasonText(status, reason);
            return r == null
                    ? activity.getString(R.string.ota_progress_paused, pct)
                    : activity.getString(R.string.ota_progress_paused_reason, pct, r);
        }
        if (status == DownloadManager.STATUS_PENDING) {
            return activity.getString(R.string.ota_progress_pending);
        }
        if (total > 0L) {
            return activity.getString(R.string.ota_progress_downloading,
                    pct, formatBytes(downloaded), formatBytes(total));
        }
        return activity.getString(R.string.ota_progress_running_unknown, formatBytes(downloaded));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private static String reasonText(int status, int reason) {
        if (status == DownloadManager.STATUS_PAUSED) {
            switch (reason) {
                case DownloadManager.PAUSED_WAITING_TO_RETRY:    return "waiting to retry";
                case DownloadManager.PAUSED_WAITING_FOR_NETWORK: return "waiting for network";
                case DownloadManager.PAUSED_QUEUED_FOR_WIFI:     return "queued for Wi-Fi";
                case DownloadManager.PAUSED_UNKNOWN:             return "paused by system";
                default:                                         return "reason " + reason;
            }
        }
        if (status == DownloadManager.STATUS_FAILED) {
            switch (reason) {
                case DownloadManager.ERROR_CANNOT_RESUME:       return "cannot resume";
                case DownloadManager.ERROR_DEVICE_NOT_FOUND:    return "storage device not found";
                case DownloadManager.ERROR_FILE_ALREADY_EXISTS: return "file already exists";
                case DownloadManager.ERROR_FILE_ERROR:          return "file error";
                case DownloadManager.ERROR_HTTP_DATA_ERROR:     return "HTTP data error";
                case DownloadManager.ERROR_INSUFFICIENT_SPACE:  return "insufficient storage";
                case DownloadManager.ERROR_TOO_MANY_REDIRECTS:  return "too many redirects";
                case DownloadManager.ERROR_UNHANDLED_HTTP_CODE: return "unexpected HTTP response";
                case DownloadManager.ERROR_UNKNOWN:             return "unknown error";
                default:                                        return "reason " + reason;
            }
        }
        return null;
    }
}
