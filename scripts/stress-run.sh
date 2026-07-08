#!/bin/bash
#
# Stress-run a Gradle test task N times and aggregate results.
#
# The OpenSearch version selects the line/module, so you MUST pass -PopensearchVersion=<v>
# (or set it in ~/.gradle/gradle.properties). Tasks are unqualified — no :aosc-plugin: prefix.
#
# Usage: ./scripts/stress-run.sh <gradle-task> <nruns> [-PopensearchVersion=<v>] [--tests <filter>]
#
# Examples:
#   ./scripts/stress-run.sh itTest 10 -PopensearchVersion=3.6.0
#   ./scripts/stress-run.sh smokeTest2Nodes 5 -PopensearchVersion=2.19.0 --tests "*.SmokeMigrationCoreIT"
#   ./scripts/stress-run.sh scaleTest 3 -PopensearchVersion=3.6.0
#
# Output: merged test results in build/stress-results/ with per-test pass/fail/flaky stats.

set -u

task=$1
nruns=$2
shift 2
extra_args="$*"

results_dir="build/stress-results"
tmp_dir="build/stress-results/runs"
rm -rf "$results_dir"
mkdir -p "$tmp_dir"

pass_count=0
fail_count=0

for i in $(seq 1 "$nruns"); do
  echo ""
  echo "╔══════════════════════════════════════════╗"
  echo "║  Stress run $i / $nruns: $task"
  echo "╚══════════════════════════════════════════╝"
  echo ""

  rm -rf aosc-plugin-os*/build/test-results

  if ./gradlew --no-daemon "$task" $extra_args 2>&1; then
    echo "✅ Run $i: PASSED"
    pass_count=$((pass_count + 1))
  else
    echo "❌ Run $i: FAILED"
    fail_count=$((fail_count + 1))
  fi

  # Collect XML results from whichever line's module built (only one participates per run)
  run_dir="$tmp_dir/run-$i"
  mkdir -p "$run_dir"
  find aosc-plugin-os*/build -path "*/test-results/*/*.xml" -exec cp {} "$run_dir/" \; 2>/dev/null
done

echo ""
echo "╔══════════════════════════════════════════╗"
echo "║  Stress run complete: $pass_count/$nruns passed"
echo "╚══════════════════════════════════════════╝"
echo ""

# Aggregate results
python3 scripts/stress-aggregate.py "$tmp_dir" "$nruns" "$results_dir"

# Cleanup run dirs
rm -rf "$tmp_dir"

# Exit with failure if any run failed
if [ "$fail_count" -gt 0 ]; then
  exit 1
fi
