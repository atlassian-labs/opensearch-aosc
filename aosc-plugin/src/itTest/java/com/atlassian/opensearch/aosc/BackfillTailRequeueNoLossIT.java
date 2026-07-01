/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.service.bulk.BulkWriteHelper;

import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.index.IndexingPressure;
import org.opensearch.test.OpenSearchIntegTestCase;

import org.junit.After;
import org.junit.Before;

/**
 * End-to-end no-loss guard for the backfill tail-requeue data loss (shard 116 of the
 * confluence-content-2.0.0-0000-2023.10.26 -> ...-v2 migration: 156 docs dropped).
 *
 * <p>Runs a real multi-shard migration at backfill concurrency &gt;= 2 under sustained, recoverable
 * overload and asserts the migration completes with matching source/target doc counts. The adaptive
 * controller always recovers, so a correct writer never false-fails; a dropped tail batch would
 * surface as a FAILED migration (cutover validation, tolerance 0) or mismatched counts.
 *
 * <p>This is a probabilistic guard — a black-box cluster cannot force the exact async interleaving —
 * so it complements the deterministic {@code ConcurrentBulkWriterTailRequeueRaceTests}.
 */
@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST, numDataNodes = 2)
public class BackfillTailRequeueNoLossIT extends AoscIntegTestBase {

    private long savedBaseRetryDelayMs;

    @Before
    public void speedUpRetries() {
        savedBaseRetryDelayMs = BulkWriteHelper.baseRetryDelayMs;
        BulkWriteHelper.baseRetryDelayMs = 100;
    }

    @After
    public void restoreRetries() {
        BulkWriteHelper.baseRetryDelayMs = savedBaseRetryDelayMs;
    }

    public void testMultiShardBackfillUnderOverloadLosesNoDocs() throws Exception {
        String source = indexName("tailrace-src");
        String target = indexName("tailrace-tgt");

        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(
                Settings.builder()
                    .put("aosc.backfill.controller.type", "adaptive")
                    // 512 MB ceiling vs the 100 MB pressure limit (nodeSettings) forces overload ->
                    // shrink -> requeue -> retry cycles through each shard's tail.
                    .put("aosc.backfill.controller.batch.max_bytes", "512mb")
                    // Let concurrency ramp above 1 — the race only exists with >= 2 in flight per shard.
                    .put("aosc.backfill.controller.concurrency.max", "4")
            )
            .get();

        try {
            // Multiple shards => multiple independent backfill tails.
            createSourceAndTarget(source, target, 3, 3);

            int docCount = 1500; // ~500 docs/shard
            int docSizeBytes = 100 * 1024; // 100 KB; 1500 x 100 KB = ~150 MB, well over the 100 MB limit
            String padding = randomPadding(docSizeBytes);

            int chunkSize = 100;
            for (int start = 0; start < docCount; start += chunkSize) {
                BulkRequest bulk = new BulkRequest();
                int end = Math.min(start + chunkSize, docCount);
                for (int i = start; i < end; i++) {
                    bulk.add(
                        new IndexRequest(source).id(String.valueOf(i))
                            .source("{\"title\":\"doc-" + i + "\",\"body\":\"" + padding + "\"}", XContentType.JSON)
                    );
                }
                bulk.setRefreshPolicy(WriteRequest.RefreshPolicy.NONE);
                client().bulk(bulk).actionGet();
            }
            client().admin().indices().prepareRefresh(source).get();
            assertEquals("Source should have all docs", docCount, getDocCount(source));

            startMigration(source, target, source + "-alias", null);

            assertMigrationCompleted(source, 360);
            assertDocCountsMatch(source, target);
            assertEquals("Target must hold every source document", docCount, getDocCount(target));
        } finally {
            client().admin()
                .cluster()
                .prepareUpdateSettings()
                .setTransientSettings(
                    Settings.builder()
                        .putNull("aosc.backfill.controller.type")
                        .putNull("aosc.backfill.controller.batch.max_bytes")
                        .putNull("aosc.backfill.controller.concurrency.max")
                )
                .get();
        }
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal))
            // Explicit pressure limit makes the overload deterministic regardless of JVM heap size
            // (default is 10% of heap, which varies by environment).
            .put(IndexingPressure.MAX_INDEXING_BYTES.getKey(), "100mb")
            .build();
    }

    private static String randomPadding(int bytes) {
        StringBuilder sb = new StringBuilder(bytes);
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789 ";
        for (int i = 0; i < bytes; i++) {
            sb.append(chars.charAt(i % chars.length()));
        }
        return sb.toString();
    }
}
