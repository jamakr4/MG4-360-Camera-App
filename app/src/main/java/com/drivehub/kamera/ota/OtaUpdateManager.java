package com.drivehub.kamera.ota;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class OtaUpdateManager {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private OtaUpdateManager() {
    }

    interface CheckCallback {
        void onResult(UpdateInfo info);
    }

    static final class UpdateInfo {
        final boolean success;
        final boolean updateAvailable;
        final String currentVersion;
        final String latestVersion;
        final String releaseName;
        final String releaseNotes;
        final String downloadUrl;
        final String assetFileName;
        final String expectedSha256;
        final String message;
        final boolean prerelease;
        final boolean betaAllowed;
        final SourceStatus githubStatus;
        final SourceStatus gitlabStatus;
        final String resolvedSource;

        UpdateInfo(
                boolean success,
                boolean updateAvailable,
                String currentVersion,
                String latestVersion,
                String releaseName,
                String releaseNotes,
                String downloadUrl,
                String assetFileName,
                String expectedSha256,
                String message,
                boolean prerelease,
                boolean betaAllowed,
                SourceStatus githubStatus,
                SourceStatus gitlabStatus,
                String resolvedSource
        ) {
            this.success = success;
            this.updateAvailable = updateAvailable;
            this.currentVersion = currentVersion;
            this.latestVersion = latestVersion;
            this.releaseName = releaseName;
            this.releaseNotes = releaseNotes;
            this.downloadUrl = downloadUrl;
            this.assetFileName = assetFileName;
            this.expectedSha256 = expectedSha256;
            this.message = message;
            this.prerelease = prerelease;
            this.betaAllowed = betaAllowed;
            this.githubStatus = githubStatus;
            this.gitlabStatus = gitlabStatus;
            this.resolvedSource = resolvedSource;
        }

        UpdateInfo withStatuses(SourceStatus githubStatus, SourceStatus gitlabStatus, String resolvedSource) {
            return new UpdateInfo(
                    success,
                    updateAvailable,
                    currentVersion,
                    latestVersion,
                    releaseName,
                    releaseNotes,
                    downloadUrl,
                    assetFileName,
                    expectedSha256,
                    message,
                    prerelease,
                    betaAllowed,
                    githubStatus,
                    gitlabStatus,
                    resolvedSource
            );
        }
    }

    static final class SourceStatus {
        final String sourceName;
        final boolean available;
        final String detail;

        SourceStatus(String sourceName, boolean available, String detail) {
            this.sourceName = sourceName;
            this.available = available;
            this.detail = detail;
        }
    }

    interface VerifyCallback {
        void onResult(boolean success, String computedSha256, String message);
    }

    static void checkForUpdates(Context context, boolean allowBetaUpdates, CheckCallback callback) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            UpdateInfo info = OtaReleaseFetcher.fetchLatestRelease(appContext, allowBetaUpdates);
            MAIN_HANDLER.post(() -> callback.onResult(info));
        });
    }

    // Downloading remains here because the UI already talks to the Android DownloadManager via this facade.
    static long enqueueDownload(Context context, UpdateInfo info) {
        if (info == null || info.downloadUrl == null || info.downloadUrl.isEmpty()) {
            throw new IllegalArgumentException("No APK download URL available.");
        }

        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            throw new IllegalStateException("DownloadManager not available.");
        }

        String fileName = sanitizeFileName(
                (info.assetFileName == null || info.assetFileName.trim().isEmpty())
                        ? String.format(Locale.US, "mg4-360-cam-%s.apk", info.latestVersion)
                        : info.assetFileName
        );
        File targetFile = preparePublicDownloadFile(fileName);

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(info.downloadUrl))
                .setTitle("360 Cam Update")
                .setDescription("Downloading version " + info.latestVersion)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(targetFile))
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true);

        return dm.enqueue(request);
    }

    static void verifyDownloadedApk(Context context, long downloadId, Uri apkUri, UpdateInfo info, VerifyCallback callback) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            OtaApkVerifier.VerificationResult result =
                    OtaApkVerifier.verifyDownloadedApk(appContext, downloadId, apkUri, info);
            MAIN_HANDLER.post(() -> callback.onResult(result.success, result.computedSha256, result.message));
        });
    }

    private static String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static File preparePublicDownloadFile(String fileName) {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsDir == null) {
            throw new IllegalStateException("Public Downloads directory unavailable");
        }
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            throw new IllegalStateException("Could not create public Downloads directory");
        }
        File targetFile = new File(downloadsDir, fileName);
        if (targetFile.exists() && !targetFile.delete()) {
            throw new IllegalStateException("Could not replace existing OTA package");
        }
        return targetFile;
    }
}
