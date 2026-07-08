/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import java.util.List;

/**
 * Provides {@link WriteOp}s for batch building, with retry support.
 *
 * @param <M> engine-specific metrics type
 */
public interface DocSource<M> {
    WriteOp<M> poll();

    void returnForRetry(List<WriteOp<M>> ops);

    boolean isExhausted();
}
