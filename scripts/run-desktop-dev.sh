#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Build and run the Kelma desktop application in development mode.

Usage:
  ./scripts/run-desktop-dev.sh [Gradle options]

Examples:
  ./scripts/run-desktop-dev.sh
  ./scripts/run-desktop-dev.sh --offline
  ./scripts/run-desktop-dev.sh --info

Environment:
  JAVA_HOME     Optional JDK override. Java 21 is preferred.

Press Ctrl+C in this terminal to stop the application.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Make a fresh checkout convenient on macOS, where Android Studio commonly owns
# the project's JDK. Respect an explicitly configured JAVA_HOME everywhere.
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == "Darwin" ]]; then
  if [[ -x /usr/libexec/java_home ]] && JAVA_21_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)"; then
    export JAVA_HOME="$JAVA_21_HOME"
  elif [[ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  fi
fi

if [[ -n "${JAVA_HOME:-}" && ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "error: JAVA_HOME does not contain an executable Java runtime: $JAVA_HOME" >&2
  exit 1
fi

if [[ -z "${JAVA_HOME:-}" ]] && ! command -v java >/dev/null 2>&1; then
  echo "error: Java was not found. Install JDK 21 or set JAVA_HOME." >&2
  exit 1
fi

cd "$ROOT_DIR"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
RUN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/kelma-desktop-dev.XXXXXX")"
cleanup() {
  rm -rf "$RUN_DIR"
}
trap cleanup EXIT INT TERM

echo "Building isolated Kelma desktop development runtime"
echo "Project: $ROOT_DIR"
echo "Java: $JAVA_BIN"
echo "Runtime: $RUN_DIR"

# A normal Gradle :run process reads classes lazily from build/libs. Any later
# compile can replace those JARs under the running JVM and trigger a
# NoClassDefFoundError. Stage every runtime JAR in a unique immutable directory
# before launch so tests/builds can safely run while the app remains open.
./gradlew :desktopApp:stageDevRuntime -PkelmaDevRuntimeDir="$RUN_DIR" "$@"

# desktopApp/jvm-args.txt is the single source of truth shared with the
# packaged distribution (desktopApp/build.gradle.kts). Reading it here keeps the
# development JVM configuration identical to the one users get.
JVM_ARGS=()
while IFS= read -r line; do
  line="${line%%$'\r'}"
  [[ -z "${line// }" || "$line" == \#* ]] && continue
  JVM_ARGS+=("$line")
done < "$ROOT_DIR/desktopApp/jvm-args.txt"

echo "Starting Kelma desktop development app"
"$JAVA_BIN" \
  "${JVM_ARGS[@]}" \
  -Dcompose.application.resources.dir="$RUN_DIR/resources" \
  -cp "$RUN_DIR/lib/*" \
  tech.kelma.app.MainKt
