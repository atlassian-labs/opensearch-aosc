/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.status;

import com.atlassian.opensearch.aosc.model.MigrationDocument;
import com.atlassian.opensearch.aosc.model.ShardProgressDocument;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.model.phase.ShardPhase;

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.Map;

public class GetMigrationStatusResponseTests extends OpenSearchTestCase {

    public void testRoundTrip() throws IOException {
        ShardProgressDocument shard0 = ShardProgressDocument.builder()
            .phase(ShardPhase.BACKFILLING)
            .lastReplayedSeqNo(42L)
            .backfillCutoffSeqNo(100L)
            .backfill(
                ShardProgressDocument.PhaseMetrics.builder()
                    .status(ShardProgressDocument.PhaseStatus.IN_PROGRESS)
                    .startSeqNo(0)
                    .targetSeqNo(100)
                    .documentsIndexed(50)
                    .startTimeMillis(1000L)
                    .build()
            )
            .build();

        MigrationDocument doc = MigrationDocument.builder()
            .migrationId("mig-1")
            .sourceIndex("src-idx")
            .targetIndex("tgt-idx")
            .alias("my-alias")
            .phase(CoordinatorPhase.ACTIVE)
            .startTimeMillis(1000L)
            .lastUpdatedMillis(2000L)
            .shards(Map.of(0, shard0))
            .build();

        GetMigrationStatusResponse original = new GetMigrationStatusResponse(doc);
        assertEquals("mig-1", original.body().migrationId());

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        GetMigrationStatusResponse deserialized = new GetMigrationStatusResponse(in);

        assertEquals("mig-1", deserialized.body().migrationId());
        assertEquals("my-alias", deserialized.body().alias());
        assertEquals(CoordinatorPhase.ACTIVE, deserialized.body().phase());
        assertEquals(1, deserialized.body().shards().size());
        assertEquals(ShardPhase.BACKFILLING, deserialized.body().shards().get(0).phase());
        assertEquals(42L, deserialized.body().shards().get(0).lastReplayedSeqNo());
        assertEquals(50L, deserialized.body().shards().get(0).backfill().documentsIndexed());
    }
}
