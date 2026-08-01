#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
collection="${1:-${HOME}/Library/Application Support/Anki2/User 1/collection.anki2}"
fsrs_repo="${KELMA_FSRS_REPO:-${repo_root}/../kelma-fsrs-v6}"
timezone="${KELMA_OPTIMIZER_TIMEZONE:-America/New_York}"
day_start_hour="${KELMA_OPTIMIZER_DAY_START_HOUR:-4}"

if [[ ! -f "${collection}" ]]; then
  printf 'Anki collection not found. Pass collection.anki2 as the first argument.\n' >&2
  exit 2
fi
if [[ ! -f "${fsrs_repo}/tools/optimizer_oracle/Dockerfile" ]]; then
  printf 'kelma-fsrs-v6 checkout not found. Set KELMA_FSRS_REPO.\n' >&2
  exit 2
fi

working_directory="$(mktemp -d "${TMPDIR:-/tmp}/kelma-optimizer-acceptance.XXXXXX")"
trap 'rm -rf "${working_directory}"' EXIT
review_fixture="${working_directory}/private-reviews.tsv"
kotlin_result="${working_directory}/kotlin-result.json"
kotlin_report="${working_directory}/kotlin-report.txt"
python_report="${working_directory}/python-report.txt"
source_before="$(stat -f '%z:%m' "${collection}")"
image="kelma-fsrs-optimizer-oracle:6.5.0"

cd "${repo_root}"
KELMA_ANKI_COLLECTION="${collection}" \
KELMA_REQUIRE_REAL_OPTIMIZER_ACCEPTANCE=1 \
KELMA_OPTIMIZER_TIMEZONE="${timezone}" \
KELMA_OPTIMIZER_DAY_START_HOUR="${day_start_hour}" \
KELMA_OPTIMIZER_REVIEW_FIXTURE="${review_fixture}" \
KELMA_OPTIMIZER_KOTLIN_RESULT="${kotlin_result}" \
KELMA_OPTIMIZER_KOTLIN_REPORT="${kotlin_report}" \
  ./gradlew :shared:jvmTest \
    --tests 'tech.kelma.app.RealCollectionFsrsOptimizerAcceptanceTest' \
    --rerun-tasks

docker build \
  --platform linux/amd64 \
  --tag "${image}" \
  "${fsrs_repo}/tools/optimizer_oracle"
docker run --rm \
  --platform linux/amd64 \
  --volume "${working_directory}:/work" \
  --entrypoint python \
  "${image}" \
  /oracle/compare_real.py \
  /work/private-reviews.tsv \
  /work/kotlin-result.json \
  /work/python-report.txt

source_after="$(stat -f '%z:%m' "${collection}")"
if [[ "${source_before}" != "${source_after}" ]]; then
  printf 'Source collection size or modification time changed.\n' >&2
  exit 1
fi

print_report() {
  local heading="$1"
  local report="$2"
  printf '\n%s\n' "${heading}"
  printf '%s\n' '---------------------------------'
  while IFS= read -r line; do
    printf '%s\n' "${line}"
  done < "${report}"
}

print_report 'Kotlin private optimizer acceptance' "${kotlin_report}"
print_report 'Python versus Kotlin optimizer parity' "${python_report}"
