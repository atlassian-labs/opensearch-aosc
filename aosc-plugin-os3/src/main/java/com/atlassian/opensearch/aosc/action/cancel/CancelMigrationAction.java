/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.cancel;

import org.opensearch.action.ActionType;

/**
 * Action to cancel an active AOSC migration.
 */
public class CancelMigrationAction extends ActionType<CancelMigrationResponse> {

    public static final CancelMigrationAction INSTANCE = new CancelMigrationAction();
    public static final String NAME = "cluster:admin/aosc/migration/cancel";

    private CancelMigrationAction() {
        super(NAME, CancelMigrationResponse::new);
    }
}
