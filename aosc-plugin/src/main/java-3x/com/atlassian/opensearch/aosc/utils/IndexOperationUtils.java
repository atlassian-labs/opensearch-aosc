/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils;

import com.atlassian.opensearch.aosc.action.cleanup.CleanupLeasesResult;
import com.atlassian.opensearch.aosc.service.worker.RetentionLeaseManager;

import lombok.Value;
import lombok.experimental.Accessors;

import org.opensearch.ExceptionsHelper;
import org.opensearch.OpenSearchTimeoutException;
import org.opensearch.action.admin.cluster.health.ClusterHealthRequest;
import org.opensearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.opensearch.action.admin.indices.flush.FlushRequest;
import org.opensearch.action.admin.indices.readonly.AddIndexBlockRequest;
import org.opensearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.opensearch.action.admin.indices.stats.IndicesStatsAction;
import org.opensearch.action.admin.indices.stats.IndicesStatsRequest;
import org.opensearch.action.admin.indices.stats.IndicesStatsResponse;
import org.opensearch.action.admin.indices.stats.ShardStats;
import org.opensearch.action.support.IndicesOptions;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.seqno.RetentionLease;
import org.opensearch.index.seqno.RetentionLeaseActions;
import org.opensearch.index.seqno.RetentionLeaseNotFoundException;
import org.opensearch.index.seqno.RetentionLeaseStats;
import org.opensearch.transport.client.Client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Utility class for async index operations — write-block management, alias swaps, shard flushes,
 * and retention lease discovery/cleanup.
 * All methods return {@link CompletableFuture}s that complete on the transport callback thread.
 */
public class IndexOperationUtils {

    private final AoscLogger logger;

    private final Client client;

    public IndexOperationUtils(AoscLogger logger, Client client) {
        this.logger = Objects.requireNonNull(logger, "logger").forClass(IndexOperationUtils.class);
        this.client = client;
    }

    /**
     * Disable shard rebalancing on the given index to prevent shard relocations during migration.
     * Sets {@code index.routing.rebalance.enable} to {@code none}.
     *
     * @return a future that completes when the setting is applied
     */
    public CompletableFuture<Void> disableRebalance(String index) {
        UpdateSettingsRequest req = new UpdateSettingsRequest(index);
        req.settings(Settings.builder().put("index.routing.rebalance.enable", "none"));
        return AsyncClientHelper.executeUpdateSettingsAsync(client, req).thenApply(response -> null);
    }

    /**
     * Restore shard rebalancing on the given index. Sets {@code index.routing.rebalance.enable}
     * to {@code all} (the default). Called when a migration reaches a terminal phase.
     *
     * @return a future that completes when the setting is restored
     */
    public CompletableFuture<Void> restoreRebalance(String index) {
        UpdateSettingsRequest req = new UpdateSettingsRequest(index);
        req.settings(Settings.builder().put("index.routing.rebalance.enable", "all"));
        return AsyncClientHelper.executeUpdateSettingsAsync(client, req).thenApply(response -> null);
    }

    /**
     * Applies a write block using {@code addBlock(WRITE)} which waits for all in-flight
     * writes to complete (shard-level permit acquisition), unlike update-settings. See B048.
     */
    public CompletableFuture<Void> applyWriteBlock(String index) {
        AddIndexBlockRequest req = new AddIndexBlockRequest(IndexMetadata.APIBlock.WRITE, index);
        return AsyncClientHelper.executeAddIndexBlockAsync(client, req).thenApply(response -> null);
    }

    /**
     * Remove write-block from source index after successful cutover.
     */
    public CompletableFuture<Void> removeWriteBlock(String sourceIndex) {
        UpdateSettingsRequest req = new UpdateSettingsRequest(sourceIndex);
        req.settings(Settings.builder().put("index.blocks.write", false));
        return AsyncClientHelper.executeUpdateSettingsAsync(client, req).thenApply(response -> null);
    }

