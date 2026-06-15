#!/usr/bin/env bash
set -euo pipefail

LINE="${1:-os2}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${ROOT_DIR}/build/release"

# shellcheck disable=SC1090
source <("${ROOT_DIR}/scripts/release-metadata.sh" "${LINE}" --shell)

IFS=',' read -r -a VERSIONS <<< "${BUILD_VERSIONS}"

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

for OS_VERSION in "${VERSIONS[@]}"; do
  echo "Building AOSC ${AOSC_VERSION} for OpenSearch ${OS_VERSION}"
  "${ROOT_DIR}/scripts/gradle-with-retry.sh" \
    "${ROOT_DIR}/gradlew" --no-daemon :aosc-plugin:bundlePlugin -Dopensearch.version="${OS_VERSION}"
  DIST_ZIP="${ROOT_DIR}/aosc-plugin/build/distributions/opensearch-aosc-${AOSC_VERSION}.zip"
  if [[ ! -f "${DIST_ZIP}" ]]; then
    echo "Expected distribution ZIP not found: ${DIST_ZIP}" >&2
    exit 1
  fi
  OS_MINOR="${OS_VERSION%.*}"
  cp "${DIST_ZIP}" \
    "${OUT_DIR}/opensearch-aosc-${AOSC_VERSION}-opensearch-${OS_MINOR}.zip"
done

(
  cd "${OUT_DIR}"
  shasum -a 256 *.zip > SHA256SUMS
)

cat > "${OUT_DIR}/release.env" <<EOF
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

echo "Release assets written to ${OUT_DIR}"
