#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LINE="${1:-os2}"
FORMAT="${2:---summary}"

read_prop() {
  local file="$1"
  local key="$2"
  local value
  value="$(awk -F= -v key="${key}" '$1 == key { value = substr($0, index($0, "=") + 1) } END { print value }' "${file}")"
  if [[ -z "${value}" ]]; then
    echo "Missing ${key} in ${file}" >&2
    exit 1
  fi
  printf '%s\n' "${value}"
}

VERSION_FILE="${ROOT_DIR}/version.properties"
LINE_FILE="${ROOT_DIR}/release/${LINE}.properties"

if [[ ! -f "${LINE_FILE}" ]]; then
  echo "Unknown release line: ${LINE}" >&2
  echo "Expected file: ${LINE_FILE}" >&2
  exit 1
fi

AOSC_VERSION="$(read_prop "${VERSION_FILE}" "aosc.version")"
OS_LINE="$(read_prop "${LINE_FILE}" "line")"
RELEASE_BRANCH="$(read_prop "${LINE_FILE}" "branch")"
PRIMARY_VERSION="$(read_prop "${LINE_FILE}" "primary_version")"
BUILD_VERSIONS="$(read_prop "${LINE_FILE}" "build_versions")"
TEST_VERSIONS="$(read_prop "${LINE_FILE}" "test_versions")"
RELEASE_TAG="aosc-${AOSC_VERSION}-${OS_LINE}"
AOSC_RELEASE_LINE="${AOSC_VERSION%.*}"
DOCS_VERSION="${AOSC_RELEASE_LINE}-${OS_LINE}"

if [[ "${OS_LINE}" != "${LINE}" ]]; then
  echo "Line mismatch: requested ${LINE}, but ${LINE_FILE} declares ${OS_LINE}" >&2
  exit 1
fi

case "${FORMAT}" in
  --shell)
    cat <<EOF
AOSC_VERSION=${AOSC_VERSION}
AOSC_RELEASE_LINE=${AOSC_RELEASE_LINE}
OS_LINE=${OS_LINE}
RELEASE_BRANCH=${RELEASE_BRANCH}
PRIMARY_VERSION=${PRIMARY_VERSION}
BUILD_VERSIONS=${BUILD_VERSIONS}
TEST_VERSIONS=${TEST_VERSIONS}
RELEASE_TAG=${RELEASE_TAG}
DOCS_VERSION=${DOCS_VERSION}
EOF
    ;;
  --summary)
    cat <<EOF
AOSC version: ${AOSC_VERSION}
AOSC release line: ${AOSC_RELEASE_LINE}
OpenSearch line: ${OS_LINE}
Release branch: ${RELEASE_BRANCH}
Primary version: ${PRIMARY_VERSION}
Build versions: ${BUILD_VERSIONS}
Test versions: ${TEST_VERSIONS}
Release tag: ${RELEASE_TAG}
Docs version: ${DOCS_VERSION}
EOF
    ;;
  *)
    echo "Usage: scripts/release-metadata.sh [os-line] [--summary|--shell]" >&2
    exit 1
    ;;
esac
