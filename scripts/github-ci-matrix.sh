#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LINE="${1:-os2}"
FORMAT="${2:---summary}"

# shellcheck disable=SC1090
source <("${ROOT_DIR}/scripts/release-metadata.sh" "${LINE}" --shell)

csv_to_json() {
  local csv="$1"
  local IFS=','
  local first=true

  printf '['
  for value in ${csv}; do
    if [[ "${first}" == "true" ]]; then
      first=false
    else
      printf ','
    fi
    printf '"%s"' "${value}"
  done
  printf ']'
}

validation_matrix_json() {
  local IFS=','
  local first=true
  local version

  printf '['
  for version in ${TEST_VERSIONS}; do
    emit_validation_row "${first}" "${version}" "fast-check" "fastCheck" ":aosc-plugin:fastCheck" ""
    first=false
    emit_validation_row "${first}" "${version}" "yaml-rest" "yamlRestTest" ":aosc-plugin:yamlRestTest" ""
    emit_validation_row "${first}" "${version}" "integration" "itTest" ":aosc-plugin:itTest" ""
    emit_validation_row "${first}" "${version}" "smoke-2n" "smokeTest2Nodes" ":aosc-plugin:smokeTest2Nodes" ""
    emit_validation_row "${first}" "${version}" "smoke-dedicated-cm" "smokeTestDedicatedCM" ":aosc-plugin:smokeTestDedicatedCM" ""
    emit_validation_row "${first}" "${version}" "smoke-docker" "smokeTestDocker" ":aosc-plugin:smokeTestDocker" ""
    emit_validation_row "${first}" "${version}" "scale-high-shard-2n" "scaleTest high-shard 2n" ":aosc-plugin:scaleTest" "-Dcluster.topology=2n -Dscale.profile=high-shard"
  done
  printf ']'
}

emit_validation_row() {
  local first="$1"
  local version="$2"
  local id="$3"
  local label="$4"
  local gradle_task="$5"
  local gradle_args="$6"

  if [[ "${first}" != "true" ]]; then
    printf ','
  fi
  printf '{"opensearch_version":"%s","id":"%s","label":"%s","gradle_task":"%s","gradle_args":"%s"}' \
    "${version}" "${id}" "${label}" "${gradle_task}" "${gradle_args}"
}

emit_github_output() {
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    emit_output_lines >> "${GITHUB_OUTPUT}"
  else
    emit_output_lines
  fi
}

emit_output_lines() {
  {
    echo "aosc_version=${AOSC_VERSION}"
    echo "os_line=${OS_LINE}"
    echo "release_branch=${RELEASE_BRANCH}"
    echo "primary_version=${PRIMARY_VERSION}"
    echo "build_versions=${BUILD_VERSIONS}"
    echo "test_versions=${TEST_VERSIONS}"
    echo "release_tag=${RELEASE_TAG}"
    echo "build_versions_json=$(csv_to_json "${BUILD_VERSIONS}")"
    echo "test_versions_json=$(csv_to_json "${TEST_VERSIONS}")"
    echo "validation_matrix_json=$(validation_matrix_json)"
  }
}

case "${FORMAT}" in
  --github-output)
    emit_github_output
    ;;
  --summary)
    "${ROOT_DIR}/scripts/release-metadata.sh" "${LINE}" --summary
    echo "Build versions JSON: $(csv_to_json "${BUILD_VERSIONS}")"
    echo "Test versions JSON: $(csv_to_json "${TEST_VERSIONS}")"
    ;;
  *)
    echo "Usage: scripts/github-ci-matrix.sh [os-line] [--summary|--github-output]" >&2
    exit 1
    ;;
esac
