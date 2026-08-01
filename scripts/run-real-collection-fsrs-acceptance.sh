#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
collection="${1:-${HOME}/Library/Application Support/Anki2/User 1/collection.anki2}"
sync_repo="${KELMA_SYNC_REPO:-${repo_root}/../kelma_sync_2}"

if [[ ! -f "${collection}" ]]; then
  printf 'Anki collection not found. Pass collection.anki2 as the first argument.\n' >&2
  exit 2
fi
if [[ ! -f "${sync_repo}/go.mod" ]]; then
  printf 'KelmaSync repository not found. Set KELMA_SYNC_REPO.\n' >&2
  exit 2
fi

working_directory="$(mktemp -d "${TMPDIR:-/tmp}/kelma-fsrs-acceptance.XXXXXX")"
trap 'rm -rf "${working_directory}"' EXIT
kotlin_report="${working_directory}/kotlin-report.txt"
go_report="${working_directory}/go-report.txt"
python_report="${working_directory}/python-report.txt"
go_fixture="${working_directory}/schedule-only-fixture.ndjson"
oracle_image="kelma-fsrs-oracle:py-fsrs-6.3.1"

cd "${repo_root}"
KELMA_ANKI_COLLECTION="${collection}" \
KELMA_REQUIRE_REAL_COLLECTION_ACCEPTANCE=1 \
KELMA_FSRS_ACCEPTANCE_REPORT="${kotlin_report}" \
KELMA_FSRS_GO_FIXTURE="${go_fixture}" \
  ./gradlew :shared:jvmTest \
    --tests 'tech.kelma.app.RealCollectionFsrsAcceptanceTest' \
    --rerun-tasks

docker build \
  --platform linux/amd64 \
  --tag "${oracle_image}" \
  "${sync_repo}/tools/fsrs_oracle"
docker run --rm \
  --platform linux/amd64 \
  --volume "${working_directory}:/work" \
  --entrypoint python \
  "${oracle_image}" \
  /oracle/compare_real_fixture.py \
  /work/schedule-only-fixture.ndjson \
  /work/python-report.txt

(
  cd "${sync_repo}"
  KELMA_REQUIRE_REAL_COLLECTION_ACCEPTANCE=1 \
  KELMA_FSRS_GO_FIXTURE="${go_fixture}" \
  KELMA_FSRS_GO_REPORT="${go_report}" \
    go test ./internal/fsrs \
      -run '^TestRealCollectionFixtureMatchesKotlin$' \
      -count=1
)

print_report() {
  local heading="$1"
  local report="$2"
  printf '\n%s\n' "${heading}"
  printf '%s\n' '---------------------------------'
  while IFS= read -r line; do
    printf '%s\n' "${line}"
  done < "${report}"
}

print_report 'Kotlin persistent-client acceptance' "${kotlin_report}"
print_report 'Python versus Kotlin real-history parity' "${python_report}"
print_report 'Go versus Kotlin real-history parity' "${go_report}"
