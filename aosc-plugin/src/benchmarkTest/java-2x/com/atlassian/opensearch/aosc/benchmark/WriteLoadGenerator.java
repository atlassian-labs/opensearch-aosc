/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.benchmark;

import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;

import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import org.opensearch.common.Randomness;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background write load generator for benchmarks.
 * Sends a mix of index/update/delete operations at a target rate.
 */
public class WriteLoadGenerator implements Closeable {

    private static final Logger LOG = Logger.getLogger(WriteLoadGenerator.class.getName());

    private final RestClient client;
    private final String index;
    private final int targetOpsPerSec;
    private final int batchSize;
    private final int indexPct;
    private final int updatePct;
    // deletePct = 100 - indexPct - updatePct
    private final AtomicLong totalOps = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong writeBlockErrors = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Thread> workers = new ArrayList<>();
    private final int writerThreads;

    // Monotonically increasing counter for new doc IDs (like v0.1's next_doc_id)
    private final AtomicLong docCounter = new AtomicLong();
    // Ring buffer of recently-created IDs for update/delete targeting
    private final AtomicLongArray knownIds;
    private final AtomicLong knownIdsHead = new AtomicLong();

    private final boolean useRouting;

    public WriteLoadGenerator(
        RestClient client,
        String index,
        int targetOpsPerSec,
        int batchSize,
        int docPool,
        int writerThreads,
        boolean useRouting
    ) {
        this(client, index, targetOpsPerSec, batchSize, docPool, 60, 25, writerThreads, useRouting);
    }

    public WriteLoadGenerator(
        RestClient client,
        String index,
        int targetOpsPerSec,
        int batchSize,
        int docPool,
        int indexPct,
        int updatePct,
        int writerThreads,
        boolean useRouting
    ) {
        this.client = client;
        this.index = index;
        this.targetOpsPerSec = targetOpsPerSec;
        this.batchSize = batchSize;
        this.indexPct = indexPct;
        this.updatePct = updatePct;
        this.writerThreads = Math.max(writerThreads, 1);
        this.useRouting = useRouting;
        this.knownIds = new AtomicLongArray(Math.min(docPool, 500_000));
    }

    /** Start generating load in background threads. */
    public void start() {
        if (running.getAndSet(true)) {
            throw new IllegalStateException("Already running");
        }
        for (int t = 0; t < writerThreads; t++) {
            Thread w = new Thread(this::run, "write-load-generator-" + t);
            w.setDaemon(true);
            w.start();
            workers.add(w);
        }
    }

    /** Stop and wait for all workers to finish. */
    @Override
    public void close() {
        running.set(false);
        for (Thread w : workers) {
            try {
                w.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public long getTotalOps() {
        return totalOps.get();
    }

    public long getErrors() {
        return errors.get();
    }

    public long getWriteBlockErrors() {
        return writeBlockErrors.get();
    }

    private void run() {
        int perThreadRate = targetOpsPerSec > 0 ? Math.max(targetOpsPerSec / writerThreads, 1) : 0;
        long batchesPerSec = perThreadRate / Math.max(batchSize, 1);
        long intervalNanos = batchesPerSec > 0 ? 1_000_000_000L / batchesPerSec : 0;

        while (running.get()) {
            long batchStart = System.nanoTime();
            try {
                sendBatch();
            } catch (Exception e) {
                // Log but don't stop — write blocks during cutover are expected
                if (e.getMessage() != null && (e.getMessage().contains("FORBIDDEN/8") || e.getMessage().contains("index write"))) {
                    writeBlockErrors.incrementAndGet();
                } else {
                    errors.incrementAndGet();
                    LOG.log(Level.FINE, "Write load error", e);
                }
            }

            // Rate limit
            if (intervalNanos > 0) {
                long elapsed = System.nanoTime() - batchStart;
                long sleepNanos = intervalNanos - elapsed;
                if (sleepNanos > 0) {
                    try {
                        Thread.sleep(sleepNanos / 1_000_000, (int) (sleepNanos % 1_000_000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        LOG.info("Write load stopped. Total ops: " + totalOps.get() + ", errors: " + errors.get());
    }

    private void sendBatch() throws Exception {
        BulkRequestBuilder bulk = new BulkRequestBuilder();
        Random rng = Randomness.get();
        long knownCount = knownIdsHead.get();

        for (int i = 0; i < batchSize; i++) {
            int roll = rng.nextInt(100);

            if (roll < indexPct || knownCount == 0) {
                // Index — always create a new unique doc
                long n = docCounter.incrementAndGet();
                String routing = useRouting ? "tenant-" + (n % 10) : null;
                bulk.index(index, "load-" + n, routing, "{\"field\":\"load-" + System.nanoTime() + "\",\"counter\":" + n + "}");
                long slot = knownIdsHead.getAndIncrement();
                knownIds.set((int) (slot % knownIds.length()), n);
                knownCount = slot + 1;
            } else if (roll < indexPct + updatePct) {
                // Update — pick a previously-created doc
                int slot = rng.nextInt((int) Math.min(knownCount, knownIds.length()));
                long targetN = knownIds.get(slot);
                String routing = useRouting ? "tenant-" + (targetN % 10) : null;
                bulk.update(index, "load-" + targetN, routing, "{\"field\":\"updated-" + System.nanoTime() + "\"}");
            } else {
                // Delete — pick a previously-created doc
                int slot = rng.nextInt((int) Math.min(knownCount, knownIds.length()));
                long targetN = knownIds.get(slot);
                String routing = useRouting ? "tenant-" + (targetN % 10) : null;
                bulk.delete(index, "load-" + targetN, routing);
            }
        }

        Request request = new Request("POST", "/_bulk");
        request.setEntity(new StringEntity(bulk.toString(), ContentType.APPLICATION_JSON));
        request.addParameter("refresh", "false");
        Response response = client.performRequest(request);

        int status = response.getStatusLine().getStatusCode();
        if (status >= 200 && status < 300) {
            totalOps.addAndGet(batchSize);
        } else {
            errors.addAndGet(batchSize);
        }
    }
}
