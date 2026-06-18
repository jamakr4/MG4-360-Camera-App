package com.drivehub.kamera.ota;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class OtaReleaseAssets {

    private OtaReleaseAssets() {
    }

    static ApkAsset selectGitHubApkAsset(JSONArray assets) {
        if (assets == null || assets.length() == 0) return null;

        List<ApkAsset> candidates = new ArrayList<>();
        for (int i = 0; i < assets.length(); i += 1) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            String downloadUrl = asset.optString("browser_download_url", "");
            if (!name.toLowerCase(Locale.US).endsWith(".apk") || downloadUrl.isEmpty()) continue;
            candidates.add(new ApkAsset(name, downloadUrl));
        }
        return chooseBestApkAsset(candidates);
    }

    static ApkAsset selectGitLabApkAsset(JSONArray links) {
        if (links == null || links.length() == 0) return null;

        List<ApkAsset> candidates = new ArrayList<>();
        for (int i = 0; i < links.length(); i += 1) {
            JSONObject link = links.optJSONObject(i);
            if (link == null) continue;
            String name = link.optString("name", "");
            String downloadUrl = firstNonEmpty(
                    link.optString("direct_asset_url", ""),
                    link.optString("url", "")
            );
            if (!name.toLowerCase(Locale.US).endsWith(".apk") || downloadUrl.isEmpty()) continue;
            candidates.add(new ApkAsset(name, downloadUrl));
        }
        return chooseBestApkAsset(candidates);
    }

    static HashAsset selectGitHubHashAsset(JSONArray assets, String apkFileName) {
        if (assets == null || assets.length() == 0 || apkFileName == null || apkFileName.isEmpty()) return null;
        String expectedSidecarName = apkFileName + ".sha256";
        HashAsset fallback = null;
        for (int i = 0; i < assets.length(); i += 1) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            String downloadUrl = asset.optString("browser_download_url", "");
            if (downloadUrl.isEmpty()) continue;
            if (expectedSidecarName.equalsIgnoreCase(name)) {
                return new HashAsset(name, downloadUrl);
            }
            if ("SHA256SUMS".equalsIgnoreCase(name)) {
                fallback = new HashAsset(name, downloadUrl);
            }
        }
        return fallback;
    }

    static HashAsset selectGitLabHashAsset(JSONArray links, String apkFileName) {
        if (links == null || links.length() == 0 || apkFileName == null || apkFileName.isEmpty()) return null;
        String expectedSidecarName = apkFileName + ".sha256";
        HashAsset fallback = null;
        for (int i = 0; i < links.length(); i += 1) {
            JSONObject link = links.optJSONObject(i);
            if (link == null) continue;
            String name = link.optString("name", "");
            String downloadUrl = firstNonEmpty(
                    link.optString("direct_asset_url", ""),
                    link.optString("url", "")
            );
            if (downloadUrl.isEmpty()) continue;
            if (expectedSidecarName.equalsIgnoreCase(name)) {
                return new HashAsset(name, downloadUrl);
            }
            if ("SHA256SUMS".equalsIgnoreCase(name)) {
                fallback = new HashAsset(name, downloadUrl);
            }
        }
        return fallback;
    }

    static String parseExpectedSha256(String hashFileContent, String apkFileName) {
        if (hashFileContent == null) return null;
        String[] lines = hashFileContent.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length == 1 && isSha256(parts[0])) {
                return parts[0].toLowerCase(Locale.US);
            }
            if (parts.length >= 2 && isSha256(parts[0])) {
                String candidateName = parts[parts.length - 1].replace("*", "");
                if (apkFileName.equals(candidateName) || trimmed.endsWith(apkFileName)) {
                    return parts[0].toLowerCase(Locale.US);
                }
            }
        }
        return null;
    }

    static boolean isLikelyPrerelease(String... values) {
        for (String value : values) {
            if (value == null) continue;
            String lower = value.toLowerCase(Locale.US);
            if (lower.contains("beta") || lower.contains("alpha") || lower.contains("rc") || lower.contains("pre")) {
                return true;
            }
        }
        return false;
    }

    // Shared text cleanup so both release sources produce consistent, readable notes in the UI.
    static String normalizeReleaseNotes(String notes) {
        if (notes == null) return "";
        String normalized = notes.replace("\r\n", "\n").trim();
        String[] lines = normalized.split("\n");
        StringBuilder cleaned = new StringBuilder(normalized.length());
        boolean lastWasBlank = false;

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            line = stripMarkdownPrefix(line);
            line = line.replace("**", "").replace("__", "").replace("`", "").trim();

            if (line.isEmpty()) {
                if (!lastWasBlank && cleaned.length() > 0) {
                    cleaned.append("\n\n");
                }
                lastWasBlank = true;
                continue;
            }

            if (cleaned.length() > 0 && !lastWasBlank) {
                cleaned.append('\n');
            }
            cleaned.append(line);
            lastWasBlank = false;
        }
        return cleaned.toString().trim();
    }

    static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String stripMarkdownPrefix(String line) {
        if (line == null || line.isEmpty()) return "";

        String normalized = line.replaceFirst("^#{1,6}\\s*", "");
        normalized = normalized.replaceFirst("^>\\s*", "");

        if (normalized.matches("^[-*+]\\s+.*")) {
            return "\u2022 " + normalized.substring(2).trim();
        }
        if (normalized.matches("^\\d+\\.\\s+.*")) {
            return normalized;
        }
        return normalized.trim();
    }

    private static ApkAsset chooseBestApkAsset(List<ApkAsset> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;

        ApkAsset best = candidates.get(0);
        int bestScore = scoreAsset(best.name);
        for (int i = 1; i < candidates.size(); i += 1) {
            ApkAsset candidate = candidates.get(i);
            int score = scoreAsset(candidate.name);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static int scoreAsset(String name) {
        String lower = name.toLowerCase(Locale.US);
        int score = 0;
        if (lower.endsWith(".apk")) score += 10;
        if (lower.contains("release")) score += 5;
        if (lower.contains("arm64")) score += 3;
        if (lower.contains("universal")) score += 2;
        if (lower.contains("debug")) score -= 8;
        return score;
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("(?i)[a-f0-9]{64}");
    }

    static final class ApkAsset {
        final String name;
        final String downloadUrl;

        ApkAsset(String name, String downloadUrl) {
            this.name = name;
            this.downloadUrl = downloadUrl;
        }
    }

    static final class HashAsset {
        final String name;
        final String downloadUrl;

        HashAsset(String name, String downloadUrl) {
            this.name = name;
            this.downloadUrl = downloadUrl;
        }
    }
}
