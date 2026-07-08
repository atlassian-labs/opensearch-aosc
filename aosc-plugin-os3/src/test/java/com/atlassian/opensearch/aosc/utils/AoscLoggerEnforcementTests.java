/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils;

import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Enforces that no AOSC class references {@code LogManager} directly.
 *
 * <p>All AOSC components must use {@link AoscLogger} for logging. Direct usage of
 * {@code LogManager.getLogger} is prohibited outside of {@code AoscLogger} itself,
 * ensuring consistent structured context propagation.</p>
 *
 * <p>This test loads each class's bytecode via the classloader and checks the
 * constant pool for references to {@code LogManager}. This approach avoids
 * filesystem access (blocked by security manager) and {@code getDeclaredFields}
 * (blocked by forbiddenApis).</p>
 */
public class AoscLoggerEnforcementTests extends OpenSearchTestCase {

    private static final String PKG = "com.atlassian.opensearch.aosc";

    /** All AOSC classes to check — referenced by name to avoid package-private visibility issues. */
    private static final String[] AOSC_CLASS_NAMES = {
        // Service — worker
        PKG + ".service.worker.AoscShardService",
        PKG + ".service.worker.ShardMigrationWorker",
        PKG + ".service.worker.BackfillEngine",
        PKG + ".service.worker.TranslogReplayEngine",
        PKG + ".service.worker.RetentionLeaseManager",
        PKG + ".service.worker.BackfillPermitManager",
        // Service — coordinator
        PKG + ".service.coordinator.AoscCoordinatorService",
        PKG + ".service.coordinator.MigrationCoordinator",
        PKG + ".service.coordinator.CutoverService",
        PKG + ".service.coordinator.ClusterStateUpdateHelper",
        PKG + ".service.coordinator.ClusterStateShardBatcher",
        PKG + ".service.coordinator.MigrationDocumentService",
        PKG + ".service.coordinator.ShardLivenessChecker",
        PKG + ".service.coordinator.ShardGate",
        // Service — bulk/adaptive
        PKG + ".service.bulk.AdaptiveWriteController",
        PKG + ".service.bulk.SimpleWriteController",
        PKG + ".service.bulk.ConcurrentBulkWriter",
        PKG + ".service.bulk.BulkWriteHelper",
        PKG + ".service.adaptive.AimdBatchSizeController",
        // Transport actions
        PKG + ".action.status.TransportGetMigrationStatusAction",
        PKG + ".action.clear.TransportClearClusterStateAction",
        PKG + ".action.cleanup.TransportCleanupLeasesAction",
        PKG + ".action.list.TransportListMigrationsAction",
        // Utils
        PKG + ".utils.ShardHandle",
        PKG + ".utils.AsyncUtils",
        PKG + ".utils.IndexOperationUtils",
        PKG + ".utils.MigrationAuditLogger", };

    public void testNoLogManagerReferencesOutsideAoscLogger() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String className : AOSC_CLASS_NAMES) {
            String resourcePath = "/" + className.replace('.', '/') + ".class";
            try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
                assertNotNull("Class resource not found: " + resourcePath, is);
                byte[] bytecode = is.readAllBytes();
                String constantPool = new String(bytecode, StandardCharsets.ISO_8859_1);
                if (constantPool.contains("LogManager")) {
                    String simpleName = className.substring(className.lastIndexOf('.') + 1);
                    violations.add(simpleName);
                }
            }
        }
        if (!violations.isEmpty()) {
            fail(
                "Found LogManager references in bytecode. Use AoscLogger instead.\n\n"
                    + "Violations:\n"
                    + String.join("\n", violations.stream().map(v -> "  - " + v).toArray(String[]::new))
            );
        }
    }
}
