/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

/** Immutable per-migration selection of how replayed deletes obtain routing. */
public enum DeleteRoutingStrategy {
    TRANSLOG_ROUTING,
    SHARD_TOPOLOGY
}
