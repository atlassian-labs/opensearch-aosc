/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Validates benchmark results against configurable performance thresholds.
 * Returns a list of pass/fail results for CI gate use.
 */
public class ThresholdChecker {

    private final long maxMigrationDurationMs;
    private final long maxWriteBlockMs;
    private final long minBackfillDocsPerSec;
    private final boolean requireCompleted;
    private final boolean requireDocCountMatch;

    public ThresholdChecker(
        long maxMigrationDurationMs,
        long maxWriteBlockMs,
        long minBackfillDocsPerSec,
        boolean requireCompleted,
        boolean requireDocCountMatch
    ) {
        this.maxMigrationDurationMs = maxMigrationDurationMs;
        this.maxWriteBlockMs = maxWriteBlockMs;
        this.minBackfillDocsPerSec = minBackfillDocsPerSec;
        this.requireCompleted = requireCompleted;
        this.requireDocCountMatch = requireDocCountMatch;
    }

    /** Run all threshold checks. Returns list of check results. */
    public List<CheckResult> check(BenchmarkResult result) {
        List<CheckResult> checks = new ArrayList<>();

        if (requireCompleted) {
            checks.add(new CheckResult("Migration completed", "COMPLETED".equals(result.terminalPhase), "COMPLETED", result.terminalPhase));
        }

        checks.add(
            new CheckResult(
                "Total migration time",
                result.totalDurationMs <= maxMigrationDurationMs,
                "<= " + maxMigrationDurationMs + "ms",
                result.totalDurationMs + "ms"
            )
        );

        checks.add(
            new CheckResult(
                "Write-block duration",
                result.writeBlockDurationMs() <= maxWriteBlockMs,
                "<= " + maxWriteBlockMs + "ms",
                result.writeBlockDurationMs() + "ms"
            )
        );

        long throughput = result.backfillDocsPerSec();
        if (throughput > 0) {
            checks.add(
                new CheckResult(
                    "Backfill throughput",
                    throughput >= minBackfillDocsPerSec,
                    ">= " + minBackfillDocsPerSec + " docs/sec",
                    throughput + " docs/sec"
                )
            );
        }

        if (requireDocCountMatch) {
            checks.add(new CheckResult("Doc count match", result.docCountMatch, "true", String.valueOf(result.docCountMatch)));
        }

        return checks;
    }

    /** Format check results as a human-readable report. */
    public String formatReport(List<CheckResult> checks) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n  Threshold Check:\n");
        int passed = 0, failed = 0;
        for (CheckResult c : checks) {
            sb.append(
                String.format(Locale.ROOT, "    %s %-25s actual=%s (threshold: %s)%n", c.passed ? "✅" : "❌", c.name, c.actual, c.threshold)
            );
            if (c.passed) passed++;
            else failed++;
        }
        sb.append(String.format(Locale.ROOT, "%n  Result: %d passed, %d failed%n", passed, failed));
        return sb.toString();
    }

    /** Single threshold check result. */
    public static class CheckResult {
        public final String name;
        public final boolean passed;
        public final String threshold;
        public final String actual;

        public CheckResult(String name, boolean passed, String threshold, String actual) {
            this.name = name;
            this.passed = passed;
            this.threshold = threshold;
            this.actual = actual;
        }
    }
}
