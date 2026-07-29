/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.coordinator;

import com.atlassian.opensearch.aosc.model.MigrationDocument;
import com.atlassian.opensearch.aosc.model.ShardProgressDocument;
import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.AsyncClientHelper;
import com.atlassian.opensearch.aosc.utils.AsyncUtils;
import com.atlassian.opensearch.aosc.utils.jackson.JacksonHelper;

import org.opensearch.ResourceAlreadyExistsException;
import org.opensearch.action.NoShardAvailableActionException;
import org.opensearch.action.admin.indices.create.CreateIndexRequest;
import org.opensearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.opensearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.search.SearchPhaseExecutionException;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.ShardSearchFailure;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.index.engine.VersionConflictEngineException;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.SearchHit;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.sort.SortOrder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Service for reading and writing migration documents in the
 * {@code .aosc-migrations} system index (Tier 1).
 *
 * <p>Each migration is stored as a single monolith document containing
 * both migration-level config ({@link MigrationDocument}) and embedded
 * per-shard progress ({@link ShardProgressDocument}) in the {@code shards} map.</p>
 *
 * <p>All public methods return {@link CompletableFuture} for cleaner
 * async composition. Internally bridges to OpenSearch's ActionListener API.</p>
 */
public class MigrationDocumentService {

    private final AoscLogger logger;
    static final String MIGRATIONS_INDEX = ".aosc-migrations";
    static final String SCHEMA_RESOURCE = "/aosc-migrations-schema.json";
    /**
     * Static, create-time settings that cannot be updated on a live index — a blocklist. Every
     * other setting in the schema is dynamic and is reconciled onto pre-existing indices.
     */
    private static final Set<String> NON_IDEMPOTENT_SETTINGS = Set.of("index.number_of_shards");

    private final AsyncClientHelper clientHelper;
    private final ThreadContext threadContext;

    /**
     * Thread-safe barrier for index creation. The first caller creates the future,
     * all subsequent callers share it. Only one create-index RPC ever fires.
     */
    private final AtomicReference<CompletableFuture<Void>> indexCreated = new AtomicReference<>();

    public MigrationDocumentService(AoscLogger logger, AsyncClientHelper clientHelper) {
        this.logger = Objects.requireNonNull(logger, "logger").forClass(MigrationDocumentService.class);
        this.clientHelper = Objects.requireNonNull(clientHelper, "clientHelper");
        this.threadContext = clientHelper.threadContext();
    }

    /**
     * Resets the cached index-creation future so that the next call to
     * {@link #ensureIndexExists()} will re-check and recreate the index if needed.
     * Used by integration tests after the test framework wipes all indices.
     */
    public void resetIndexCreated() {
        indexCreated.set(null);
    }

    /**
     * Stash the calling thread's context so that reads/writes to the
     * system index are executed with a clean (privileged) thread context.
     * This is required for system index access from transport threads.
     */
    private <T> CompletableFuture<T> withStashedContext(Supplier<CompletableFuture<T>> operation) {
        try (ThreadContext.StoredContext ignored = threadContext.stashContext()) {
            return operation.get();
        }
    }

    public CompletableFuture<Void> ensureIndexExists() {
        return withStashedContext(this::doEnsureIndexExists);
    }

    private CompletableFuture<Void> doEnsureIndexExists() {
        CompletableFuture<Void> existing = indexCreated.get();
        if (existing != null) {
            return existing;
        }
        CompletableFuture<Void> newFuture = createIndex();
        if (indexCreated.compareAndSet(null, newFuture)) {
            return newFuture;
        }
        return indexCreated.get();
    }

    private static final int MAX_CREATE_INDEX_RETRIES = 3;

