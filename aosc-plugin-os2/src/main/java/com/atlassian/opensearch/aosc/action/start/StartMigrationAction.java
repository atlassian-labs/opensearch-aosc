/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start;

import org.opensearch.action.ActionType;

/**
 * Action to start a new AOSC migration. Routed to the cluster manager node.
 */
public class StartMigrationAction extends ActionType<StartMigrationResponse> {

    public static final StartMigrationAction INSTANCE = new StartMigrationAction();
    public static final String NAME = "cluster:admin/aosc/migration/start";

    private StartMigrationAction() {
        super(NAME, StartMigrationResponse::new);
    }
}
