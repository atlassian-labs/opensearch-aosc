/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.status;

import org.opensearch.action.ActionType;

/**
 * Action to get the status of an AOSC migration.
 */
public class GetMigrationStatusAction extends ActionType<GetMigrationStatusResponse> {

    public static final GetMigrationStatusAction INSTANCE = new GetMigrationStatusAction();
    public static final String NAME = "cluster:monitor/aosc/migration/status";

    private GetMigrationStatusAction() {
        super(NAME, GetMigrationStatusResponse::new);
    }
}
