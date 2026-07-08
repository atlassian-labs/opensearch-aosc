/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import org.opensearch.action.index.IndexRequest;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class BatchBuilderTests extends OpenSearchTestCase {

    private static WriteOp<Integer> writableOp(String id) {
        return WriteOp.of(new IndexRequest("idx").id(id).source("k", "v"), 1);
    }

    private static WriteOp<Integer> skippedOp() {
        return WriteOp.skipped(0);
    }

    private static WriteOp<Integer> largeOp(String id, int sizeKb) {
        Map<String, Object> source = new HashMap<>();
        StringBuilder sb = new StringBuilder(sizeKb * 1024);
        for (int i = 0; i < sizeKb * 1024; i++) {
            sb.append('x');
        }
        source.put("data", sb.toString());
        return WriteOp.of(new IndexRequest("idx").id(id).source(source), 1);
    }

    public void testBuildsBatchUpToMaxDocs() {
        ThreadSafeDocSource<Integer> source = new ThreadSafeDocSource<>(
            Arrays.asList(writableOp("1"), writableOp("2"), writableOp("3")).iterator()
        );
        BatchBuilder<Integer> builder = new BatchBuilder<>(source);

        PreparedBatch<Integer> batch = builder.nextBatch(2, Long.MAX_VALUE);
        assertNotNull(batch);
        assertEquals(2, batch.docCount());
        assertEquals(2, batch.ops().size());

        PreparedBatch<Integer> batch2 = builder.nextBatch(10, Long.MAX_VALUE);
        assertNotNull(batch2);
        assertEquals(1, batch2.docCount());
    }

    public void testBuildsBatchUpToMaxBytes() {
        ThreadSafeDocSource<Integer> source = new ThreadSafeDocSource<>(
            Arrays.asList(largeOp("1", 10), largeOp("2", 10), largeOp("3", 10)).iterator()
        );
        BatchBuilder<Integer> builder = new BatchBuilder<>(source);

        PreparedBatch<Integer> batch = builder.nextBatch(100, 15_000);
        assertNotNull(batch);
        assertTrue("Should stop due to byte limit", batch.docCount() <= 2);
        assertTrue(batch.estimatedBytes() > 0);
    }

    public void testReturnsNullWhenExhausted() {
        ThreadSafeDocSource<Integer> source = new ThreadSafeDocSource<>(Collections.emptyIterator());
        BatchBuilder<Integer> builder = new BatchBuilder<>(source);

        assertNull(builder.nextBatch(10, Long.MAX_VALUE));
    }

    public void testSkippedOpsInListButNotInDocCount() {
        ThreadSafeDocSource<Integer> source = new ThreadSafeDocSource<>(
            Arrays.asList(skippedOp(), writableOp("1"), skippedOp(), writableOp("2")).iterator()
        );
        BatchBuilder<Integer> builder = new BatchBuilder<>(source);

        PreparedBatch<Integer> batch = builder.nextBatch(10, Long.MAX_VALUE);
        assertNotNull(batch);
        assertEquals(4, batch.ops().size());
        assertEquals(2, batch.docCount());
    }
}
