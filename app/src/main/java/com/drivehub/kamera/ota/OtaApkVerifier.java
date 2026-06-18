package com.drivehub.kamera.ota;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

final class OtaApkVerifier {

    private static final int OPEN_RETRY_COUNT = 5;
    private static final long OPEN_RETRY_DELAY_MS = 300L;

    private OtaApkVerifier() {
    }

    // Verification is intentionally isolated so download integrity stays testable without UI code.
    static VerificationResult verifyDownloadedApk(
            Context context,
            long downloadId,
            Uri apkUri,
            OtaUpdateManager.UpdateInfo info
    ) {
        boolean success;
        String computed = "";
        String message;
        try (InputStream in = openApkStream(context, downloadId, apkUri)) {
            if (info == null || info.expectedSha256 == null || info.expectedSha256.isEmpty()) {
                throw new IllegalStateException("Missing expected SHA-256");
            }
            computed = computeSha256(in);
            success = computed.equalsIgnoreCase(info.expectedSha256);
            message = success ? "OK" : "SHA256 mismatch";
        } catch (Exception e) {
            success = false;
            String detail = e.getMessage();
            message = (detail == null || detail.trim().isEmpty())
                    ? e.getClass().getSimpleName()
                    : e.getClass().getSimpleName() + ": " + detail;
        }
        return new VerificationResult(success, computed, message);
    }

    private static InputStream openApkStream(Context context, long downloadId, Uri apkUri) throws Exception {
        if (context == null) {
            throw new IllegalStateException("Context missing");
        }
        Exception lastError = null;
        for (int attempt = 0; attempt < OPEN_RETRY_COUNT; attempt += 1) {
            try {
                InputStream fromDownloadManager = openDownloadedFileStream(context, downloadId);
                if (fromDownloadManager != null) {
                    return fromDownloadManager;
                }
                if (apkUri == null) {
                    throw new IllegalStateException("Downloaded APK not found");
                }
                String scheme = apkUri.getScheme();
                if ("file".equalsIgnoreCase(scheme)) {
                    File apkFile = new File(apkUri.getPath());
                    if (!apkFile.exists()) {
                        throw new IllegalStateException("Downloaded APK not found");
                    }
                    return new FileInputStream(apkFile);
                }

                InputStream stream = context.getContentResolver().openInputStream(apkUri);
                if (stream != null) {
                    return stream;
                }
                throw new IllegalStateException("Downloaded APK stream unavailable");
            } catch (Exception e) {
                lastError = e;
                if (attempt == OPEN_RETRY_COUNT - 1) {
                    break;
                }
                try {
                    Thread.sleep(OPEN_RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Verification interrupted", interrupted);
                }
            }
        }
        throw lastError != null ? lastError : new IllegalStateException("Downloaded APK not found");
    }

    private static InputStream openDownloadedFileStream(Context context, long downloadId) {
        if (downloadId <= 0L) return null;
        try {
            android.app.DownloadManager dm =
                    (android.app.DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return null;
            ParcelFileDescriptor pfd = dm.openDownloadedFile(downloadId);
            if (pfd == null) return null;
            return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String computeSha256(InputStream in) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    static final class VerificationResult {
        final boolean success;
        final String computedSha256;
        final String message;

        VerificationResult(boolean success, String computedSha256, String message) {
            this.success = success;
            this.computedSha256 = computedSha256;
            this.message = message;
        }
    }
}
