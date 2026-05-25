package com.drivehub.kamera;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class OtaReleaseFetcher {

    private static final String SOURCE_GITHUB = "GitHub";
    private static final String SOURCE_GITLAB = "GitLab";
    private static final String GITHUB_RELEASES_URL =
            "https://api.github.com/repos/jamakr4/MG4-360-Camera-App/releases?per_page=20";
    private static final String GITLAB_RELEASES_URL =
            "https://gitlab.com/api/v4/projects/jamakr4%2Fmg4-360-camera-app/releases?per_page=20";

    private OtaReleaseFetcher() {
    }

    // Pull both sources every time so the UI can show source health even when the primary source works.
    static OtaUpdateManager.UpdateInfo fetchLatestRelease(Context context, boolean allowBetaUpdates) {
        SourceResult github = fetchFromGitHub(context, allowBetaUpdates);
        SourceResult gitlab = fetchFromGitLab(context, allowBetaUpdates);

        SourceResult selected;
        if (github.hasUsableRelease()) {
            selected = github;
        } else if (gitlab.hasUsableRelease()) {
            selected = gitlab;
        } else if (github.info != null) {
            selected = github;
        } else {
            selected = gitlab;
        }

        if (selected == null || selected.info == null) {
            return new OtaUpdateManager.UpdateInfo(
                    false,
                    false,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_NAME,
                    null,
                    null,
                    null,
                    null,
                    null,
                    context.getString(R.string.ota_update_channel_error),
                    false,
                    allowBetaUpdates,
                    github.status,
                    gitlab.status,
                    null
            );
        }

        return selected.info.withStatuses(github.status, gitlab.status, selected.sourceName);
    }

    private static SourceResult fetchFromGitHub(Context context, boolean allowBetaUpdates) {
        try {
            JSONArray releases = readJsonArray(GITHUB_RELEASES_URL, "application/vnd.github+json");
            JSONObject release = selectGitHubRelease(releases, allowBetaUpdates);
            if (release == null) {
                return SourceResult.failure(
                        SOURCE_GITHUB,
                        context.getString(R.string.ota_error_no_release),
                        allowBetaUpdates
                );
            }
            return SourceResult.success(
                    SOURCE_GITHUB,
                    buildUpdateInfoFromGitHubRelease(context, release, allowBetaUpdates)
            );
        } catch (Exception e) {
            return SourceResult.failure(SOURCE_GITHUB, e.getClass().getSimpleName(), allowBetaUpdates);
        }
    }

    private static SourceResult fetchFromGitLab(Context context, boolean allowBetaUpdates) {
        try {
            JSONArray releases = readJsonArray(GITLAB_RELEASES_URL, "application/json");
            JSONObject release = selectGitLabRelease(releases, allowBetaUpdates);
            if (release == null) {
                return SourceResult.failure(
                        SOURCE_GITLAB,
                        context.getString(R.string.ota_error_no_release),
                        allowBetaUpdates
                );
            }
            return SourceResult.success(
                    SOURCE_GITLAB,
                    buildUpdateInfoFromGitLabRelease(context, release, allowBetaUpdates)
            );
        } catch (Exception e) {
            return SourceResult.failure(SOURCE_GITLAB, e.getClass().getSimpleName(), allowBetaUpdates);
        }
    }

    private static OtaUpdateManager.UpdateInfo buildUpdateInfoFromGitHubRelease(
            Context context,
            JSONObject release,
            boolean allowBetaUpdates
    ) throws Exception {
        String latestVersion = normalizeVersion(
                OtaReleaseAssets.firstNonEmpty(
                        release.optString("tag_name", null),
                        release.optString("name", null),
                        BuildConfig.VERSION_NAME
                )
        );
        String releaseName = OtaReleaseAssets.firstNonEmpty(release.optString("name", null), latestVersion);
        String releaseNotes = OtaReleaseAssets.firstNonEmpty(release.optString("body", null), "");
        boolean prerelease = release.optBoolean("prerelease", false);

        OtaReleaseAssets.ApkAsset apkAsset = OtaReleaseAssets.selectGitHubApkAsset(release.optJSONArray("assets"));
        if (apkAsset == null) {
            return unavailableReleaseInfo(
                    context,
                    latestVersion,
                    releaseName,
                    releaseNotes,
                    prerelease,
                    allowBetaUpdates,
                    R.string.ota_error_no_apk
            );
        }

        boolean updateAvailable = compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0;
        String expectedSha256 = null;
        if (updateAvailable) {
            OtaReleaseAssets.HashAsset hashAsset =
                    OtaReleaseAssets.selectGitHubHashAsset(release.optJSONArray("assets"), apkAsset.name);
            if (hashAsset == null) {
                return missingHashInfo(
                        context,
                        latestVersion,
                        releaseName,
                        releaseNotes,
                        apkAsset,
                        prerelease,
                        allowBetaUpdates,
                        R.string.ota_error_no_hash
                );
            }
            String hashFileContent = readUrl(hashAsset.downloadUrl, "text/plain, application/octet-stream");
            expectedSha256 = OtaReleaseAssets.parseExpectedSha256(hashFileContent, apkAsset.name);
            if (expectedSha256 == null || expectedSha256.isEmpty()) {
                return missingHashInfo(
                        context,
                        latestVersion,
                        releaseName,
                        releaseNotes,
                        apkAsset,
                        prerelease,
                        allowBetaUpdates,
                        R.string.ota_error_invalid_hash
                );
            }
        }

        return buildSuccessInfo(
                context,
                latestVersion,
                releaseName,
                releaseNotes,
                apkAsset,
                expectedSha256,
                prerelease,
                allowBetaUpdates
        );
    }

    private static OtaUpdateManager.UpdateInfo buildUpdateInfoFromGitLabRelease(
            Context context,
            JSONObject release,
            boolean allowBetaUpdates
    ) throws Exception {
        String latestVersion = normalizeVersion(
                OtaReleaseAssets.firstNonEmpty(
                        release.optString("tag_name", null),
                        release.optString("name", null),
                        BuildConfig.VERSION_NAME
                )
        );
        String releaseName = OtaReleaseAssets.firstNonEmpty(release.optString("name", null), latestVersion);
        String releaseNotes = OtaReleaseAssets.firstNonEmpty(release.optString("description", null), "");
        boolean prerelease = OtaReleaseAssets.isLikelyPrerelease(
                release.optString("tag_name", null),
                release.optString("name", null)
        );

        JSONArray links = release.optJSONObject("assets") != null
                ? release.optJSONObject("assets").optJSONArray("links")
                : null;
        OtaReleaseAssets.ApkAsset apkAsset = OtaReleaseAssets.selectGitLabApkAsset(links);
        if (apkAsset == null) {
            return unavailableReleaseInfo(
                    context,
                    latestVersion,
                    releaseName,
                    releaseNotes,
                    prerelease,
                    allowBetaUpdates,
                    R.string.ota_error_no_apk
            );
        }

        boolean updateAvailable = compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0;
        String expectedSha256 = null;
        if (updateAvailable) {
            OtaReleaseAssets.HashAsset hashAsset = OtaReleaseAssets.selectGitLabHashAsset(links, apkAsset.name);
            if (hashAsset == null) {
                return missingHashInfo(
                        context,
                        latestVersion,
                        releaseName,
                        releaseNotes,
                        apkAsset,
                        prerelease,
                        allowBetaUpdates,
                        R.string.ota_error_no_hash
                );
            }
            String hashFileContent = readUrl(hashAsset.downloadUrl, "text/plain, application/octet-stream");
            expectedSha256 = OtaReleaseAssets.parseExpectedSha256(hashFileContent, apkAsset.name);
            if (expectedSha256 == null || expectedSha256.isEmpty()) {
                return missingHashInfo(
                        context,
                        latestVersion,
                        releaseName,
                        releaseNotes,
                        apkAsset,
                        prerelease,
                        allowBetaUpdates,
                        R.string.ota_error_invalid_hash
                );
            }
        }

        return buildSuccessInfo(
                context,
                latestVersion,
                releaseName,
                releaseNotes,
                apkAsset,
                expectedSha256,
                prerelease,
                allowBetaUpdates
        );
    }

    private static OtaUpdateManager.UpdateInfo unavailableReleaseInfo(
            Context context,
            String latestVersion,
            String releaseName,
            String releaseNotes,
            boolean prerelease,
            boolean allowBetaUpdates,
            int messageRes
    ) {
        return new OtaUpdateManager.UpdateInfo(
                false,
                false,
                BuildConfig.VERSION_NAME,
                latestVersion,
                releaseName,
                releaseNotes,
                null,
                null,
                null,
                context.getString(messageRes),
                prerelease,
                allowBetaUpdates,
                null,
                null,
                null
        );
    }

    private static OtaUpdateManager.UpdateInfo missingHashInfo(
            Context context,
            String latestVersion,
            String releaseName,
            String releaseNotes,
            OtaReleaseAssets.ApkAsset apkAsset,
            boolean prerelease,
            boolean allowBetaUpdates,
            int messageRes
    ) {
        // Keep the update visible in the UI, but OtaController.maybeStartDownload() will block
        // the install flow until a valid hash file is published alongside the mirrored APK.
        return new OtaUpdateManager.UpdateInfo(
                true,
                true,
                BuildConfig.VERSION_NAME,
                latestVersion,
                releaseName,
                releaseNotes,
                apkAsset.downloadUrl,
                apkAsset.name,
                null,
                context.getString(messageRes),
                prerelease,
                allowBetaUpdates,
                null,
                null,
                null
        );
    }

    private static OtaUpdateManager.UpdateInfo buildSuccessInfo(
            Context context,
            String latestVersion,
            String releaseName,
            String releaseNotes,
            OtaReleaseAssets.ApkAsset apkAsset,
            String expectedSha256,
            boolean prerelease,
            boolean allowBetaUpdates
    ) {
        boolean updateAvailable = compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0;
        return new OtaUpdateManager.UpdateInfo(
                true,
                updateAvailable,
                BuildConfig.VERSION_NAME,
                latestVersion,
                releaseName,
                releaseNotes,
                apkAsset.downloadUrl,
                apkAsset.name,
                expectedSha256,
                updateAvailable
                        ? context.getString(R.string.ota_status_update_available, latestVersion)
                        : context.getString(R.string.ota_status_up_to_date),
                prerelease,
                allowBetaUpdates,
                null,
                null,
                null
        );
    }

    private static JSONArray readJsonArray(String url, String accept) throws Exception {
        return new JSONArray(readUrl(url, accept));
    }

    private static String readUrl(String url, String accept) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setRequestProperty("Accept", accept);
            connection.setRequestProperty("User-Agent", "MG4-360-Camera-App/" + BuildConfig.VERSION_NAME);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("HTTP " + status);
            }
            return readFully(connection.getInputStream());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static JSONObject selectGitHubRelease(JSONArray releases, boolean allowBetaUpdates) {
        if (releases == null || releases.length() == 0) return null;

        JSONObject best = null;
        String bestVersion = null;
        for (int i = 0; i < releases.length(); i += 1) {
            JSONObject candidate = releases.optJSONObject(i);
            if (candidate == null || candidate.optBoolean("draft", false)) continue;
            boolean prerelease = candidate.optBoolean("prerelease", false);
            if (prerelease && !allowBetaUpdates) continue;

            String candidateVersion = normalizeVersion(OtaReleaseAssets.firstNonEmpty(
                    candidate.optString("tag_name", null),
                    candidate.optString("name", null),
                    ""
            ));
            if (candidateVersion.isEmpty()) continue;

            if (best == null) {
                best = candidate;
                bestVersion = candidateVersion;
                continue;
            }

            int compare = compareVersions(candidateVersion, bestVersion);
            if (compare > 0 || (compare == 0 && !prerelease && best.optBoolean("prerelease", false))) {
                best = candidate;
                bestVersion = candidateVersion;
            }
        }
        return best;
    }

    private static JSONObject selectGitLabRelease(JSONArray releases, boolean allowBetaUpdates) {
        if (releases == null || releases.length() == 0) return null;

        JSONObject best = null;
        String bestVersion = null;
        boolean bestPrerelease = false;
        for (int i = 0; i < releases.length(); i += 1) {
            JSONObject candidate = releases.optJSONObject(i);
            if (candidate == null) continue;

            boolean prerelease = OtaReleaseAssets.isLikelyPrerelease(
                    candidate.optString("tag_name", null),
                    candidate.optString("name", null)
            );
            if (prerelease && !allowBetaUpdates) continue;

            String candidateVersion = normalizeVersion(OtaReleaseAssets.firstNonEmpty(
                    candidate.optString("tag_name", null),
                    candidate.optString("name", null),
                    ""
            ));
            if (candidateVersion.isEmpty()) continue;

            if (best == null) {
                best = candidate;
                bestVersion = candidateVersion;
                bestPrerelease = prerelease;
                continue;
            }

            int compare = compareVersions(candidateVersion, bestVersion);
            if (compare > 0 || (compare == 0 && !prerelease && bestPrerelease)) {
                best = candidate;
                bestVersion = candidateVersion;
                bestPrerelease = prerelease;
            }
        }
        return best;
    }

    private static String readFully(InputStream inputStream) throws Exception {
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String normalizeVersion(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        while (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1);
        }
        while (!trimmed.isEmpty() && !Character.isDigit(trimmed.charAt(0))) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }

    private static int compareVersions(String leftRaw, String rightRaw) {
        String left = normalizeVersion(leftRaw);
        String right = normalizeVersion(rightRaw);

        String[] leftParts = left.split("[^0-9]+");
        String[] rightParts = right.split("[^0-9]+");
        int max = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < max; i += 1) {
            int l = parsePart(leftParts, i);
            int r = parsePart(rightParts, i);
            if (l != r) return Integer.compare(l, r);
        }
        return 0;
    }

    private static int parsePart(String[] parts, int index) {
        if (index >= parts.length) return 0;
        String part = parts[index];
        if (part == null || part.isEmpty()) return 0;
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static final class SourceResult {
        final String sourceName;
        final OtaUpdateManager.SourceStatus status;
        final OtaUpdateManager.UpdateInfo info;

        SourceResult(String sourceName, OtaUpdateManager.SourceStatus status, OtaUpdateManager.UpdateInfo info) {
            this.sourceName = sourceName;
            this.status = status;
            this.info = info;
        }

        static SourceResult success(String sourceName, OtaUpdateManager.UpdateInfo info) {
            return new SourceResult(
                    sourceName,
                    new OtaUpdateManager.SourceStatus(
                            sourceName,
                            info != null && info.downloadUrl != null && !info.downloadUrl.isEmpty(),
                            null
                    ),
                    info
            );
        }

        static SourceResult failure(String sourceName, String detail, boolean allowBetaUpdates) {
            return new SourceResult(
                    sourceName,
                    new OtaUpdateManager.SourceStatus(sourceName, false, detail),
                    new OtaUpdateManager.UpdateInfo(
                            false,
                            false,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_NAME,
                            null,
                            null,
                            null,
                            null,
                            null,
                            detail,
                            false,
                            allowBetaUpdates,
                            null,
                            null,
                            null
                    )
            );
        }

        boolean hasUsableRelease() {
            return info != null && info.success && info.downloadUrl != null && !info.downloadUrl.isEmpty();
        }
    }
}
