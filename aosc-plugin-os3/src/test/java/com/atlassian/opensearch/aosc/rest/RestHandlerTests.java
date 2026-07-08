/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.rest;

import com.atlassian.opensearch.aosc.model.MigrationRequest;
import com.atlassian.opensearch.aosc.model.transform.InlineTransformScript;

import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.xcontent.DeprecationHandler;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.rest.RestRequest;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.test.rest.FakeRestRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for REST handler route registration and body parsing.
 *
 * <p>Verifies that each handler registers the correct HTTP method, path, and name,
 * and that StartMigration body parsing produces the correct {@link MigrationRequest}.
 */
public class RestHandlerTests extends OpenSearchTestCase {

    /** Verify StartMigration route is POST /_plugins/_aosc/{index}/_start */
    public void testStartMigrationRoute() {
        RestStartMigrationAction handler = new RestStartMigrationAction();
        List<RestStartMigrationAction.Route> routes = handler.routes();

        assertEquals(1, routes.size());
        assertEquals(RestRequest.Method.POST, routes.get(0).getMethod());
        assertEquals("/_plugins/_aosc/{index}/_start", routes.get(0).getPath());
        assertEquals("aosc_start_migration", handler.getName());
    }

    /** Verify GetStatus route is GET /_plugins/_aosc/{index}/_status */
    public void testGetStatusRoute() {
        RestGetMigrationStatusAction handler = new RestGetMigrationStatusAction();
        List<RestGetMigrationStatusAction.Route> routes = handler.routes();

        assertEquals(1, routes.size());
        assertEquals(RestRequest.Method.GET, routes.get(0).getMethod());
        assertEquals("/_plugins/_aosc/{index}/_status", routes.get(0).getPath());
        assertEquals("aosc_get_migration_status", handler.getName());
    }

    /** Verify Cancel route is POST /_plugins/_aosc/{index}/_cancel */
    public void testCancelRoute() {
        RestCancelMigrationAction handler = new RestCancelMigrationAction();
        List<RestCancelMigrationAction.Route> routes = handler.routes();

        assertEquals(1, routes.size());
        assertEquals(RestRequest.Method.POST, routes.get(0).getMethod());
        assertEquals("/_plugins/_aosc/{index}/_cancel", routes.get(0).getPath());
        assertEquals("aosc_cancel_migration", handler.getName());
    }

    /** Verify ListMigrations route is GET /_plugins/_aosc/_list */
    public void testListMigrationsRoute() {
        RestListMigrationsAction handler = new RestListMigrationsAction();
        List<RestListMigrationsAction.Route> routes = handler.routes();

        assertEquals(1, routes.size());
        assertEquals(RestRequest.Method.GET, routes.get(0).getMethod());
        assertEquals("/_plugins/_aosc/_list", routes.get(0).getPath());
        assertEquals("aosc_list_migrations", handler.getName());
    }

    /** Verify CleanupLeases registers a single POST route with the {@code aosc_cleanup_leases} handler name. */
    public void testCleanupLeasesRoutes() {
        RestCleanupLeasesAction handler = new RestCleanupLeasesAction();
        List<RestCleanupLeasesAction.Route> routes = handler.routes();

        assertEquals("aosc_cleanup_leases", handler.getName());
        assertEquals(1, routes.size());
        assertEquals(RestRequest.Method.POST, routes.get(0).getMethod());
        assertEquals("/_plugins/_aosc/_admin/_cleanup_leases", routes.get(0).getPath());
    }

    /**
     * An explicit empty {@code ?index=} query parameter is a client error. Cleanup must
     * reject it with {@link IllegalArgumentException} (which the REST framework surfaces
     * as 400) rather than silently falling back to the cluster-wide form, which would be
     * a destructive surprise.
     */
    public void testCleanupLeasesRejectsExplicitEmptyIndex() {
        RestCleanupLeasesAction handler = new RestCleanupLeasesAction();

        Map<String, String> params = new HashMap<>();
        params.put("index", ""); // present-but-empty — distinct from absent

        FakeRestRequest request = new FakeRestRequest.Builder(xContentRegistry()).withMethod(RestRequest.Method.POST)
            .withPath("/_plugins/_aosc/_admin/_cleanup_leases")
            .withParams(params)
            .build();

        IllegalArgumentException ex = expectThrows(IllegalArgumentException.class, () -> handler.prepareRequest(request, null));
        assertTrue(
            "error message must mention the empty [index] parameter, got: " + ex.getMessage(),
            ex.getMessage().contains("[index]") && ex.getMessage().contains("empty")
        );
    }

    /**
     * Companion to {@link #testCleanupLeasesRejectsExplicitEmptyIndex()}: when the
     * {@code index} parameter is absent (cluster-wide route), {@code prepareRequest}
     * must succeed without throwing. We do not exercise the dispatch (no client
     * available in this unit test) — we only verify the absent-vs-empty discrimination
     * lives correctly in the handler.
     */
    public void testCleanupLeasesAcceptsAbsentIndex() {
        RestCleanupLeasesAction handler = new RestCleanupLeasesAction();

        FakeRestRequest request = new FakeRestRequest.Builder(xContentRegistry()).withMethod(RestRequest.Method.POST)
            .withPath("/_plugins/_aosc/_admin/_cleanup_leases")
            .build();

        // prepareRequest returns a RestChannelConsumer (a lambda); invoking it would
        // need a client. Constructing the consumer is enough to prove the handler
        // accepted the request without throwing.
        assertNotNull(handler.prepareRequest(request, null));
    }

    /** Verify ClearClusterState route is POST /_plugins/_aosc/_admin/_clear_state */
    public void testClearClusterStateRoute() {
        RestClearClusterStateAction handler = new RestClearClusterStateAction();
        List<RestClearClusterStateAction.Route> routes = handler.routes();

        assertEquals(1, routes.size());
        assertEquals(RestRequest.Method.POST, routes.get(0).getMethod());
        assertEquals("/_plugins/_aosc/_admin/_clear_state", routes.get(0).getPath());
        assertEquals("aosc_clear_cluster_state", handler.getName());
    }

    /**
     * Verify StartMigration body parsing: JSON body with target, transform_script,
     * and alias produces correct {@link MigrationRequest} fields. Tests the
     * fromXContent parser directly since prepareRequest requires a real NodeClient.
     */
    public void testStartMigrationParsesBody() throws Exception {
        String json = "{\"target_index\": \"my-target\", "
            + "\"transform_script\": {\"type\": \"inline\", \"source\": \"ctx._source.remove('old')\"}, "
            + "\"alias\": \"my-alias\"}";

        XContentParser parser = XContentType.JSON.xContent()
            .createParser(xContentRegistry(), DeprecationHandler.THROW_UNSUPPORTED_OPERATION, json);

        MigrationRequest migrationRequest = MigrationRequest.fromXContent(parser);

        assertEquals("my-target", migrationRequest.getTargetIndex());
        assertTrue(migrationRequest.getTransformScript() instanceof InlineTransformScript);
        InlineTransformScript inline = (InlineTransformScript) migrationRequest.getTransformScript();
        assertEquals("ctx._source.remove('old')", inline.getSource());
        assertEquals("my-alias", migrationRequest.getAlias());
    }
}
