package com.drivehub.kamera.ota;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OtaReleaseAssetsTest {
    @Test public void acceptsOnlyThisProjectsReleaseAssets() {
        assertTrue(OtaReleaseAssets.isTrustedAssetUrl(
                "https://github.com/jamakr4/MG4-360-Camera-App/releases/download/v0.9.5/MG4-360-Camera-App-v0.9.5-release.apk"));
        assertTrue(OtaReleaseAssets.isTrustedAssetUrl(
                "https://github.com/jamakr4/MG4-360-Camera-App/releases/download/v0.9.5/MG4-360-Camera-App-v0.9.5-release.apk.sha256"));
        assertTrue(OtaReleaseAssets.isTrustedAssetUrl(
                "https://gitlab.com/jamakr4/mg4-360-camera-app/-/package_files/generic/v0.9.5/1.0.0/app.apk"));
    }

    @Test public void refusesEveryOtherOrigin() {
        String[] refused = {
                null, "",
                "http://github.com/jamakr4/MG4-360-Camera-App/releases/download/v1/a.apk",
                "https://github.com/someone/MG4-360-Camera-App/releases/download/v1/a.apk",
                "https://github.com/jamakr4/MG4-360-Camera-App-fork/releases/download/v1/a.apk",
                "https://github.com/jamakr4/MG4-360-Camera-App/raw/main/a.apk",
                "https://github.com.attacker.example/jamakr4/MG4-360-Camera-App/releases/download/v1/a.apk",
                "https://user@github.com/jamakr4/MG4-360-Camera-App/releases/download/v1/a.apk",
                "https://github.com:8443/jamakr4/MG4-360-Camera-App/releases/download/v1/a.apk",
                "https://github.com/jamakr4/MG4-360-Camera-App/releases/download/../../../x/a.apk",
                "https://gitlab.com/someone/mg4-360-camera-app/-/package_files/a.apk",
                "https://example.com/a.apk",
                "file:///sdcard/Download/a.apk",
        };
        for (String url : refused) assertFalse(String.valueOf(url), OtaReleaseAssets.isTrustedAssetUrl(url));
    }
}
