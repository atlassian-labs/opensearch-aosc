/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.coordinator;

import com.atlassian.opensearch.aosc.model.MigrationDocument;
import com.atlassian.opensearch.aosc.model.MigrationRequestOptions;
import com.atlassian.opensearch.aosc.model.ShardRoutingMode;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.ResourceAlreadyExistsException;
import org.opensearch.action.admin.indices.create.CreateIndexRequest;
import org.opensearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.opensearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.action.support.master.AcknowledgedResponse;
import org.opensearch.client.AdminClient;
import org.opensearch.client.Client;
import org.opensearch.client.IndicesAdminClient;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.engine.VersionConflictEngineException;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.mockito.invocation.InvocationOnMock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MigrationDocumentServiceTests extends OpenSearchTestCase {

    // ---- Constants ----
    public void testMigrationsIndexConstant() {
        assertEquals(".aosc-migrations", MigrationDocumentService.MIGRATIONS_INDEX);
    }

    public void testSchemaResourceConstant() {
        assertEquals("/aosc-migrations-schema.json", MigrationDocumentService.SCHEMA_RESOURCE);
    }

    public void testConstructorRejectsNullClient() {
        expectThrows(
            NullPointerException.class,
            () -> new MigrationDocumentService(AoscLogger.create(MigrationDocumentService.class), null)
        );
    }

    // ---- Monolith document: no child doc ID scheme ----
    // shardProgressDocId was removed — shard progress is now embedded in the migration document.

    // ---- Schema resource ----
    public void testSchemaResourceIsLoadable() {
        assertNotNull(MigrationDocumentService.class.getResourceAsStream(MigrationDocumentService.SCHEMA_RESOURCE));
    }

    // ---- B047: createMigrationDocument must be idempotent across CM failover ----

    /**
     * Regression test for B047: when the previous CM persisted the migration document
     * and then died before publishing the {@code INITIALIZING → ACTIVE} cluster-state
     * update, the new CM re-enters {@code onInitializing} via {@code justBecameCM} and
     * tries to create the same document again. The service must treat the resulting
     * {@link VersionConflictEngineException} as success rather than failing the
     * migration.
     */
    public void testCreateMigrationDocumentSwallowsVersionConflictOnReentry() throws Exception {
        Client client = mockClient();
        AtomicInteger calls = new AtomicInteger();
        doAnswer((InvocationOnMock invocation) -> {
            ActionListener<IndexResponse> listener = invocation.getArgument(1);
            int call = calls.incrementAndGet();
            if (call == 1) {
                // Simulate the first CM successfully creating the document.
                listener.onResponse(mock(IndexResponse.class));
            } else {
                // Simulate the second CM hitting create=true after failover.
                listener.onFailure(
                    new VersionConflictEngineException(new ShardId(".aosc-migrations", "_na_", 0), "mig-1", "document already exists")
                );
            }
            return null;
        }).when(client).index(any(IndexRequest.class), any());

        MigrationDocumentService service = newService(client);

        MigrationDocument doc = newDocument("mig-1");

        // First create succeeds.
        MigrationDocument first = await(service.createMigrationDocument(doc));
        assertEquals("mig-1", first.migrationId());

        // Second create (post-failover re-entry) must NOT throw — must return the same doc.
        MigrationDocument second = await(service.createMigrationDocument(doc));
        assertEquals("mig-1", second.migrationId());
        assertEquals(2, calls.get());
    }

    public void testCreateMigrationDocumentPropagatesNonVersionConflictErrors() {
        Client client = mockClient();
        doAnswer((InvocationOnMock invocation) -> {
            ActionListener<IndexResponse> listener = invocation.getArgument(1);
            listener.onFailure(new RuntimeException("boom"));
            return null;
        }).when(client).index(any(IndexRequest.class), any());

        MigrationDocumentService service = newService(client);
        MigrationDocument doc = newDocument("mig-2");

        ExecutionException ex = expectThrows(ExecutionException.class, () -> service.createMigrationDocument(doc).get(5, TimeUnit.SECONDS));
        assertTrue("expected the original RuntimeException to propagate, got: " + ex.getCause(), ex.getCause() instanceof RuntimeException);
        assertEquals("boom", ex.getCause().getMessage());
    }

    // ---- Schema enforcement on an already-existing index ----

    /**
     * When {@code .aosc-migrations} already exists, {@code ensureIndexExists()} enforces the schema.
     * It must reconcile the dynamically-updatable {@code auto_expand_replicas} setting (so indices
     * created with 0 replicas by an older plugin become resilient) in addition to the mappings.
     */
    @SuppressWarnings("unchecked")
    public void testEnforceSchemaReconcilesAutoExpandReplicasOnExistingIndex() throws Exception {
        Client client = mockClient();
        AdminClient admin = mock(AdminClient.class);
        IndicesAdminClient indices = mock(IndicesAdminClient.class);
        when(client.admin()).thenReturn(admin);
        when(admin.indices()).thenReturn(indices);

        // Index already exists -> triggers enforceSchema.
        doAnswer(inv -> {
            ((ActionListener<Object>) inv.getArgument(1)).onFailure(
                new ResourceAlreadyExistsException("index .aosc-migrations already exists")
            );
            return null;
        }).when(indices).create(any(CreateIndexRequest.class), any());

        AtomicReference<UpdateSettingsRequest> settingsReq = new AtomicReference<>();
        doAnswer(inv -> {
            settingsReq.set(inv.getArgument(0));
            ((ActionListener<AcknowledgedResponse>) inv.getArgument(1)).onResponse(new AcknowledgedResponse(true) {
            });
            return null;
        }).when(indices).updateSettings(any(UpdateSettingsRequest.class), any());

        AtomicInteger putMappingCalls = new AtomicInteger();
        doAnswer(inv -> {
            putMappingCalls.incrementAndGet();
            ((ActionListener<AcknowledgedResponse>) inv.getArgument(1)).onResponse(new AcknowledgedResponse(true) {
            });
            return null;
        }).when(indices).putMapping(any(PutMappingRequest.class), any());

        await(newService(client).ensureIndexExists());

        assertNotNull("enforceSchema must issue an updateSettings on the existing index", settingsReq.get());
        assertEquals("0-1", settingsReq.get().settings().get("index.auto_expand_replicas"));
        // Static create-time settings must not be pushed to a live index.
        assertNull("number_of_shards is static and must not be reconciled", settingsReq.get().settings().get("index.number_of_shards"));
        assertEquals("mappings must still be enforced", 1, putMappingCalls.get());
    }

    /**
     * The reconcilable-settings extraction keeps every dynamic setting — resolving both flat and
     * nested JSON forms — and drops only the non-idempotent (static, create-time) ones.
     */
    public void testReconcilableSettingsKeepsDynamicAndDropsNonIdempotent() {
        Settings flat = MigrationDocumentService.reconcilableSettings(
            Map.of("index.number_of_shards", 1, "index.auto_expand_replicas", "0-1", "index.hidden", true)
        );
        assertEquals("0-1", flat.get("index.auto_expand_replicas"));
        assertEquals("dynamic index.hidden must be kept", "true", flat.get("index.hidden"));
        assertNull("static number_of_shards must be dropped", flat.get("index.number_of_shards"));

        Settings nested = MigrationDocumentService.reconcilableSettings(
            Map.of("index", Map.of("auto_expand_replicas", "0-1", "number_of_shards", 1))
        );
        assertEquals("nested form must resolve to the same flat key", "0-1", nested.get("index.auto_expand_replicas"));
        assertNull(nested.get("index.number_of_shards"));

        assertTrue(MigrationDocumentService.reconcilableSettings(Map.of()).isEmpty());
        assertTrue(MigrationDocumentService.reconcilableSettings(null).isEmpty());
    }

    // ---- helpers ----

    private static MigrationDocumentService newService(Client client) {
        return new MigrationDocumentService(AoscLogger.create(MigrationDocumentService.class), client);
    }

    private static Client mockClient() {
        Client client = mock(Client.class);
        ThreadContext threadContext = new ThreadContext(Settings.EMPTY);
        org.opensearch.threadpool.ThreadPool threadPool = mock(ThreadPool.class);
        when(threadPool.getThreadContext()).thenReturn(threadContext);
        when(client.threadPool()).thenReturn(threadPool);
        return client;
    }

    private static MigrationDocument newDocument(String id) {
        return MigrationDocument.builder()
            .migrationId(id)
            .sourceIndex("src")
            .targetIndex("tgt")
            .alias("a")
            .phase(CoordinatorPhase.INITIALIZING)
            .options(new MigrationRequestOptions())
            .shardRoutingMode(ShardRoutingMode.BULK_API)
            .startTimeMillis(1L)
            .lastUpdatedMillis(1L)
            .build();
    }

    private static <T> T await(CompletableFuture<T> future) throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(5, TimeUnit.SECONDS);
    }

}
