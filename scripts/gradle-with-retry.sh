#!/usr/bin/env bash
set -euo pipefail

MAX_ATTEMPTS="${GRADLE_RETRY_ATTEMPTS:-3}"
DELAYS=(5 20)

if [[ "$#" -eq 0 ]]; then
  echo "Usage: $0 <gradle-command> [args...]" >&2
  exit 2
fi

is_retryable_failure() {
  local log_file="$1"
  grep -Eq \
    'Failed to load eclipse jdt formatter|SocketTimeoutException: connect timed out|Read timed out|Connection timed out|Could not resolve all files|Could not resolve .*spotless|repo.maven.apache.org|plugins.gradle.org|artifacts.opensearch.org' \
    "${log_file}"
}

for ((attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)); do
  log_file="$(mktemp "${RUNNER_TEMP:-/tmp}/gradle-with-retry.XXXXXX.log")"
  echo "gradle-with-retry: attempt ${attempt}/${MAX_ATTEMPTS}: $*"

  set +e
  "$@" 2>&1 | tee "${log_file}"
  status=${PIPESTATUS[0]}
  set -e

  if [[ "${status}" -eq 0 ]]; then
    rm -f "${log_file}"
    exit 0
  fi

  if [[ "${attempt}" -lt "${MAX_ATTEMPTS}" ]] && is_retryable_failure "${log_file}"; then
    delay="${DELAYS[$((attempt - 1))]:-20}"
    echo "gradle-with-retry: retryable Gradle bootstrap failure; sleeping ${delay}s before retry"
    rm -f "${log_file}"
    sleep "${delay}"
    continue
  fi

  echo "gradle-with-retry: command failed with exit code ${status}"
  rm -f "${log_file}"
  exit "${status}"
done
