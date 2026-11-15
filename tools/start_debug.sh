#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERIAL="${ANDROID_SERIAL:-}"
DEBUG_VIEW="curbside"
CAPTURE=""
REGION=""
INSTALL=false
BUILD=false
PACKAGE_NAME="com.drivehub.kamera"

usage() {
  cat <<'EOF'
Usage: tools/start_debug.sh [options]

Starts an installed debug activity. With multiple connected devices, asks which
one to use (or pass --serial). Does not start an emulator or import recordings.

  --view curbside|birdseye|wheels  Debug view (default: curbside)
  --serial SERIAL                 adb device/emulator; also uses ANDROID_SERIAL
  --region front|rear|side         Curbside framing (default: front)
  --capture EXPORT_ID             Already imported private capture (not wheels)
  --install                       Install the existing debug APK before starting
  --build                         Build and install the debug APK before starting
  -h, --help                      Show this help

Examples:
  tools/start_debug.sh
  tools/start_debug.sh --serial emulator-5554 --view birdseye
  tools/start_debug.sh --build --view curbside
EOF
}
die() { echo "Error: $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial|--view|--capture|--region)
      [[ $# -ge 2 && -n "$2" && "$2" != --* ]] || die "Missing value for $1"
      case "$1" in
        --serial) SERIAL="$2" ;;
        --view) DEBUG_VIEW="$2" ;;
        --capture) CAPTURE="$2" ;;
        --region) REGION="$2" ;;
      esac
      shift 2 ;;
    --install) INSTALL=true; shift ;;
    --build) BUILD=true; INSTALL=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown argument: $1 (see --help)" ;;
  esac
done

case "$DEBUG_VIEW" in
  curbside) COMPONENT="$PACKAGE_NAME/.birdseye.CurbsideDebugActivity" ;;
  birdseye) COMPONENT="$PACKAGE_NAME/.birdseye.BirdseyeDebugActivity" ;;
  wheels) COMPONENT="$PACKAGE_NAME/.dev.WheelRotationDebugActivity" ;;
  *) die "Unknown view '$DEBUG_VIEW'; choose curbside, birdseye or wheels" ;;
esac
if [[ -n "$CAPTURE" ]]; then
  [[ "$CAPTURE" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$ ]] || die "Invalid capture export ID"
  [[ "$DEBUG_VIEW" != wheels ]] || die "--capture requires curbside or birdseye"
fi

if [[ -n "$REGION" ]]; then
  [[ "$DEBUG_VIEW" == curbside ]] || die "--region requires --view curbside"
  case "$REGION" in front) REGION_ID=1 ;; rear) REGION_ID=2 ;; side) REGION_ID=0 ;; *) die "Choose front, rear or side for --region" ;; esac
fi

ADB_BIN="${ADB:-$(command -v adb || true)}"
if [[ -z "$ADB_BIN" ]]; then
  SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [[ -z "$SDK_DIR" && -f "$ROOT_DIR/local.properties" ]]; then
    SDK_DIR="$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | head -n 1 | tr -d '\r')"
  fi
  SDK_DIR="${SDK_DIR:-$HOME/Library/Android/sdk}"
  ADB_BIN="$SDK_DIR/platform-tools/adb"
fi
[[ -x "$ADB_BIN" ]] || die "adb not found; set ADB or ANDROID_SDK_ROOT"

DEVICES=()
DEVICE_LIST="$("$ADB_BIN" devices)"
while read -r id state rest; do
  if [[ "$state" == device ]]; then DEVICES+=("$id"); fi
done <<< "$DEVICE_LIST"
[[ ${#DEVICES[@]} -gt 0 ]] || die "No ready Android device. Start an emulator or connect/unlock a device; run adb devices for details."

if [[ -z "$SERIAL" ]]; then
  if [[ ${#DEVICES[@]} -eq 1 ]]; then
    SERIAL="${DEVICES[0]}"
  else
    "$ADB_BIN" devices -l
    [[ -t 0 ]] || die "Multiple devices: pass --serial SERIAL"
    PS3="Choose device: "
    select selected in "${DEVICES[@]}"; do
      if [[ -n "$selected" ]]; then SERIAL="$selected"; break; fi
      echo "Choose a number from the list." >&2
    done
    [[ -n "$SERIAL" ]] || die "No device selected"
  fi
fi
READY=false
for id in "${DEVICES[@]}"; do if [[ "$id" == "$SERIAL" ]]; then READY=true; fi; done
[[ "$READY" == true ]] || die "Device '$SERIAL' is not ready; run adb devices"

TARGET=("$ADB_BIN" -s "$SERIAL")
ANDROID_USER_ID="$("${TARGET[@]}" shell am get-current-user | tr -d '\r')"
[[ "$ANDROID_USER_ID" =~ ^[0-9]+$ ]] || die "Could not identify the foreground Android user"

if [[ "$BUILD" == true ]]; then
  source "$ROOT_DIR/tools/java_env.sh"
  configure_gradle_java
  (cd "$ROOT_DIR" && ./gradlew :app:assembleDebug)
fi
if [[ "$INSTALL" == true ]]; then
  APK_PATH="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
  [[ -f "$APK_PATH" ]] || die "Debug APK missing; use --build"
  # Never uninstall or erase private recordings to resolve an installation error.
  "${TARGET[@]}" install -r --user "$ANDROID_USER_ID" "$APK_PATH"
fi

RESOLVED="$("${TARGET[@]}" shell cmd package resolve-activity --brief --user "$ANDROID_USER_ID" -n "$COMPONENT")"
[[ "$RESOLVED" == *"$COMPONENT"* ]] || die "Debug activity unavailable. Use --build or --install; a release APK has no debug activities."

echo "Opening $DEBUG_VIEW on $SERIAL (Android user $ANDROID_USER_ID)..."
# Recreate an already open standard Activity so a new capture ID takes effect.
# No force-stop: background services and private app files remain intact.
START=(shell am start -W --user "$ANDROID_USER_ID" -f 0x14000000 -n "$COMPONENT")
if [[ -n "$REGION" ]]; then START+=(--ei curbside_region "$REGION_ID"); fi
if [[ -n "$CAPTURE" ]]; then START+=(--es camera_capture_set "$CAPTURE"); fi
RESULT="$("${TARGET[@]}" "${START[@]}")"
printf '%s\n' "$RESULT"
[[ "$RESULT" != *"Error:"* && "$RESULT" != *"Exception"* ]] || die "Activity could not be opened"
