/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.compat;

import org.opensearch.index.translog.Translog;

import java.util.Objects;

/** Wraps {@link Translog.Delete}; OpenSearch records delete routing only on 3.9+. */
public final class TranslogDeleteOp {

    private final Translog.Delete delete;

    public TranslogDeleteOp(Translog.Delete delete) {
        this.delete = Objects.requireNonNull(delete, "delete");
    }

    public String id() {
        return delete.id();
    }

    public long seqNo() {
        return delete.seqNo();
    }

    /** Routing the delete was sent with; throws before OpenSearch 3.9. */
    public String routing() {
        throw new UnsupportedOperationException("Translog delete routing requires OpenSearch 3.9 or later");
    }
}
