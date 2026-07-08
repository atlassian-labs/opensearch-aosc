/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.benchmark;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Captures the results of a benchmark run including timing, throughput,
 * and validation data.
 */
public class BenchmarkResult {

    public String profile;
    public String terminalPhase;
    public long totalDurationMs;
    public long seedDurationMs;
    public int seedDocCount;
    public long sourceDocCount;
    public long targetDocCount;
    public boolean docCountMatch;
    public long writeOpsTotal;
    public long writeErrors;
    public long writeBlockErrors;
    public int writeRateTarget;
    public Map<String, MetricsCollector.PhaseRecord> transitionHistory;

    /** Backfill throughput in docs/sec (0 if no ACTIVE phase recorded). */
    public long backfillDocsPerSec() {
        MetricsCollector.PhaseRecord active = transitionHistory != null ? transitionHistory.get("ACTIVE") : null;
        if (active != null && active.durationMs > 0 && seedDocCount > 0) {
            return (long) seedDocCount * 1000L / active.durationMs;
        }
        return 0;
    }

    /** Write-block duration in ms (CUTTING_OVER phase). */
    public long writeBlockDurationMs() {
        MetricsCollector.PhaseRecord cutover = transitionHistory != null ? transitionHistory.get("CUTTING_OVER") : null;
        return cutover != null ? cutover.durationMs : 0;
    }

    /** Write results to a JSON file. */
    public void writeJson(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outputDir.resolve("result.json")))) {
            pw.println("{");
            pw.printf(Locale.ROOT, "  \"profile\": \"%s\",%n", profile);
            pw.printf(Locale.ROOT, "  \"terminal_phase\": \"%s\",%n", terminalPhase);
            pw.printf(Locale.ROOT, "  \"total_duration_ms\": %d,%n", totalDurationMs);
            pw.printf(Locale.ROOT, "  \"seed_duration_ms\": %d,%n", seedDurationMs);
            pw.printf(Locale.ROOT, "  \"seed_doc_count\": %d,%n", seedDocCount);
            pw.printf(Locale.ROOT, "  \"source_doc_count\": %d,%n", sourceDocCount);
            pw.printf(Locale.ROOT, "  \"target_doc_count\": %d,%n", targetDocCount);
            pw.printf(Locale.ROOT, "  \"doc_count_match\": %s,%n", docCountMatch);
            pw.printf(Locale.ROOT, "  \"backfill_docs_per_sec\": %d,%n", backfillDocsPerSec());
            pw.printf(Locale.ROOT, "  \"write_block_duration_ms\": %d,%n", writeBlockDurationMs());
            pw.printf(Locale.ROOT, "  \"write_ops_total\": %d,%n", writeOpsTotal);
            pw.printf(Locale.ROOT, "  \"write_errors\": %d,%n", writeErrors);
            pw.printf(Locale.ROOT, "  \"write_block_errors\": %d,%n", writeBlockErrors);
            pw.printf(Locale.ROOT, "  \"write_rate_target\": %d,%n", writeRateTarget);
            pw.println("  \"transition_history\": {");
            if (transitionHistory != null) {
                int i = 0;
                for (Map.Entry<String, MetricsCollector.PhaseRecord> e : transitionHistory.entrySet()) {
                    pw.printf(
                        Locale.ROOT,
                        "    \"%s\": %d%s%n",
                        e.getKey(),
                        e.getValue().durationMs,
                        ++i < transitionHistory.size() ? "," : ""
                    );
                }
            }
            pw.println("  }");
            pw.println("}");
        }
    }

    /** Print a human-readable summary to stdout. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n══════════════════════════════════════════════════════════\n");
        sb.append("  AOSC Benchmark Result — ").append(profile).append("\n");
        sb.append("══════════════════════════════════════════════════════════\n\n");
        sb.append(String.format(Locale.ROOT, "  Status:              %s%n", terminalPhase));
        sb.append(String.format(Locale.ROOT, "  Total duration:      %s%n", formatDuration(totalDurationMs)));
        sb.append(String.format(Locale.ROOT, "  Seed duration:       %s%n", formatDuration(seedDurationMs)));
        sb.append(String.format(Locale.ROOT, "  Seed docs:           %,d%n", seedDocCount));
        sb.append(String.format(Locale.ROOT, "  Source doc count:    %,d%n", sourceDocCount));
        sb.append(String.format(Locale.ROOT, "  Target doc count:    %,d%n", targetDocCount));
        sb.append(String.format(Locale.ROOT, "  Doc count match:     %s%n", docCountMatch ? "✅" : "❌"));
        sb.append(String.format(Locale.ROOT, "  Backfill throughput: %,d docs/sec%n", backfillDocsPerSec()));
        sb.append(String.format(Locale.ROOT, "  Write-block:         %s%n", formatDuration(writeBlockDurationMs())));
        sb.append(String.format(Locale.ROOT, "  Write ops total:     %,d%n", writeOpsTotal));
        sb.append(String.format(Locale.ROOT, "  Write errors:        %,d%n", writeErrors));
        sb.append(String.format(Locale.ROOT, "  Write block errors:  %,d%n", writeBlockErrors));
        sb.append("\n  Phase Timings:\n");
        if (transitionHistory != null) {
            for (Map.Entry<String, MetricsCollector.PhaseRecord> e : transitionHistory.entrySet()) {
                sb.append(String.format(Locale.ROOT, "    %-20s %s%n", e.getKey(), formatDuration(e.getValue().durationMs)));
            }
        }
        sb.append("\n──────────────────────────────────────────────────────────\n");
        return sb.toString();
    }

    private String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long sec = ms / 1000;
        if (sec < 60) return sec + "." + (ms % 1000) / 100 + "s";
        return (sec / 60) + "m " + (sec % 60) + "s";
    }
}