    private CompletableFuture<Void> createIndex() {
        Map<String, Object> schema = loadSchema();
        return createIndexWithRetry(schema, MAX_CREATE_INDEX_RETRIES);
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Void> createIndexWithRetry(Map<String, Object> schema, int attempt) {
        CreateIndexRequest createRequest = new CreateIndexRequest(MIGRATIONS_INDEX);
        if (schema != null) {
            Map<String, Object> mappings = (Map<String, Object>) schema.get("mappings");
            if (mappings != null) {
                createRequest.mapping(mappings);
            }
            Map<String, Object> settings = (Map<String, Object>) schema.get("settings");
            if (settings != null) {
                createRequest.settings(settings);
            }
        }
        return clientHelper.executeCreateIndexAsync(createRequest).handle((response, ex) -> {
            if (ex == null) {
                logger.info("Created system index: {}", MIGRATIONS_INDEX);
                return CompletableFuture.<Void>completedFuture(null);
            }
            // "already exists" can arrive wrapped (CompletionException / RemoteTransportException)
            // when the create is routed to a remote cluster-manager node — match it anywhere in the
            // cause chain, not just the local unwrapped form.
            if (AsyncUtils.hasCauseOfType(ex, ResourceAlreadyExistsException.class)) {
                logger.debug("System index {} already exists — enforcing schema", MIGRATIONS_INDEX);
                return enforceSchema(schema);
            }
            if (attempt <= 1) {
                logger.error("Failed to create system index {} after {} attempts", MIGRATIONS_INDEX, MAX_CREATE_INDEX_RETRIES, ex);
                return CompletableFuture.<Void>failedFuture(ex);
            }
            logger.warn("Failed to create system index {}, retrying ({} attempts left)", MIGRATIONS_INDEX, attempt - 1, ex);
            return createIndexWithRetry(schema, attempt - 1);
        }).thenCompose(f -> f);
    }

    /**
     * Bring an already-existing index up to the current schema. Called on every cluster-manager
     * election, so pre-existing indices pick up the mappings ({@code dynamic: false},
     * {@code shards.enabled: false}) and the reconcilable settings ({@code auto_expand_replicas} —
     * indices created with 0 replicas by an older plugin become resilient with no manual step).
     */
    private CompletableFuture<Void> enforceSchema(Map<String, Object> schema) {
        if (schema == null) {
            return CompletableFuture.completedFuture(null);
        }
        return enforceMappings(schema).thenCompose(ignored -> enforceSettings(schema));
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Void> enforceSettings(Map<String, Object> schema) {
        Settings reconciled = reconcilableSettings((Map<String, Object>) schema.get("settings"));
        if (reconciled.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        UpdateSettingsRequest req = new UpdateSettingsRequest(MIGRATIONS_INDEX).settings(reconciled);
        return clientHelper.executeUpdateSettingsAsync(req).<Void>thenApply(r -> {
            logger.debug("Enforced settings on {}", MIGRATIONS_INDEX);
            return null;
        }).exceptionally(e -> {
            logger.error("Failed to enforce settings on {}", MIGRATIONS_INDEX, e);
            throw (e instanceof CompletionException) ? (CompletionException) e : new CompletionException(e);
        });
    }

    /**
     * The subset of the schema's settings block that is safe to reconcile onto a live index:
     * everything except the {@link #NON_IDEMPOTENT_SETTINGS} static, create-time settings. The raw
     * map is normalised through {@link Settings} so both flat ({@code "index.auto_expand_replicas"})
     * and nested ({@code "index": {"auto_expand_replicas": ...}}) JSON forms resolve to the same
     * flat keys before the blocklist is applied.
     */
    static Settings reconcilableSettings(Map<String, Object> rawSettings) {
        if (rawSettings == null || rawSettings.isEmpty()) {
            return Settings.EMPTY;
        }
        Settings normalised = Settings.builder().loadFromMap(rawSettings).build();
        Settings.Builder reconcilable = Settings.builder();
        for (String key : normalised.keySet()) {
            if (!NON_IDEMPOTENT_SETTINGS.contains(key)) {
                reconcilable.put(key, normalised.get(key));
            }
        }
        return reconcilable.build();
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Void> enforceMappings(Map<String, Object> schema) {
        Map<String, Object> mappings = (Map<String, Object>) schema.get("mappings");
        if (mappings == null) {
            return CompletableFuture.completedFuture(null);
        }
        PutMappingRequest req = new PutMappingRequest(MIGRATIONS_INDEX).source(mappings);
        return clientHelper.executePutMappingAsync(req).<Void>thenApply(r -> {
            logger.debug("Enforced mappings on {}", MIGRATIONS_INDEX);
            return null;
        }).exceptionally(e -> {
            logger.error("Failed to enforce schema on {} — index may have wrong mappings", MIGRATIONS_INDEX, e);
            throw (e instanceof CompletionException) ? (CompletionException) e : new CompletionException(e);
        });
    }

    private static Map<String, Object> loadSchema() {
        AoscLogger staticLogger = AoscLogger.create(MigrationDocumentService.class);
        try (InputStream is = MigrationDocumentService.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (is == null) {
                staticLogger.warn("Schema resource not found: {}", SCHEMA_RESOURCE);
                return null;
            }
            return JacksonHelper.readValue(is, Map.class);
        } catch (IOException e) {
            staticLogger.warn("Failed to load schema resource: {}", SCHEMA_RESOURCE, e);
            return null;
        }
    }

    public CompletableFuture<MigrationDocument> createMigrationDocument(MigrationDocument doc) {
        // The .aosc-migrations index is pre-built on CM election by AoscCoordinatorService.
        return withStashedContext(() -> doCreateMigrationDocument(doc));
    }

    private CompletableFuture<MigrationDocument> doCreateMigrationDocument(MigrationDocument doc) {
        try {
            IndexRequest indexRequest = new IndexRequest(MIGRATIONS_INDEX).id(doc.migrationId())
                .source(JacksonHelper.writeAsBytes(doc), XContentType.JSON)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .create(true);
            return clientHelper.executeIndexAsync(indexRequest).thenApply(response -> {
                logger.info("Created migration document [{}]", doc.migrationId());
                return doc;
            }).exceptionally(e -> {
                // Idempotent on CM failover (B047): doc ID is the deterministic migrationId,
                // so a conflict means a prior CM wrote it before dying — treat as success.
                if (AsyncUtils.hasCauseOfType(e, VersionConflictEngineException.class)) {
                    logger.info("Migration document [{}] already exists (CM failover re-entry), proceeding", doc.migrationId());
                    return doc;
                }
                throw (e instanceof RuntimeException) ? (RuntimeException) e : new CompletionException(e);
            });
        } catch (IOException e) {
            return CompletableFuture.failedFuture(new UncheckedIOException("Failed to create migration document: " + e.getMessage(), e));
        }
    }

    // ---- Status Read API ----

    /**
     * Find the most recent migration document for a given source index.
     * Returns null if no migration exists for that source index, or if
     * the index doesn't exist yet.
     */
    public CompletableFuture<MigrationDocument> getMigrationBySourceIndex(String sourceIndex) {
        return withStashedContext(() -> doGetMigrationBySourceIndex(sourceIndex));
    }

    private CompletableFuture<MigrationDocument> doGetMigrationBySourceIndex(String sourceIndex) {
        SearchSourceBuilder searchSource = new SearchSourceBuilder().size(1)
            .query(QueryBuilders.termQuery("source_index", sourceIndex))
            .sort("start_time_millis", SortOrder.DESC);

        SearchRequest searchReq = new SearchRequest(MIGRATIONS_INDEX).source(searchSource);

        return clientHelper.executeSearchAsync(searchReq).thenApply(response -> {
            SearchHit[] hits = response.getHits().getHits();
            if (hits.length == 0) {
                return null;
            }
            try {
                return JacksonHelper.readValue(hits[0].getSourceAsString(), MigrationDocument.class);
            } catch (IOException e) {
                throw new RuntimeException("Failed to parse migration document: " + e.getMessage(), e);
            }
        }).exceptionally(ex -> {
            Throwable cause = ex.getCause();
            if (cause instanceof IndexNotFoundException || isAllShardsUnavailable(cause)) {
                return null;
            }
            throw (ex instanceof RuntimeException) ? (RuntimeException) ex : new RuntimeException(ex);
        });
    }

    public CompletableFuture<List<MigrationDocument>> listMigrations(List<CoordinatorPhase> phases, int size) {
        return withStashedContext(() -> doListMigrations(phases, size));
    }

    private CompletableFuture<List<MigrationDocument>> doListMigrations(List<CoordinatorPhase> phases, int size) {
        // Every document in the index is a migration document (monolith schema).
        // Filter by phase in Java since search-level phase filtering is unreliable
        // when the schema mapping may not be applied (auto-detected text fields
        // don't support term queries without .keyword subfield).
        int fetchSize = Math.min(size * 5 + 50, 10000);
        SearchSourceBuilder source = new SearchSourceBuilder().size(fetchSize).query(QueryBuilders.matchAllQuery());

        SearchRequest searchReq = new SearchRequest(MIGRATIONS_INDEX).source(source);

        return clientHelper.executeSearchAsync(searchReq).thenApply(response -> {
            List<MigrationDocument> result = new ArrayList<>();
            for (SearchHit hit : response.getHits().getHits()) {
                try {
                    MigrationDocument doc = JacksonHelper.readValue(hit.getSourceAsString(), MigrationDocument.class);
                    if (phases == null || phases.isEmpty() || phases.contains(doc.phase())) {
                        result.add(doc);
                        if (result.size() >= size) {
                            break;
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to parse migration document: " + e.getMessage(), e);
                }
            }
            return result;
        }).exceptionally(ex -> {
            Throwable cause = ex.getCause();
            if (cause instanceof IndexNotFoundException || isAllShardsUnavailable(cause)) {
                return List.of();
            }
            throw (ex instanceof RuntimeException) ? (RuntimeException) ex : new RuntimeException(ex);
        });
    }

    /**
     * Returns {@code true} when the cause is a {@link SearchPhaseExecutionException} where
     * every shard failure (if any) is {@link NoShardAvailableActionException}. This happens
     * when {@code .aosc-migrations} exists in the cluster state but its primary shard is
     * still initializing (not yet searchable). An empty {@code shardFailures} array with
     * "all shards failed" also qualifies — some OS versions don't populate per-shard details
     * when no shard copy was available for the search request.
     */
    private static boolean isAllShardsUnavailable(Throwable cause) {
        if (!(cause instanceof SearchPhaseExecutionException)) {
            return false;
        }
        SearchPhaseExecutionException spee = (SearchPhaseExecutionException) cause;
        ShardSearchFailure[] failures = spee.shardFailures();
        if (failures.length == 0) {
            // OS 2.17+ may not populate per-shard details; fall back to message check.
            return spee.getMessage() != null && spee.getMessage().contains("all shards failed");
        }
        for (ShardSearchFailure f : failures) {
            if (!(f.getCause() instanceof NoShardAvailableActionException)) {
                return false;
            }
        }
        return true;
    }

    // ---- Terminal Persistence & Shard Progress ----

    /**
     * Persist the final state of a migration as a single monolith document containing
     * both migration-level fields and embedded shard progress. Called once at terminal.
     *
     * @param finalDoc      the complete migration document with terminal phase, cutover context, error
     * @param shardProgress map of shard ordinal → progress document (embedded into the doc)
     * @return a future that completes when the index request finishes
     */
    public CompletableFuture<Void> persistFinalState(MigrationDocument finalDoc, Map<Integer, ShardProgressDocument> shardProgress) {
        return withStashedContext(() -> doPersistFinalState(finalDoc, shardProgress));
    }

    private CompletableFuture<Void> doPersistFinalState(MigrationDocument finalDoc, Map<Integer, ShardProgressDocument> shardProgress) {
        MigrationDocument docWithShards = finalDoc.toBuilder().shards(shardProgress).build();
        try {
            IndexRequest indexRequest = new IndexRequest(MIGRATIONS_INDEX).id(docWithShards.migrationId())
                .source(JacksonHelper.writeAsBytes(docWithShards), XContentType.JSON)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
            return clientHelper.executeIndexAsync(indexRequest).thenAccept(response -> {
                logger.info(
                    "Persisted final state for migration [{}]: phase={}, shards={}",
                    docWithShards.migrationId(),
                    docWithShards.phase(),
                    shardProgress.size()
                );
            });
        } catch (IOException e) {
            return CompletableFuture.failedFuture(new RuntimeException("Failed to persist final state: " + e.getMessage(), e));
        }
    }

    /**
     * Fetch all shard progress for a migration from the embedded shards map
     * in the monolith migration document. Single GET by migration ID.
     */
    public CompletableFuture<Map<Integer, ShardProgressDocument>> getAllShardProgress(String migrationId) {
        return withStashedContext(() -> doGetAllShardProgress(migrationId));
    }

    private CompletableFuture<Map<Integer, ShardProgressDocument>> doGetAllShardProgress(String migrationId) {
        return clientHelper.executeGetAsync(new GetRequest(MIGRATIONS_INDEX, migrationId)).thenApply(response -> {
            if (!response.isExists()) {
                return Map.<Integer, ShardProgressDocument>of();
            }
            try {
                MigrationDocument doc = JacksonHelper.readValue(response.getSourceAsString(), MigrationDocument.class);
                return doc.shards();
            } catch (IOException e) {
                throw new RuntimeException("Failed to parse migration document for shard progress: " + e.getMessage(), e);
            }
        });
    }
}
