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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * Multi-threaded bulk document seeder for benchmarks and scale tests.
 * Seeds documents into an index using parallel _bulk requests.
 */
public final class BulkDocSeeder {

    private static final Logger LOG = Logger.getLogger(BulkDocSeeder.class.getName());

    private final RestClient client;
    private final int threads;

    public BulkDocSeeder(RestClient client, int threads) {
        this.client = Objects.requireNonNull(client, "client");
        this.threads = Math.max(threads, 1);
    }

    /**
     * Seed {@code count} documents into the given index.
     * Returns elapsed time in milliseconds.
     */
    public long seed(String index, int count, int batchSize) throws Exception {
        return seed(index, count, batchSize, false);
    }

    /**
     * Seed {@code count} documents with custom routing (10 tenant buckets).
     * Returns elapsed time in milliseconds.
     */
    public long seedWithRouting(String index, int count, int batchSize) throws Exception {
        return seed(index, count, batchSize, true);
    }

    private long seed(String index, int count, int batchSize, boolean routing) throws Exception {
        long start = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int offset = 0; offset < count; offset += batchSize) {
                int batchStart = offset;
                int batchEnd = Math.min(offset + batchSize, count);
                futures.add(pool.submit(() -> {
                    sendBatch(index, batchStart, batchEnd, routing);
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        // Final refresh to make all docs searchable
        client.performRequest(new Request("POST", "/" + index + "/_refresh"));
        long elapsed = System.currentTimeMillis() - start;
        LOG.info("Seeded " + count + " docs into " + index + " in " + elapsed + "ms (" + threads + " threads, batch=" + batchSize + ")");
        return elapsed;
    }

    private void sendBatch(String index, int start, int end, boolean routing) throws Exception {
        BulkRequestBuilder bulk = new BulkRequestBuilder();
        for (int i = start; i < end; i++) {
            String routingKey = routing ? "tenant-" + (i % 10) : null;
            bulk.seedDoc(index, i, routingKey);
        }
        Request request = new Request("POST", "/_bulk");
        request.setEntity(new StringEntity(bulk.toString(), ContentType.APPLICATION_JSON));
        request.addParameter("refresh", "false");
        Response response = client.performRequest(request);
        int status = response.getStatusLine().getStatusCode();
        if (status < 200 || status >= 300) {
            throw new RuntimeException("Bulk seed failed with status " + status);
        }
    }
}
