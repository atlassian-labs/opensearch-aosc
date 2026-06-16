#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

VERSION=""
SET_DEFAULT=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      VERSION="${2:-}"
      shift 2
      ;;
    --set-default)
      SET_DEFAULT=true
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 --version <docs-version> [--set-default]" >&2
      exit 1
      ;;
  esac
done

if [[ -z "${VERSION}" ]]; then
  echo "Missing required --version" >&2
  exit 1
fi

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"

ROOT_DIR="$(git rev-parse --show-toplevel)"
BUILD_DIR="${ROOT_DIR}/docs/.vitepress/dist"
PUBLISH_DIR="$(mktemp -d)"

cleanup() {
  git worktree remove "${PUBLISH_DIR}" --force >/dev/null 2>&1 || true
}
trap cleanup EXIT

DOCS_BASE="/opensearch-aosc/${VERSION}/" npm run docs:build

git fetch origin gh-pages || true
if git show-ref --verify --quiet refs/remotes/origin/gh-pages; then
  git worktree add "${PUBLISH_DIR}" origin/gh-pages
else
  git worktree add --detach "${PUBLISH_DIR}"
  git -C "${PUBLISH_DIR}" checkout --orphan gh-pages
  git -C "${PUBLISH_DIR}" rm -rf . >/dev/null 2>&1 || true
fi

mkdir -p "${PUBLISH_DIR}/${VERSION}"
find "${PUBLISH_DIR:?}/${VERSION}" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
cp -R "${BUILD_DIR}/." "${PUBLISH_DIR}/${VERSION}/"

PUBLISH_DIR="${PUBLISH_DIR}" VERSION="${VERSION}" node <<'NODE'
const fs = require("node:fs");
const nodePath = require("node:path");
const publishDir = process.env.PUBLISH_DIR;
const version = process.env.VERSION;
const path = nodePath.join(publishDir, "versions.json");
let versions = [];

if (fs.existsSync(path)) {
  try {
    versions = JSON.parse(fs.readFileSync(path, "utf8"));
  } catch {
    versions = [];
  }
}

versions = versions.filter((entry) => entry.version !== version);
const entry = { version, title: version, aliases: [] };

if (version === "develop") {
  versions.unshift(entry);
} else {
  const develop = versions.filter((item) => item.version === "develop");
  const releases = versions.filter((item) => item.version !== "develop");
  versions = [...develop, ...releases, entry];
}

fs.writeFileSync(path, `${JSON.stringify(versions, null, 2)}\n`);
NODE

if [[ "${SET_DEFAULT}" == "true" ]]; then
  cat > "${PUBLISH_DIR}/index.html" <<HTML
<!doctype html>
<meta charset="utf-8">
<meta http-equiv="refresh" content="0; url=./${VERSION}/">
<link rel="canonical" href="./${VERSION}/">
<title>AOSC Documentation</title>
<p>Redirecting to <a href="./${VERSION}/">${VERSION}</a>.</p>
HTML
fi

touch "${PUBLISH_DIR}/.nojekyll"

git -C "${PUBLISH_DIR}" add .
if git -C "${PUBLISH_DIR}" diff --cached --quiet; then
  echo "No documentation changes to publish for ${VERSION}."
else
  git -C "${PUBLISH_DIR}" commit -m "Deploy ${VERSION} docs"
  git -C "${PUBLISH_DIR}" push origin HEAD:gh-pages
fi
