/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.coordinator;

import com.atlassian.opensearch.aosc.compat.MockClientFactory;
import com.atlassian.opensearch.aosc.model.MigrationDocument;
import com.atlassian.opensearch.aosc.model.MigrationRequestOptions;
import com.atlassian.opensearch.aosc.model.ShardRoutingMode;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.AsyncClientHelper;

import org.opensearch.ResourceAlreadyExistsException;
import org.opensearch.action.admin.indices.create.CreateIndexRequest;
import org.opensearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.opensearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.index.IndexResponse;
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

    @SuppressWarnings("unchecked")
    public void testCreateMigrationDocumentSwallowsVersionConflictOnReentry() throws Exception {
        var handle = mockHandleWithThreadPool();
        AtomicInteger calls = new AtomicInteger();
        doAnswer((InvocationOnMock invocation) -> {
            ActionListener<IndexResponse> listener = invocation.getArgument(1);
            int call = calls.incrementAndGet();
            if (call == 1) {
                listener.onResponse(mock(IndexResponse.class));
            } else {
                listener.onFailure(
                    new VersionConflictEngineException(new ShardId(".aosc-migrations", "_na_", 0), "mig-1", "document already exists")
                );
            }
            return null;
        }).when(handle.client()).index(any(IndexRequest.class), any());

        var service = newService(handle.helper());

        MigrationDocument doc = newDocument("mig-1");

        MigrationDocument first = await(service.createMigrationDocument(doc));
        assertEquals("mig-1", first.migrationId());

        MigrationDocument second = await(service.createMigrationDocument(doc));
        assertEquals("mig-1", second.migrationId());
        assertEquals(2, calls.get());
    }

    @SuppressWarnings("unchecked")
    public void testCreateMigrationDocumentPropagatesNonVersionConflictErrors() {
        var handle = mockHandleWithThreadPool();
        doAnswer((InvocationOnMock invocation) -> {
            ActionListener<IndexResponse> listener = invocation.getArgument(1);
            listener.onFailure(new RuntimeException("boom"));
            return null;
        }).when(handle.client()).index(any(IndexRequest.class), any());

        var service = newService(handle.helper());
        MigrationDocument doc = newDocument("mig-2");

        ExecutionException ex = expectThrows(ExecutionException.class, () -> service.createMigrationDocument(doc).get(5, TimeUnit.SECONDS));
        assertTrue("expected the original RuntimeException to propagate, got: " + ex.getCause(), ex.getCause() instanceof RuntimeException);
        assertEquals("boom", ex.getCause().getMessage());
    }

    // ---- Schema enforcement on an already-existing index ----

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testEnforceSchemaReconcilesAutoExpandReplicasOnExistingIndex() throws Exception {
        var handle = mockHandleWithThreadPool();

        // Index already exists -> triggers enforceSchema.
        doAnswer(inv -> {
            ((ActionListener) inv.getArgument(1)).onFailure(new ResourceAlreadyExistsException("index .aosc-migrations already exists"));
            return null;
        }).when(handle.indicesAdmin()).create(any(CreateIndexRequest.class), any());

        AtomicReference<UpdateSettingsRequest> settingsReq = new AtomicReference<>();
        doAnswer(inv -> {
            settingsReq.set(inv.getArgument(0));
            ActionListener listener = inv.getArgument(1);
            listener.onResponse(MockClientFactory.acknowledgedResponse(true));
            return null;
        }).when(handle.indicesAdmin()).updateSettings(any(UpdateSettingsRequest.class), any());

        AtomicInteger putMappingCalls = new AtomicInteger();
        doAnswer(inv -> {
            putMappingCalls.incrementAndGet();
            ActionListener listener = inv.getArgument(1);
            listener.onResponse(MockClientFactory.acknowledgedResponse(true));
            return null;
        }).when(handle.indicesAdmin()).putMapping(any(PutMappingRequest.class), any());

        await(newService(handle.helper()).ensureIndexExists());

        assertNotNull("enforceSchema must issue an updateSettings on the existing index", settingsReq.get());
        assertEquals("0-1", settingsReq.get().settings().get("index.auto_expand_replicas"));
        assertNull("number_of_shards is static and must not be reconciled", settingsReq.get().settings().get("index.number_of_shards"));
        assertEquals("mappings must still be enforced", 1, putMappingCalls.get());
    }

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

    private static MigrationDocumentService newService(AsyncClientHelper helper) {
        return new MigrationDocumentService(AoscLogger.create(MigrationDocumentService.class), helper);
    }

    private static MockClientFactory.Handle mockHandleWithThreadPool() {
        var handle = MockClientFactory.createHandle();
        ThreadContext threadContext = new ThreadContext(Settings.EMPTY);
        ThreadPool threadPool = mock(ThreadPool.class);
        when(threadPool.getThreadContext()).thenReturn(threadContext);
        when(handle.client().threadPool()).thenReturn(threadPool);
        return handle;
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
