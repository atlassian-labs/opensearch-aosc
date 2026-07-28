/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import org.opensearch.action.bulk.BulkRequest;

import java.util.List;

/**
 * A prepared bulk batch ready for submission.
 *
 * @param <M> engine-specific metrics type
 */
@lombok.Value
@lombok.experimental.Accessors(fluent = true)
public class PreparedBatch<M> {
    BulkRequest request;
    List<WriteOp<M>> ops;
    int docCount;
    long estimatedBytes;
}
