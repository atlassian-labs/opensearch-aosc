#!/usr/bin/env bash
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

mike deploy --push "${VERSION}"

if [[ "${SET_DEFAULT}" == "true" ]]; then
  mike set-default --push "${VERSION}"
fi
