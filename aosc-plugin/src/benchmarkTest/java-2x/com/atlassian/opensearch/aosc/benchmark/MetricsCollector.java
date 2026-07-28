/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.benchmark;

import org.apache.http.util.EntityUtils;

import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background metrics collector that polls the AOSC status endpoint
 * and records phase transitions, timing, and shard progress to CSV.
 */
public class MetricsCollector implements Closeable {

    private static final Logger LOG = Logger.getLogger(MetricsCollector.class.getName());

    private final RestClient client;
    private final String sourceIndex;
    private final Path outputDir;
    private final int pollIntervalMs;
    private final Supplier<Long> writeOpsSupplier;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    // Collected data
    private final List<DataPoint> dataPoints = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, PhaseRecord> phaseRecords = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile String currentPhase = "";
    private volatile long phaseStartMs;
    private volatile String terminalPhase;

    public MetricsCollector(RestClient client, String sourceIndex, Path outputDir, int pollIntervalMs, Supplier<Long> writeOpsSupplier) {
        this.client = client;
        this.sourceIndex = sourceIndex;
        this.outputDir = outputDir;
        this.pollIntervalMs = pollIntervalMs;
        this.writeOpsSupplier = writeOpsSupplier;
    }

    /** Start collecting in background. */
    public void start() {
        if (running.getAndSet(true)) {
            throw new IllegalStateException("Already running");
        }
        worker = new Thread(this::run, "metrics-collector");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void close() {
        running.set(false);
        if (worker != null) {
            try {
                worker.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public Map<String, PhaseRecord> getPhaseRecords() {
        return Collections.unmodifiableMap(phaseRecords);
    }

    /** Write collected metrics to CSV files. */
    public void writeResults() throws IOException {
        Files.createDirectories(outputDir);

        // metrics.csv
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outputDir.resolve("metrics.csv")))) {
            pw.println("timestamp,elapsed_ms,phase,write_ops_total");
            for (DataPoint dp : dataPoints) {
                pw.printf(Locale.ROOT, "%s,%d,%s,%d%n", dp.timestamp, dp.elapsedMs, dp.coordinatorPhase, dp.writeOpsTotal);
            }
        }

        // phases.csv
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outputDir.resolve("phases.csv")))) {
            pw.println("phase,duration_ms");
            for (Map.Entry<String, PhaseRecord> entry : phaseRecords.entrySet()) {
                pw.printf(Locale.ROOT, "%s,%d%n", entry.getKey(), entry.getValue().durationMs);
            }
        }
    }

    private void run() {
        long startMs = System.currentTimeMillis();
        phaseStartMs = startMs;

        while (running.get()) {
            try {
                poll(startMs);
            } catch (Exception e) {
                LOG.log(Level.FINE, "Poll error", e);
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void poll(long startMs) throws IOException {
        Request request = new Request("GET", "/_plugins/_aosc/" + sourceIndex + "/_status");
        Response response = client.performRequest(request);
        String body = EntityUtils.toString(response.getEntity());
        Map<String, Object> map = parseJson(body);

        String phase = (String) map.getOrDefault("phase", "UNKNOWN");
        long now = System.currentTimeMillis();
        long elapsed = now - startMs;

        // Record data point
        DataPoint dp = new DataPoint();
        dp.timestamp = Instant.now().toString();
        dp.elapsedMs = elapsed;
        dp.coordinatorPhase = phase;
        dp.writeOpsTotal = writeOpsSupplier.get();
        dataPoints.add(dp);

        // Detect phase transition
        if (!phase.equals(currentPhase)) {
            if (!currentPhase.isEmpty()) {
                long duration = now - phaseStartMs;
                phaseRecords.put(currentPhase, new PhaseRecord(currentPhase, duration));
                LOG.info("Phase " + currentPhase + " completed in " + duration + "ms");
            }
            currentPhase = phase;
            phaseStartMs = now;
            LOG.info("Entered phase: " + phase);
        }

        // Terminal state
        if ("COMPLETED".equals(phase) || "FAILED".equals(phase) || "CANCELLED".equals(phase)) {
            long duration = now - phaseStartMs;
            phaseRecords.put(phase, new PhaseRecord(phase, duration));
            terminalPhase = phase;
            running.set(false);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        // Simple JSON parsing using OpenSearch's built-in XContent
        try {
            return XContentHelper.convertToMap(XContentType.JSON.xContent(), json, false);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /** A single time-series data point. */
    public static class DataPoint {
        public String timestamp;
        public long elapsedMs;
        public String coordinatorPhase;
        public long writeOpsTotal;
    }

    /** Timing record for a single phase. */
    public static class PhaseRecord {
        public final String phase;
        public final long durationMs;

        public PhaseRecord(String phase, long durationMs) {
            this.phase = phase;
            this.durationMs = durationMs;
        }
    }
}
