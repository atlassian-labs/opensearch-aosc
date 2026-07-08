/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.list;

import org.opensearch.action.ActionType;

/**
 * Action to list AOSC migrations.
 */
public class ListMigrationsAction extends ActionType<ListMigrationsResponse> {

    public static final ListMigrationsAction INSTANCE = new ListMigrationsAction();
    public static final String NAME = "cluster:monitor/aosc/migration/list";

    private ListMigrationsAction() {
        super(NAME, ListMigrationsResponse::new);
    }
}
