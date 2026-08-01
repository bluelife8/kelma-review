#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Build, install, and launch Kelma Review in an Android emulator.

Usage:
  ./scripts/run-android-emulator.sh [AVD name] [Gradle options]

Examples:
  ./scripts/run-android-emulator.sh
  ./scripts/run-android-emulator.sh KelmaPixel
  ./scripts/run-android-emulator.sh KelmaPixel --offline

Environment:
  ANDROID_EMULATOR_DEVICE      Default AVD when no name is supplied.
  ANDROID_SERIAL               Reuse a specific running emulator serial.
  ANDROID_EMULATOR_HEADLESS=1  Start a new emulator without a window/audio.
  ANDROID_EMULATOR_COLD_BOOT=1 Start a new emulator without loading a snapshot.
  ANDROID_SHOW_SOFT_KEYBOARD=1 Show the Android keyboard instead of using only
                               the host computer keyboard (default: 0).
  ANDROID_EMULATOR_BUILD_ONLY=1
                               Build the APK without boot/install/launch.
  ANDROID_EMULATOR_ARGS        Additional arguments for a newly started emulator.
  ANDROID_BOOT_TIMEOUT_SECONDS Boot timeout (default: 300).
  ANDROID_HOME/ANDROID_SDK_ROOT
                               Android SDK location overrides.

The script enables the AVD hardware-keyboard device so host keystrokes reach
focused fields. It cold-restarts once when that setting changes. Installation
uses `adb install -r`, so app data and credentials are preserved; it never wipes
an AVD.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_SDK=""
if [[ -f "$ROOT_DIR/local.properties" ]]; then
  LOCAL_SDK="$(awk -F= '$1 == "sdk.dir" { sub(/^[^=]*=/, ""); print; exit }' "$ROOT_DIR/local.properties")"
fi
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$LOCAL_SDK}}"

if [[ -z "$SDK_ROOT" ]]; then
  echo "error: Android SDK not found; set ANDROID_SDK_ROOT or sdk.dir in local.properties" >&2
  exit 1
fi

ADB="${ANDROID_ADB:-$SDK_ROOT/platform-tools/adb}"
EMULATOR="${ANDROID_EMULATOR:-$SDK_ROOT/emulator/emulator}"
for tool in "$ADB" "$EMULATOR"; do
  if [[ ! -x "$tool" ]]; then
    echo "error: required Android SDK tool is not executable: $tool" >&2
    exit 1
  fi
done

AVD="${ANDROID_EMULATOR_DEVICE:-}"
if [[ -n "${1:-}" && "${1:-}" != --* ]]; then
  AVD="$1"
  shift
fi
if [[ -z "$AVD" ]]; then
  while IFS= read -r candidate; do
    if [[ -n "$candidate" ]]; then
      AVD="$candidate"
      break
    fi
  done < <("$EMULATOR" -list-avds)
fi
if [[ -z "$AVD" ]]; then
  echo "error: no Android Virtual Device exists; create one with Android Studio Device Manager" >&2
  exit 1
fi
if ! "$EMULATOR" -list-avds | grep -Fxq "$AVD"; then
  echo "error: Android Virtual Device not found: $AVD" >&2
  echo "Available AVDs:" >&2
  "$EMULATOR" -list-avds >&2
  exit 1
fi

FORCE_COLD_BOOT=0
AVD_DESCRIPTOR="$HOME/.android/avd/$AVD.ini"
AVD_DIRECTORY="$HOME/.android/avd/$AVD.avd"
if [[ -f "$AVD_DESCRIPTOR" ]]; then
  DESCRIBED_PATH="$(awk -F= '$1 == "path" { sub(/^[^=]*=/, ""); print; exit }' "$AVD_DESCRIPTOR")"
  if [[ -n "$DESCRIBED_PATH" ]]; then
    AVD_DIRECTORY="$DESCRIBED_PATH"
  fi
