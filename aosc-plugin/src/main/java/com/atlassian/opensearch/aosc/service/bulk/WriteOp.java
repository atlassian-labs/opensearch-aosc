/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import org.opensearch.action.DocWriteRequest;

/**
 * A single write operation produced by an engine-specific iterator.
 *
 * @param <M> engine-specific metrics type
 */
@lombok.Value
@lombok.experimental.Accessors(fluent = true)
public class WriteOp<M> {

    DocWriteRequest<?> request;

    M metrics;

    public static <M> WriteOp<M> of(DocWriteRequest<?> request, M metrics) {
        return new WriteOp<>(request, metrics);
    }

    public static <M> WriteOp<M> skipped(M metrics) {
        return new WriteOp<>(null, metrics);
    }

    /** Returns true if this operation has a request to write. */
    public boolean isWritable() {
        return request != null;
    }
}
