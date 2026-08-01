#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
temporary="$(mktemp -d "${TMPDIR:-/tmp}/kelma-sbom.XXXXXX")"
trap 'rm -rf "$temporary"' EXIT

cd "$repo_root"
run_gradle() {
  if [[ -d "$repo_root/kelma-fsrs-v6" ]]; then
    ./gradlew "-PkelmaFsrsPath=$repo_root/kelma-fsrs-v6" "$@"
  else
    ./gradlew "$@"
  fi
}
run_gradle -q :desktopApp:dependencies --configuration runtimeClasspath >"$temporary/desktop.txt"
run_gradle -q :androidApp:dependencies --configuration debugRuntimeClasspath >"$temporary/android.txt"
run_gradle -q :shared:dependencies --configuration iosSimulatorArm64CompileKlibraries >"$temporary/ios.txt"
python3 scripts/generate_sbom.py "$temporary/desktop.txt" "$temporary/android.txt" "$temporary/ios.txt" >sbom.cdx.json
printf 'Wrote %s/sbom.cdx.json\n' "$repo_root"
