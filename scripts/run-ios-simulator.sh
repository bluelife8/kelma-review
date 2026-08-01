#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Build, install, and launch Kelma Review in an iOS simulator.

Usage:
  ./scripts/run-ios-simulator.sh [simulator name]

Examples:
  ./scripts/run-ios-simulator.sh
  ./scripts/run-ios-simulator.sh "iPhone 17 Pro"

Environment:
  IOS_SIMULATOR_DEVICE  Default simulator when no argument is provided.
  SHOW_SIMULATOR=0              Do not open the Simulator application.
  IOS_SIMULATOR_BUILD_ONLY=1    Build and verify signing without boot/install/launch.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEVICE="${1:-${IOS_SIMULATOR_DEVICE:-iPhone 17 Pro}}"
DERIVED_DATA="$ROOT_DIR/build/ios"
APP_PATH="$DERIVED_DATA/Build/Products/Debug-iphonesimulator/Kelma Review.app"
BUNDLE_ID="tech.kelma.app.KelmaReview"
BUILD_ONLY="${IOS_SIMULATOR_BUILD_ONLY:-0}"

command -v xcrun >/dev/null || {
  echo "error: Xcode command-line tools are not installed" >&2
  exit 1
}

cd "$ROOT_DIR"
if [[ -d "$ROOT_DIR/kelma-fsrs-v6" ]]; then
  export ORG_GRADLE_PROJECT_kelmaFsrsPath="$ROOT_DIR/kelma-fsrs-v6"
fi

if [[ "$BUILD_ONLY" != "1" ]]; then
  echo "Booting iOS simulator: $DEVICE"
  xcrun simctl boot "$DEVICE" 2>/dev/null || true
  xcrun simctl bootstatus "$DEVICE" -b

  if [[ "${SHOW_SIMULATOR:-1}" != "0" ]]; then
    open -a Simulator
  fi
  DESTINATION="platform=iOS Simulator,name=$DEVICE,OS=latest"
else
  DESTINATION="generic/platform=iOS Simulator"
fi

echo "Building Kelma Review for $DESTINATION"
xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination "$DESTINATION" \
  -derivedDataPath "$DERIVED_DATA" \
  CODE_SIGN_IDENTITY=- \
  CODE_SIGN_STYLE=Manual \
  DEVELOPMENT_TEAM= \
  CODE_SIGNING_ALLOWED=YES \
  CODE_SIGNING_REQUIRED=YES \
  clean build

if [[ ! -d "$APP_PATH" ]]; then
  echo "error: expected simulator app was not produced at $APP_PATH" >&2
  exit 1
fi

# An unsigned simulator app launches, but Security.framework rejects every
# Keychain operation with errSecMissingEntitlement (-34018). Xcode's ad-hoc
# "Sign to Run Locally" identity supplies the simulator process identity needed
# by Keychain without requiring a developer account.
if ! codesign --verify --strict "$APP_PATH"; then
  echo "error: simulator app is not signed; Keychain credentials would be unavailable" >&2
  exit 1
fi
if [[ "$(otool -l "$APP_PATH/Kelma Review")" != *"__entitlements"* ]]; then
  echo "error: simulator app has no application-identifier entitlement; Keychain would return -34018" >&2
  exit 1
fi

if [[ "$BUILD_ONLY" == "1" ]]; then
  echo "Verified signed Keychain-capable iOS Simulator app"
  exit 0
fi

echo "Installing Kelma Review"
xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" 2>/dev/null || true
xcrun simctl install "$DEVICE" "$APP_PATH"

echo "Launching Kelma Review"
xcrun simctl launch "$DEVICE" "$BUNDLE_ID"
