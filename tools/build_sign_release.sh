#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

source "${ROOT_DIR}/tools/java_env.sh"
configure_gradle_java

VERSION_NAME="$(sed -n 's/.*versionName "\(.*\)".*/\1/p' app/build.gradle | head -n 1)"
if [[ -z "${VERSION_NAME}" ]]; then
  echo "Could not determine versionName from app/build.gradle" >&2
  exit 1
fi

APKSIGNER="$(ls "$HOME"/Library/Android/sdk/build-tools/*/apksigner 2>/dev/null | tail -n 1 || true)"
if [[ -z "${APKSIGNER}" ]]; then
  echo "Could not find apksigner in ~/Library/Android/sdk/build-tools" >&2
  exit 1
fi

UNSIGNED_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
RELEASE_DIR="app/build/outputs/apk/release"
ARTIFACT_BASENAME="MG4-360-Camera-App-v${VERSION_NAME}-release.apk"
SIGNED_APK="${RELEASE_DIR}/${ARTIFACT_BASENAME}"
SHA_FILE="${SIGNED_APK}.sha256"

./gradlew --no-daemon clean assembleRelease

"${APKSIGNER}" sign \
  --key tools/platform.pk8 \
  --cert tools/platform.x509.pem \
  --out "${SIGNED_APK}" \
  "${UNSIGNED_APK}"

SHA256="$(shasum -a 256 "${SIGNED_APK}" | awk '{print $1}')"
printf '%s  %s\n' "${SHA256}" "${ARTIFACT_BASENAME}" > "${SHA_FILE}"

echo "Created release artifacts:"
echo "  APK: ${SIGNED_APK}"
echo "  SHA: ${SHA_FILE}"
echo
# The GitLab mirror job copies release assets from GitHub, so uploading the SHA sidecar
# here is required for both sources to offer a verifiable OTA download.
echo "Upload both files as GitHub release assets:"
echo "  - ${ARTIFACT_BASENAME}"
echo "  - ${ARTIFACT_BASENAME}.sha256"
