/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import com.atlassian.opensearch.aosc.AoscSettings;
import com.atlassian.opensearch.aosc.service.adaptive.AimdBatchSizeController;
import com.atlassian.opensearch.aosc.service.adaptive.AimdConfig;
import com.atlassian.opensearch.aosc.service.adaptive.FixedBatchSizeController;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import java.util.Objects;

/**
 * Creates {@link BulkWriter} instances for backfill and replay phases.
 *
 * <p>The {@code aosc.<phase>.controller.type} setting selects the controller strategy:
 * <ul>
 *   <li>{@code fixed} — static batch size and concurrency from settings</li>
 *   <li>{@code adaptive_batch} — fixed concurrency, AIMD batch sizing</li>
 *   <li>{@code adaptive} — adaptive concurrency + AIMD batch sizing (backfill only)</li>
 * </ul>
 */
public class BulkWriterFactory {

    private final Client client;
    private final ThreadPool threadPool;
    private final ClusterSettings clusterSettings;

    public BulkWriterFactory(Client client, ThreadPool threadPool, ClusterSettings clusterSettings) {
        this.client = Objects.requireNonNull(client, "client");
        this.threadPool = Objects.requireNonNull(threadPool, "threadPool");
        this.clusterSettings = Objects.requireNonNull(clusterSettings, "clusterSettings");
    }

    public BulkWriter createBackfillWriter(AoscLogger logger) {
        ControllerType type = ControllerType.fromString(clusterSettings.get(AoscSettings.BACKFILL_CONTROLLER_TYPE));
        OverloadBackoff backoff = new OverloadBackoff(
            () -> clusterSettings.get(AoscSettings.BACKFILL_OVERLOAD_BASE_PAUSE),
            () -> clusterSettings.get(AoscSettings.BACKFILL_OVERLOAD_MAX_PAUSE),
            () -> clusterSettings.get(AoscSettings.OVERLOAD_MAX_CONSECUTIVE_FAILURES)
        );

        WriteController controller;
        switch (type) {
            case FIXED:
                controller = new SimpleWriteController(
                    logger,
                    new FixedBatchSizeController(() -> clusterSettings.get(AoscSettings.BACKFILL_FIXED_BATCH_SIZE)),
                    () -> clusterSettings.get(AoscSettings.BACKFILL_FIXED_CONCURRENCY),
                    () -> clusterSettings.get(AoscSettings.BACKFILL_BATCH_MAX_BYTES).getBytes(),
                    backoff
                );
                break;
            case ADAPTIVE_BATCH:
                controller = new SimpleWriteController(
                    logger,
                    new AimdBatchSizeController(logger, AimdConfig.defaults(clusterSettings, true)),
                    () -> clusterSettings.get(AoscSettings.BACKFILL_FIXED_CONCURRENCY),
                    () -> clusterSettings.get(AoscSettings.BACKFILL_BATCH_MAX_BYTES).getBytes(),
                    backoff
                );
                break;
            case ADAPTIVE:
                controller = new AdaptiveWriteController(
                    logger,
                    () -> clusterSettings.get(AoscSettings.BACKFILL_CONCURRENCY_MAX),
                    clusterSettings.get(AoscSettings.BACKFILL_FIXED_CONCURRENCY),
                    clusterSettings.get(AoscSettings.BACKFILL_CONCURRENCY_GRADIENT_THRESHOLD),
                    clusterSettings.get(AoscSettings.BACKFILL_CONCURRENCY_PROBE_INTERVAL),
                    () -> clusterSettings.get(AoscSettings.BACKFILL_BATCH_MIN_BYTES).getBytes(),
                    () -> clusterSettings.get(AoscSettings.BACKFILL_BATCH_MAX_BYTES).getBytes(),
                    () -> clusterSettings.get(AoscSettings.BACKFILL_AIMD_MIN_STEP_BYTES).getBytes(),
                    () -> clusterSettings.get(AoscSettings.BACKFILL_BATCH_MAX_DOCS),
                    clusterSettings.get(AoscSettings.BACKFILL_BATCH_START_BPD).getBytes(),
                    backoff
                );
                break;
            default:
                throw new IllegalStateException("Unknown controller type: " + type);
        }
        return new ConcurrentBulkWriter(client, threadPool, controller, logger);
    }

    public BulkWriter createReplayWriter(AoscLogger logger) {
        ControllerType type = ControllerType.fromString(clusterSettings.get(AoscSettings.REPLAY_CONTROLLER_TYPE));
        OverloadBackoff backoff = new OverloadBackoff(
            () -> clusterSettings.get(AoscSettings.REPLAY_OVERLOAD_BASE_PAUSE),
            () -> clusterSettings.get(AoscSettings.REPLAY_OVERLOAD_MAX_PAUSE),
            () -> clusterSettings.get(AoscSettings.OVERLOAD_MAX_CONSECUTIVE_FAILURES)
        );

        WriteController controller;
        switch (type) {
            case FIXED:
                controller = new SimpleWriteController(
                    logger,
                    new FixedBatchSizeController(() -> clusterSettings.get(AoscSettings.REPLAY_FIXED_BATCH_SIZE)),
                    () -> 1,
                    () -> clusterSettings.get(AoscSettings.REPLAY_BATCH_MAX_BYTES).getBytes(),
                    backoff
                );
                break;
            case ADAPTIVE_BATCH:
                controller = new SimpleWriteController(
                    logger,
                    new AimdBatchSizeController(logger, AimdConfig.defaults(clusterSettings, false)),
                    () -> 1,
                    () -> clusterSettings.get(AoscSettings.REPLAY_BATCH_MAX_BYTES).getBytes(),
                    backoff
                );
                break;
            default:
                throw new IllegalStateException("Replay does not support controller type: " + type);
        }
        return new ConcurrentBulkWriter(client, threadPool, controller, logger);
    }
}
