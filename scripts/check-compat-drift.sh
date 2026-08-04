#!/usr/bin/env bash
# Checks that java-2x/ and java-3x/ compat files differ only in import/package
# lines. Fails if any non-excepted file pair has real code divergence.
set -euo pipefail

PLUGIN_DIR="${1:-aosc-plugin}"

# Files with intentional code differences between versions.
EXCEPTIONS=(
  "compat/OsCompat.java"
  "compat/OsTestCompat.java"
  "compat/MockClientFactory.java"
  "compat/HttpCompat.java"
  "utils/AsyncClientHelper.java"
  "action/cleanup/TransportCleanupLeasesActionTests.java"
)

is_exception() {
  local file="$1"
  for ex in "${EXCEPTIONS[@]}"; do
    if [[ "$file" == *"$ex" ]]; then
      return 0
    fi
  done
  return 1
}

strip_imports() {
  grep -v '^\s*import ' | grep -v '^\s*package '
}

errors=0

for dir2x in "$PLUGIN_DIR"/src/*/java-2x; do
  [ -d "$dir2x" ] || continue
  source_set_dir="$(dirname "$dir2x")"
  dir3x="$source_set_dir/java-3x"
  [ -d "$dir3x" ] || continue

  source_set="$(basename "$source_set_dir")"

  while IFS= read -r -d '' file2x; do
    rel="${file2x#"$dir2x/"}"
    file3x="$dir3x/$rel"

    [ -f "$file3x" ] || continue
    is_exception "$rel" && continue

    tmp2x=$(mktemp)
    tmp3x=$(mktemp)
    strip_imports < "$file2x" > "$tmp2x"
    strip_imports < "$file3x" > "$tmp3x"
    diff_output=$(diff "$tmp2x" "$tmp3x" || true)
    rm -f "$tmp2x" "$tmp3x"
    if [ -n "$diff_output" ]; then
      echo "DRIFT: $source_set: $rel"
      echo "$diff_output"
      echo ""
      errors=$((errors + 1))
    fi
  done < <(find "$dir2x" -name '*.java' -print0)
done

if [ "$errors" -gt 0 ]; then
  echo "ERROR: $errors compat file pair(s) have non-import divergence."
  echo "If intentional, add the file to EXCEPTIONS in scripts/check-compat-drift.sh"
  exit 1
fi

echo "OK: all compat file pairs differ only in imports."