    /**
     * Swap alias from source index to target index.
     */
    public CompletableFuture<Void> swapAlias(String sourceIndex, String targetIndex, String alias) {
        IndicesAliasesRequest req = new IndicesAliasesRequest();
        req.addAliasAction(IndicesAliasesRequest.AliasActions.remove().index(sourceIndex).alias(alias));
        req.addAliasAction(IndicesAliasesRequest.AliasActions.add().index(targetIndex).alias(alias));
        return AsyncClientHelper.executeAliasesAsync(client, req).thenApply(response -> null);
    }

    /** Flush via client API (transport layer) — unlike {@code IndexShard.flush()}, this advances the GCP. */
    public CompletableFuture<Void> flushIndex(String index) {
        FlushRequest req = new FlushRequest(index).force(true).waitIfOngoing(true);
        return AsyncClientHelper.executeFlushAsync(client, req).thenApply(response -> null);
    }

    // ---- Index settings helpers ----

    /**
     * Capture current values for the given setting keys from an index's metadata.
     * If a setting is not explicitly set (at its default), the captured value is {@code null}.
     */
    public static Map<String, String> captureSettings(IndexMetadata indexMeta, Set<String> settingKeys) {
        Settings current = indexMeta.getSettings();
        Map<String, String> captured = new HashMap<>();
        for (String key : settingKeys) {
            captured.put(key, current.get(key));
        }
        return captured;
    }