fi
AVD_CONFIG="$AVD_DIRECTORY/config.ini"
if [[ ! -f "$AVD_CONFIG" ]]; then
  echo "error: AVD configuration not found: $AVD_CONFIG" >&2
  exit 1
fi
if ! grep -Eq '^hw\.keyboard[[:space:]]*=[[:space:]]*yes[[:space:]]*$' "$AVD_CONFIG"; then
  TEMP_CONFIG="$(mktemp "$AVD_CONFIG.XXXXXX")"
  awk '
    BEGIN { found = 0 }
    /^hw\.keyboard[[:space:]]*=/ { print "hw.keyboard = yes"; found = 1; next }
    { print }
    END { if (!found) print "hw.keyboard = yes" }
  ' "$AVD_CONFIG" >"$TEMP_CONFIG"
  mv "$TEMP_CONFIG" "$AVD_CONFIG"
  FORCE_COLD_BOOT=1
  echo "Enabled host keyboard input for AVD: $AVD"
fi

PACKAGE="tech.kelma.app"
ACTIVITY="$PACKAGE/.MainActivity"
APK="$ROOT_DIR/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
BUILD_ONLY="${ANDROID_EMULATOR_BUILD_ONLY:-0}"

cd "$ROOT_DIR"
echo "Building Kelma Review Android debug APK"
if (( $# > 0 )); then
  ./gradlew :androidApp:assembleDebug "$@"
else
  # Bash 3.2 treats an empty array expansion as unbound under `set -u`.
  ./gradlew :androidApp:assembleDebug
fi

if [[ ! -f "$APK" ]]; then
  echo "error: expected debug APK was not produced at $APK" >&2
  exit 1
fi
if [[ "$BUILD_ONLY" == "1" ]]; then
  echo "Built Android debug APK: $APK"
  exit 0
fi

"$ADB" start-server >/dev/null

avd_for_serial() {
  "$ADB" -s "$1" emu avd name 2>/dev/null | tr -d '\r' | head -n 1
}

SERIAL="${ANDROID_SERIAL:-}"
if [[ -n "$SERIAL" && "$SERIAL" != emulator-* ]]; then
  echo "error: ANDROID_SERIAL must identify an emulator (emulator-*), not a physical device" >&2
  exit 1
fi

if [[ -z "$SERIAL" ]]; then
  while read -r candidate state _; do
    if [[ "$candidate" == emulator-* && "$state" == "device" ]]; then
      if [[ "$(avd_for_serial "$candidate")" == "$AVD" ]]; then
        SERIAL="$candidate"
        break
      fi
    fi
  done < <("$ADB" devices)
fi

if [[ -n "$SERIAL" ]]; then
  GUEST_CONFIGURATION="$(
    "$ADB" -s "$SERIAL" shell dumpsys activity 2>/dev/null |
      awk '/mGlobalConfiguration/ { print; exit }' || true
  )"
  if [[ "$FORCE_COLD_BOOT" == "1" || "$GUEST_CONFIGURATION" == *"-keyb"* ]]; then
    echo "Restarting $AVD so Android detects the host hardware keyboard"
    OLD_SERIAL="$SERIAL"
    "$ADB" -s "$OLD_SERIAL" emu kill >/dev/null 2>&1 || true
    for _ in $(seq 1 30); do
      if ! "$ADB" devices | awk 'NR > 1 { print $1 }' | grep -Fxq "$OLD_SERIAL"; then
        break
      fi
      sleep 1
    done
    SERIAL=""
    FORCE_COLD_BOOT=1
  fi
fi

if [[ -z "$SERIAL" ]]; then
  PORT=5554
  while "$ADB" devices | awk 'NR > 1 { print $1 }' | grep -Fxq "emulator-$PORT"; do
    PORT=$((PORT + 2))
    if (( PORT > 5682 )); then
      echo "error: no free Android emulator console port is available" >&2
      exit 1
    fi
  done
  SERIAL="emulator-$PORT"
  LOG_PATH="$ROOT_DIR/build/android-emulator-${AVD//[^A-Za-z0-9_.-]/_}.log"
  mkdir -p "$(dirname "$LOG_PATH")"

  EMULATOR_ARGS=(-avd "$AVD" -port "$PORT")
  if [[ "${ANDROID_EMULATOR_HEADLESS:-0}" == "1" ]]; then
    EMULATOR_ARGS+=(-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect)
  fi
  if [[ "${ANDROID_EMULATOR_COLD_BOOT:-0}" == "1" || "$FORCE_COLD_BOOT" == "1" ]]; then
    EMULATOR_ARGS+=(-no-snapshot-load)
  fi
  if [[ -n "${ANDROID_EMULATOR_ARGS:-}" ]]; then
    # Intentional shell-style splitting for SDK emulator flags supplied by the caller.
    read -r -a EXTRA_EMULATOR_ARGS <<<"$ANDROID_EMULATOR_ARGS"
    EMULATOR_ARGS+=("${EXTRA_EMULATOR_ARGS[@]}")
  fi

  echo "Starting Android emulator: $AVD ($SERIAL)"
  echo "Emulator log: $LOG_PATH"
  nohup "$EMULATOR" "${EMULATOR_ARGS[@]}" >"$LOG_PATH" 2>&1 &
fi

TIMEOUT_SECONDS="${ANDROID_BOOT_TIMEOUT_SECONDS:-300}"
DEADLINE=$((SECONDS + TIMEOUT_SECONDS))
echo "Waiting for Android emulator to boot: $SERIAL"
while (( SECONDS < DEADLINE )); do
  STATE="$($ADB -s "$SERIAL" get-state 2>/dev/null || true)"
  BOOTED="$($ADB -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [[ "$STATE" == "device" && "$BOOTED" == "1" ]]; then
    break
  fi
  sleep 2
done
if [[ "$($ADB -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" != "1" ]]; then
  echo "error: emulator did not finish booting within ${TIMEOUT_SECONDS}s: $SERIAL" >&2
  exit 1
fi

GUEST_CONFIGURATION="$(
  "$ADB" -s "$SERIAL" shell dumpsys activity 2>/dev/null |
    awk '/mGlobalConfiguration/ { print; exit }' || true
)"
if [[ "$GUEST_CONFIGURATION" == *"-keyb"* ]]; then
  echo "error: Android booted without the configured hardware keyboard; cold-boot the AVD" >&2
  exit 1
