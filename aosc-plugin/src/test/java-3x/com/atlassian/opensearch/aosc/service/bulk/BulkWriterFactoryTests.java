/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import com.atlassian.opensearch.aosc.AoscSettings;
import com.atlassian.opensearch.aosc.utils.AoscLogger;

import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BulkWriterFactoryTests extends OpenSearchTestCase {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void tearDown() throws Exception {
        executor.shutdownNow();
        super.tearDown();
    }

    // ---- backfill writer ----

    public void testCreateBackfillWriterWithAdaptiveDisabled() {
        BulkWriterFactory factory = factory(Settings.EMPTY);
        BulkWriter writer = factory.createBackfillWriter(AoscLogger.create(BulkWriterFactory.class));
        assertNotNull(writer);
        assertTrue(writer instanceof ConcurrentBulkWriter);
    }

    public void testCreateBackfillWriterWithAdaptiveEnabled() {
        Settings settings = Settings.builder().put("aosc.backfill.controller.type", "adaptive").build();
        BulkWriterFactory factory = factory(settings);
        BulkWriter writer = factory.createBackfillWriter(AoscLogger.create(BulkWriterFactory.class));
        assertNotNull(writer);
        assertTrue(writer instanceof ConcurrentBulkWriter);
    }

    // ---- replay writer ----

    public void testCreateReplayWriter() {
        BulkWriterFactory factory = factory(Settings.EMPTY);
        BulkWriter writer = factory.createReplayWriter(AoscLogger.create(BulkWriterFactory.class));
        assertNotNull(writer);
        assertTrue(writer instanceof ConcurrentBulkWriter);
        ConcurrentBulkWriter cbw = (ConcurrentBulkWriter) writer;
    }

    // ---- settings are read correctly ----

    public void testBackfillWriterAlwaysSerialWhenAdaptiveDisabled() {
        BulkWriterFactory factory = factory(Settings.EMPTY);
        BulkWriter writer = factory.createBackfillWriter(AoscLogger.create(BulkWriterFactory.class));
        ConcurrentBulkWriter cbw = (ConcurrentBulkWriter) writer;
    }

    public void testBackfillWriterAdaptiveStartsAtMinW() {
        Settings settings = Settings.builder()
            .put("aosc.backfill.controller.type", "adaptive")
            .put("aosc.backfill.controller.concurrency.min", 2)
            .put("aosc.backfill.controller.concurrency.max", 8)
            .build();
        BulkWriterFactory factory = factory(settings);
        BulkWriter writer = factory.createBackfillWriter(AoscLogger.create(BulkWriterFactory.class));
        ConcurrentBulkWriter cbw = (ConcurrentBulkWriter) writer;
    }

    // ---- helpers ----

    private BulkWriterFactory factory(Settings settings) {
        ClusterSettings clusterSettings = new ClusterSettings(settings, new HashSet<>(AoscSettings.ALL));
        Client client = mock(Client.class);
        ThreadPool tp = mock(ThreadPool.class);
        when(tp.getThreadContext()).thenReturn(new ThreadContext(Settings.EMPTY));
        when(tp.generic()).thenReturn(executor);
        return new BulkWriterFactory(client, tp, clusterSettings);
    }
}