    /**
     * Apply settings to an index. Entries with {@code null} values are reset to defaults via
     * {@link org.opensearch.common.settings.Settings.Builder#putNull(String)}.
     */
    public CompletableFuture<Void> applySettings(String index, Map<String, String> settings) {
        Settings.Builder sb = Settings.builder();
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            if (entry.getValue() != null) {
                sb.put(entry.getKey(), entry.getValue());
            } else {
                sb.putNull(entry.getKey());
            }
        }
        UpdateSettingsRequest req = new UpdateSettingsRequest(index);
        req.settings(sb);
        logger.info("Applying settings on [{}]: {}", index, settings);
        return AsyncClientHelper.executeUpdateSettingsAsync(client, req).thenApply(r -> null);
    }

    /**
     * Wait for an index to reach GREEN status (all primary + replica shards STARTED).
     * Scoped to the specified index only — health of other indices is irrelevant.
     */
    public CompletableFuture<Void> waitForGreen(String index, TimeValue timeout) {
        ClusterHealthRequest req = new ClusterHealthRequest(index);
        req.waitForGreenStatus();
        req.timeout(timeout);
        logger.info("Waiting for [{}] to reach GREEN (timeout: {})", index, timeout);
        return AsyncClientHelper.executeClusterHealthAsync(client, req).thenAccept(response -> {
            if (response.isTimedOut()) {
                throw new OpenSearchTimeoutException(
                    "index [{}] did not reach GREEN within [{}]; status={}, active={}, unassigned={}",
                    index,
                    timeout,
                    response.getStatus(),
                    response.getActiveShards(),
                    response.getUnassignedShards()
                );
            }
            logger.info("[{}] is GREEN: {} active shards", index, response.getActiveShards());
        });
    }

    // ---- Retention lease discovery and cleanup ----

    /** Discover AOSC-owned retention leases, deduplicated across shard copies. Empty indices = all. */
    public CompletableFuture<List<MatchedLease>> findAoscLeases(String[] indices) {
        IndicesStatsRequest statsRequest = new IndicesStatsRequest();
        statsRequest.clear();
        // LENIENT_EXPAND_OPEN excludes hidden indices from wildcard expansion.
        // .aosc-migrations is hidden, so it won't be included in cluster-wide stats requests.
        statsRequest.indicesOptions(IndicesOptions.LENIENT_EXPAND_OPEN);
        statsRequest.indices(indices);
        return AsyncClientHelper.executeAsync(client, IndicesStatsAction.INSTANCE, statsRequest)
            .thenApply(IndexOperationUtils::collectAoscLeases);
    }

    /** Remove a single AOSC lease. {@link RetentionLeaseNotFoundException} is treated as success. */
    public CompletableFuture<CleanupLeasesResult.LeaseInfo> removeAoscLease(MatchedLease matched) {
        RetentionLeaseActions.RemoveRequest req = new RetentionLeaseActions.RemoveRequest(matched.shardId(), matched.lease().id());
        return AsyncClientHelper.executeAsync(client, RetentionLeaseActions.Remove.INSTANCE, req)
            .thenApply(r -> toLeaseInfo(matched, /* released */ true, /* error */ null))
            .exceptionally(e -> {
                if (AsyncUtils.hasCauseOfType(e, RetentionLeaseNotFoundException.class)) {
                    return toLeaseInfo(matched, /* released */ true, /* error */ null);
                }
                logger.warn("Failed to remove retention lease [{}] on shard [{}]", matched.lease().id(), matched.shardId(), e);
                return toLeaseInfo(matched, /* released */ false, formatErrorMessage(e));
            });
    }

    /** Remove all matched leases concurrently. Per-lease failures are captured, not propagated. */
    public CompletableFuture<List<CleanupLeasesResult.LeaseInfo>> removeAllAoscLeases(List<MatchedLease> matched) {
        List<CompletableFuture<CleanupLeasesResult.LeaseInfo>> futures = new ArrayList<>(matched.size());
        for (MatchedLease m : matched) {
            futures.add(removeAoscLease(m));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenApply(v -> {
            List<CleanupLeasesResult.LeaseInfo> results = new ArrayList<>(futures.size());
            for (CompletableFuture<CleanupLeasesResult.LeaseInfo> f : futures) {
                results.add(f.join());
            }
            return results;
        });
    }

    /** Filter and dedup AOSC leases from a stats response. */
    static List<MatchedLease> collectAoscLeases(IndicesStatsResponse statsResponse) {
        List<MatchedLease> matched = new ArrayList<>();
        Set<DedupKey> seen = new HashSet<>();
        ShardStats[] all = statsResponse.getShards();
        if (all == null) {
            return matched;
        }
        for (ShardStats shardStats : all) {
            RetentionLeaseStats stats = shardStats.getRetentionLeaseStats();
            if (stats == null) {
                continue;
            }
            ShardId shardId = shardStats.getShardRouting().shardId();
            for (RetentionLease lease : stats.retentionLeases().leases()) {
                if (!lease.id().startsWith(RetentionLeaseManager.LEASE_ID_PREFIX)) {
                    continue;
                }
                if (seen.add(new DedupKey(shardId, lease.id()))) {
                    matched.add(new MatchedLease(shardId, lease));
                }
            }
        }
        return matched;
    }

    public static CleanupLeasesResult.LeaseInfo toLeaseInfo(MatchedLease m, boolean released, String error) {
        return new CleanupLeasesResult.LeaseInfo(
            m.shardId().getIndexName(),
            m.shardId().id(),
            m.lease().id(),
            m.lease().source(),
            m.lease().retainingSequenceNumber(),
            released,
            error
        );
    }

    /** Format exception for the response error field, unwrapping transport wrappers. */
    static String formatErrorMessage(Throwable t) {
        Throwable root = ExceptionsHelper.unwrapCause(t);
        if (root == null) {
            root = t;
        }
        String type = root.getClass().getSimpleName();
        String msg = root.getMessage();
        return msg == null ? type : type + ": " + msg;
    }

    /** Paired shard + lease entry. */
    @Value
    @Accessors(fluent = true)
    public static class MatchedLease {
        ShardId shardId;
        RetentionLease lease;
    }

    /** Dedup key for (shardId, leaseId). */
    private static final class DedupKey {
        private final ShardId shardId;
        private final String leaseId;

        DedupKey(ShardId shardId, String leaseId) {
            this.shardId = shardId;
            this.leaseId = leaseId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DedupKey)) return false;
            DedupKey other = (DedupKey) o;
            return shardId.equals(other.shardId) && leaseId.equals(other.leaseId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(shardId, leaseId);
        }
    }
}
