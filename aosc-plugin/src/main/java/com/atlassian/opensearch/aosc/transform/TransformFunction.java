/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.transform;

import com.atlassian.opensearch.aosc.model.IndexDoc;

import java.util.List;

/** Per-document transform: source doc → 0..N target docs. Empty list drops the doc. */
@FunctionalInterface
public interface TransformFunction {
    List<IndexDoc> apply(IndexDoc sourceDoc);
}
