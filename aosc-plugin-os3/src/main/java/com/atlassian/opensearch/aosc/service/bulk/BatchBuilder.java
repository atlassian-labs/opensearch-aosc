/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import org.opensearch.action.bulk.BulkRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * Pulls {@link WriteOp}s from a {@link DocSource} and assembles {@link PreparedBatch} instances.
 * Not thread-safe — intended for use from a single reader thread.
 *
 * @param <M> engine-specific metrics type
 */
public class BatchBuilder<M> {

    private final DocSource<M> docSource;

    public BatchBuilder(DocSource<M> docSource) {
        this.docSource = docSource;
    }

    /**
     * Builds the next batch up to the given doc and byte limits.
     *
     * @return the batch, or null when the source is exhausted and no ops were collected
     */
    public PreparedBatch<M> nextBatch(int maxDocs, long maxBytes) {
        BulkRequest bulkRequest = new BulkRequest();
        List<WriteOp<M>> ops = new ArrayList<>();
        int docCount = 0;

        while (docCount < maxDocs) {
            if (maxBytes > 0 && bulkRequest.estimatedSizeInBytes() >= maxBytes) {
                break;
            }
            WriteOp<M> op = docSource.poll();
            if (op == null) {
                break;
            }
            ops.add(op);
            if (op.isWritable()) {
                bulkRequest.add(op.request());
                docCount++;
            }
        }

        if (ops.isEmpty()) {
            return null;
        }

        return new PreparedBatch<>(bulkRequest, ops, docCount, bulkRequest.estimatedSizeInBytes());
    }
}
