/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.update;

import org.opensearch.action.ActionType;

/**
 * Action for workers to report shard phase updates to the coordinator (ClusterManager).
 * Uses cluster:internal prefix — routed to ClusterManager node.
 */
public class UpdateShardMigrationStatusAction extends ActionType<UpdateShardMigrationStatusResponse> {

    public static final UpdateShardMigrationStatusAction INSTANCE = new UpdateShardMigrationStatusAction();
    public static final String NAME = "cluster:internal/aosc/shard/status";

    private UpdateShardMigrationStatusAction() {
        super(NAME, UpdateShardMigrationStatusResponse::new);
    }
}
