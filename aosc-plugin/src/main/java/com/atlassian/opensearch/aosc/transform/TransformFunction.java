/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.transform;

import com.atlassian.opensearch.aosc.model.DeletedDoc;
import com.atlassian.opensearch.aosc.model.IndexDoc;

import java.util.List;

/**
 * Per-document transform, run after AOSC routing; outputs are written as is. An implementation that
 * changes identity in {@link #apply} should mirror it in {@link #applyDelete}.
 */
@FunctionalInterface
public interface TransformFunction {

    /** Source doc → 0..N target docs. Empty list drops the doc. */
    List<IndexDoc> apply(IndexDoc sourceDoc);

    /** Routed delete → 0..N target deletes. Defaults to pass-through. */
    default List<DeletedDoc> applyDelete(DeletedDoc deletedDoc) {
        return List.of(deletedDoc);
    }
}
