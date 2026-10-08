/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc;

import com.atlassian.opensearch.aosc.action.start.StartMigrationAction;
import com.atlassian.opensearch.aosc.action.start.StartMigrationRequest;
import com.atlassian.opensearch.aosc.action.start.StartMigrationResponse;
import com.atlassian.opensearch.aosc.model.MigrationRequest;
import com.atlassian.opensearch.aosc.model.transform.InlineTransformScript;

import org.opensearch.action.delete.DeleteRequest;
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;

/** With {@code apply_to_deletes}, replayed deletes reach the documents the script renamed. */
@ClusterScope(scope = Scope.SUITE, numDataNodes = 2, numClientNodes = 0)
public class DeleteAwareTransformIT extends AoscIntegTestBase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        // Slow backfill so the sentinel delete lands after the backfill cutoff and is replayed.
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal))
            .put("aosc.backfill.controller.type", "fixed")
            .put("aosc.backfill.controller.batch.size", 1)
            .put("aosc.backfill.read.page_size", 25)
            .build();
    }

    public void testReplayedDeleteRemovesTheTransformedTargetDoc() throws Exception {
        String source = indexName("dat-src");
        String target = indexName("dat-tgt");
        createSourceAndTarget(source, target, 2, 4);
        index(source, "sentinel", -1);
        for (int i = 0; i < 300; i++) {
            index(source, "filler-" + i, i);
        }
        client().admin().indices().prepareRefresh(source).get();

        start(source, target, "dat-alias");

        assertBusyWithFixedSleepTime(
            () -> assertTrue(client().get(new GetRequest(target, "t-sentinel").routing("t-sentinel")).actionGet().isExists()),
            TimeValue.timeValueSeconds(60),
            TimeValue.timeValueMillis(100)
        );
        client().delete(new DeleteRequest(source, "sentinel").setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)).actionGet();
        client().admin().indices().prepareFlush(source).get();

        assertMigrationCompleted(source, 120);
        client().admin().indices().prepareRefresh(source, target).get();

        assertFalse(client().get(new GetRequest(target, "t-sentinel").routing("t-sentinel")).actionGet().isExists());
        assertTrue(client().get(new GetRequest(target, "t-filler-7").routing("t-filler-7")).actionGet().isExists());
        assertDocCountsMatch(source, target);
    }

    private void start(String source, String target, String alias) {
        InlineTransformScript script = new InlineTransformScript(DELETE_AWARE_PREFIX_SCRIPT, null);
        script.setApplyToDeletes(true);
        MigrationRequest request = new MigrationRequest().setSourceIndex(source)
            .setTargetIndex(target)
            .setAlias(alias)
            .setTransformScript(script);
        StartMigrationResponse response = client().execute(StartMigrationAction.INSTANCE, new StartMigrationRequest(request)).actionGet();
        assertTrue(response.body().accepted());
    }

    private void index(String index, String id, int value) {
        client().index(
            new IndexRequest(index).id(id)
                .source("{\"value\":" + value + "}", XContentType.JSON)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.NONE)
        ).actionGet();
    }
}
