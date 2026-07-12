#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source "${ROOT_DIR}/tools/java_env.sh"
configure_gradle_java

SERIAL="${ANDROID_SERIAL:-emulator-5554}"
PACKAGE_NAME="com.drivehub.kamera"
MAIN_ACTIVITY="${PACKAGE_NAME}/.MainActivity"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/Users/jan/Library/Android/sdk}}"
DEFAULT_JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
DEVICE_WAIT_TIMEOUT_SECONDS="${DEVICE_WAIT_TIMEOUT_SECONDS:-20}"

usage() {
  echo "Usage: $0 [--serial emulator-5554]"
}

wait_for_target_device() {
  local deadline=$((SECONDS + DEVICE_WAIT_TIMEOUT_SECONDS))
  local state=""

  while (( SECONDS < deadline )); do
    state="$(adb -s "$SERIAL" get-state 2>/dev/null || true)"
    if [[ "$state" == "device" ]]; then
      return 0
    fi
    sleep 1
  done

  echo "Android device '${SERIAL}' did not become available within ${DEVICE_WAIT_TIMEOUT_SECONDS}s." >&2
  echo "Connected devices:" >&2
  adb devices >&2 || true
  echo "Start the matching emulator or pass --serial <serial>." >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      SERIAL="${2:?Missing serial value}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ ! -f local.properties ]]; then
  echo "sdk.dir=${SDK_DIR}" > local.properties
fi

if [[ -z "${JAVA_HOME:-}" && -d "${DEFAULT_JAVA_HOME}" ]]; then
  export JAVA_HOME="${DEFAULT_JAVA_HOME}"
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="${JAVA_HOME}/bin:${PATH}"
  echo "Using JAVA_HOME=${JAVA_HOME}"
fi

echo "Checking Android device: ${SERIAL}"
wait_for_target_device

echo "Building debug APK..."
./gradlew --no-daemon :app:assembleDebug

echo "Installing ${APK_PATH}..."
if ! INSTALL_OUTPUT="$(adb -s "$SERIAL" install -r "$APK_PATH" 2>&1)"; then
  echo "$INSTALL_OUTPUT" >&2
  if [[ "$INSTALL_OUTPUT" == *"INSTALL_FAILED_UPDATE_INCOMPATIBLE"* ]]; then
    echo "Existing ${PACKAGE_NAME} install has a different signature; uninstalling from simulator and retrying..."
    adb -s "$SERIAL" uninstall "$PACKAGE_NAME" >/dev/null || true
    adb -s "$SERIAL" install -r "$APK_PATH"
  else
    exit 1
  fi
else
  echo "$INSTALL_OUTPUT"
fi

echo "Stopping previous app instance..."
adb -s "$SERIAL" shell am force-stop "$PACKAGE_NAME"

echo "Launching ${MAIN_ACTIVITY}..."
adb -s "$SERIAL" shell am start -n "$MAIN_ACTIVITY" >/dev/null

echo "Done."
