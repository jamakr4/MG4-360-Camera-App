#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SERIAL="${ANDROID_SERIAL:-emulator-5554}"
PACKAGE_NAME="com.drivehub.kamera"
MAIN_ACTIVITY="${PACKAGE_NAME}/.MainActivity"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/Users/jan/Library/Android/sdk}}"
DEFAULT_JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
OPEN_SETTINGS=0
SCREENSHOT_PATH="${SCREENSHOT_PATH:-/tmp/drivehub_settings_open.png}"

usage() {
  echo "Usage: $0 [--serial emulator-5554] [--open-settings] [--screenshot /tmp/file.png]"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      SERIAL="${2:?Missing serial value}"
      shift 2
      ;;
    --open-settings)
      OPEN_SETTINGS=1
      shift
      ;;
    --screenshot)
      SCREENSHOT_PATH="${2:?Missing screenshot path}"
      OPEN_SETTINGS=1
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

echo "Building debug APK..."
./gradlew --no-daemon :app:assembleDebug

echo "Waiting for Android device: ${SERIAL}"
adb -s "$SERIAL" wait-for-device

echo "Installing ${APK_PATH}..."
adb -s "$SERIAL" install -r "$APK_PATH"

echo "Stopping previous app instance..."
adb -s "$SERIAL" shell am force-stop "$PACKAGE_NAME"

echo "Launching ${MAIN_ACTIVITY}..."
adb -s "$SERIAL" shell am start -n "$MAIN_ACTIVITY" >/dev/null

if [[ "$OPEN_SETTINGS" -eq 1 ]]; then
  echo "Opening settings dialog and capturing screenshot..."
  sleep 1
  adb -s "$SERIAL" shell input tap 50 575
  sleep 1
  adb -s "$SERIAL" shell screencap -p /sdcard/drivehub_settings_open.png
  adb -s "$SERIAL" pull /sdcard/drivehub_settings_open.png "$SCREENSHOT_PATH" >/dev/null
  echo "Screenshot: ${SCREENSHOT_PATH}"
fi

echo "Done."