fi

# Emulator mouse input advertises stylus support. Disable Gboard's handwriting toolbar and,
# by default, keep all IME chrome hidden so typing goes directly through the host keyboard.
SOFT_KEYBOARD="${ANDROID_SHOW_SOFT_KEYBOARD:-0}"
if [[ "$SOFT_KEYBOARD" != "0" && "$SOFT_KEYBOARD" != "1" ]]; then
  echo "error: ANDROID_SHOW_SOFT_KEYBOARD must be 0 or 1" >&2
  exit 1
fi
"$ADB" -s "$SERIAL" shell settings put secure stylus_handwriting_enabled 0
"$ADB" -s "$SERIAL" shell settings put secure show_ime_with_hard_keyboard "$SOFT_KEYBOARD"
"$ADB" -s "$SERIAL" shell settings put system show_touches 0
"$ADB" -s "$SERIAL" shell settings put system pointer_location 0
"$ADB" -s "$SERIAL" shell am force-stop com.google.android.inputmethod.latin >/dev/null 2>&1 || true

# Wake and unlock the emulator when it is using the standard no-password test image.
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true

echo "Installing Kelma Review on $SERIAL"
"$ADB" -s "$SERIAL" install -r -d "$APK"

echo "Launching Kelma Review"
"$ADB" -s "$SERIAL" shell am force-stop "$PACKAGE"
"$ADB" -s "$SERIAL" shell am start -W -n "$ACTIVITY"

echo "Kelma Review is running on $AVD ($SERIAL)"
echo "Logs: $ADB -s $SERIAL logcat --pid=\$( $ADB -s $SERIAL shell pidof -s $PACKAGE )"
