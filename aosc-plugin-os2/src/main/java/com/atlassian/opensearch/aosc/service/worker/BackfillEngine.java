/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.worker;

import com.atlassian.opensearch.aosc.model.IndexDoc;
import com.atlassian.opensearch.aosc.service.bulk.BulkWriter;
import com.atlassian.opensearch.aosc.service.bulk.ThreadSafeDocSource;
import com.atlassian.opensearch.aosc.service.bulk.WriteOp;
import com.atlassian.opensearch.aosc.transform.TransformFunction;
import com.atlassian.opensearch.aosc.utils.AoscLogger;
import com.atlassian.opensearch.aosc.utils.LC;
import com.atlassian.opensearch.aosc.utils.ShardHandle;

import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.AlreadyClosedException;

import org.opensearch.action.DocWriteRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.common.lucene.search.Queries;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.index.engine.Engine;
import org.opensearch.index.fieldvisitor.FieldsVisitor;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

import static com.atlassian.opensearch.aosc.utils.AoscLogger.kv;

/**
 * Shard-local backfill engine. Enumerates all documents in a source
 * shard via a Lucene snapshot and bulk-indexes them into the target.
 *
 * <p>One instance per backfill operation. Create via constructor,
 * start with {@link #start()}, cancel with {@link #cancel()}.
 * Single-use — do not call {@code start()} more than once.</p>
 */
public class BackfillEngine {

    /** Excludes nested child docs — only matches root-level documents (B037). */
    private static final Query ROOT_DOCS_QUERY = Queries.newNonNestedFilter();

    /**
     * Callback interface for backfill progress reporting.
     * Called after each batch is successfully written.
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(long documentsProcessed, int batchCount, long totalDocs);
    }

    private final ShardHandle shardHandle;
    private final String targetIndex;
    private final TransformFunction transform;
    private final BulkWriter bulkWriter;
    /** Lucene page size for {@code searchAfter} — independent of the bulk write batch size. */
    private final IntSupplier readPageSize;
    private final Runnable startCallback;
    private final ProgressCallback progressCallback;
    private final AoscLogger logger;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final CompletableFuture<BackfillResult> finishedFuture = new CompletableFuture<>();

    private final AtomicLong documentsProcessed = new AtomicLong(0);
    private final AtomicInteger batchCount = new AtomicInteger(0);

    /** Acquired once in start(), closed in finishedFuture.whenComplete(). */
    private final AtomicReference<Engine.Searcher> activeSearcher = new AtomicReference<>();
    private volatile long totalDocs;
    private volatile long startTimeMillis;

    /**
     * Creates a new backfill engine for a single shard.
     *
     * @param bulkWriter      writer that batches and submits bulk write requests
     * @param shardHandle     handle to the source shard
     * @param targetIndex     name of the target index
     * @param transform       document transformation function
     * @param readPageSize    supplier for the number of Lucene docs to fetch per {@code searchAfter} page
     * @param startCallback   optional callback fired when backfill begins (before reads)
     * @param progressCallback optional callback fired after each batch and on terminal state
     */
    public BackfillEngine(
        AoscLogger logger,
        BulkWriter bulkWriter,
        ShardHandle shardHandle,
        String targetIndex,
        TransformFunction transform,
        IntSupplier readPageSize,
        Runnable startCallback,
        ProgressCallback progressCallback
    ) {
        this.logger = Objects.requireNonNull(logger, "logger").forClass(BackfillEngine.class);
        this.bulkWriter = Objects.requireNonNull(bulkWriter, "bulkWriter");
        this.shardHandle = Objects.requireNonNull(shardHandle, "shardHandle");
        this.targetIndex = Objects.requireNonNull(targetIndex, "targetIndex");
        this.transform = Objects.requireNonNull(transform, "transform");
        this.readPageSize = readPageSize;
        this.startCallback = startCallback;
        this.progressCallback = progressCallback;
        // Single cleanup and progress reporting point: fires on success, failure, or cancel.
        finishedFuture.whenComplete((r, e) -> {
            closeSearcherQuietly();
            if (progressCallback != null) {
                progressCallback.onProgress(documentsProcessed.get(), batchCount.get(), totalDocs);
            }
        });
    }

    /**
     * Initiates backfill. Refreshes the shard, acquires a Lucene searcher snapshot, and begins
     * async batch processing via {@link BulkWriter#consumeAsync}.
     *
     * @return a future that completes with the backfill result, or exceptionally on error/cancel
     * @throws IllegalStateException if already started or cancelled
     */
    public CompletableFuture<BackfillResult> start() {
        if (cancelled.get()) {
            throw new IllegalStateException("Cannot start — already cancelled");
        }
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("start() already called — single-use only");
        }

        startTimeMillis = System.currentTimeMillis();

        try {
            if (startCallback != null) {
                startCallback.run();
            }

            // Force refresh to ensure Lucene snapshot is consistent with GCP
            shardHandle.refresh("aosc-backfill-pre-snapshot");

            // Acquire shard-local searcher — closed in finishedFuture.whenComplete()
            Engine.Searcher searcher = shardHandle.acquireSearcher("aosc-backfill");
            activeSearcher.set(searcher);
            totalDocs = searcher.count(ROOT_DOCS_QUERY);
            logger.info("Backfill starting", kv(LC.EVENT, "backfill_start"), kv(LC.DOCS, totalDocs));

            Iterator<WriteOp<BackfillBatchMetrics>> iterator = new BackfillDocIterator(searcher);
            ThreadSafeDocSource<BackfillBatchMetrics> source = new ThreadSafeDocSource<>(iterator);

            bulkWriter.consumeAsync(source, batch -> {
                long docsInBatch = batch.ops().stream().filter(WriteOp::isWritable).count();
                documentsProcessed.addAndGet(docsInBatch);
                batchCount.incrementAndGet();
                logger.trace(
                    "Backfill progress",
                    kv(LC.EVENT, "backfill_progress"),
                    kv(LC.BATCHES, batchCount.get()),
                    kv(LC.DOCS, documentsProcessed.get())
                );
                if (progressCallback != null) {
                    progressCallback.onProgress(documentsProcessed.get(), batchCount.get(), totalDocs);
                }
            }).whenComplete((v, e) -> {
                if (e != null) {
                    finishedFuture.completeExceptionally(e);
                } else {
                    logger.info(
                        "Backfill complete",
                        kv(LC.EVENT, "backfill_complete"),
                        kv(LC.DOCS, documentsProcessed.get()),
                        kv(LC.BATCHES, batchCount.get())
                    );
                    finishedFuture.complete(
                        new BackfillResult(
                            documentsProcessed.get(),
                            totalDocs,
                            System.currentTimeMillis() - startTimeMillis,
                            batchCount.get()
                        )
                    );
                }
            });
        } catch (Exception e) {
            finishedFuture.completeExceptionally(e);
        }

        return finishedFuture;
    }

    /**
     * Cancels the backfill operation. Safe to call from any thread, before or after {@link #start()}.
     * Also cancels the underlying {@link BulkWriter} and closes the searcher.
     *
     * @return the same future returned by {@link #start()}, which will complete exceptionally
     */
    public CompletableFuture<BackfillResult> cancel() {
        cancelled.set(true);
        bulkWriter.cancel();
        // Eagerly release the index reader — don't wait for the backfill loop to exit.
        // Without this, Node.awaitClose() may see open shards from the searcher.
        closeSearcherQuietly();
        if (!started.get()) {
            finishedFuture.completeExceptionally(new CancellationException("Backfill cancelled before start"));
        }
        return finishedFuture;
    }

    /** Closes the active searcher if open, swallowing any exception. */
    private void closeSearcherQuietly() {
        Engine.Searcher searcher = activeSearcher.getAndSet(null);
        if (searcher != null) {
            try {
                searcher.close();
            } catch (Exception e) {
                logger.warn("Failed to close searcher", e);
            }
        }
    }

    /**
     * Result of a completed backfill operation for a single shard.
     */
    @lombok.Value
    @lombok.experimental.Accessors(fluent = true)
    public static class BackfillResult {
        long documentsProcessed;
        long totalDocuments;
        long elapsedMillis;
        int batchCount;
    }

    /** Per-batch metrics for backfill — just a document count. */
    @lombok.Value
    @lombok.experimental.Accessors(fluent = true)
    public static class BackfillBatchMetrics {
        static final BackfillBatchMetrics ZERO = new BackfillBatchMetrics(0);

        int docCount;

        public BackfillBatchMetrics zero() {
            return ZERO;
        }

        public BackfillBatchMetrics accumulate(BackfillBatchMetrics prev) {
            return new BackfillBatchMetrics(prev.docCount + this.docCount);
        }
    }

    private class BackfillDocIterator implements Iterator<WriteOp<BackfillBatchMetrics>> {

        private final Engine.Searcher searcher;
        private final FieldsVisitor fieldsVisitor = new FieldsVisitor(true);
        /** Prefetched Lucene scoreDocs (refilled from {@code searchAfter} pages). */
        private final ArrayDeque<ScoreDoc> sourceBuffer = new ArrayDeque<>();
        /** Buffered write ops produced from the most recent source doc (0..N per doc). */
        private final ArrayDeque<WriteOp<BackfillBatchMetrics>> outQueue = new ArrayDeque<>();
        private ScoreDoc lastDoc;

        BackfillDocIterator(Engine.Searcher searcher) {
            this.searcher = searcher;
        }

        @Override
        public boolean hasNext() {
            if (cancelled.get()) throw new RuntimeException(new CancellationException("Backfill cancelled"));
            // Drive source forward until outQueue has at least one op or source is exhausted.
            // A single source doc can produce zero writes (e.g. all routings filtered out by
            // the owned-slice filter), so one buildIndexWriteOps call does not guarantee an outQueue entry.
            try {
                while (outQueue.isEmpty()) {
                    if (sourceBuffer.isEmpty()) {
                        TopDocs topDocs = searcher.searchAfter(lastDoc, ROOT_DOCS_QUERY, readPageSize.getAsInt(), Sort.INDEXORDER);
                        if (topDocs.scoreDocs == null || topDocs.scoreDocs.length == 0) {
                            return false;
                        }
                        sourceBuffer.addAll(Arrays.asList(topDocs.scoreDocs));
                    }
                    ScoreDoc scoreDoc = sourceBuffer.poll();
                    lastDoc = scoreDoc;
                    buildIndexWriteOps(scoreDoc);
                }
            } catch (AlreadyClosedException e) {
                throw new RuntimeException("Engine searcher is already closed", e);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return true;
        }

        @Override
        public WriteOp<BackfillBatchMetrics> next() {
            if (!hasNext()) throw new NoSuchElementException();
            return outQueue.poll();
        }

        /** Reads one source doc, applies transform, appends 0..N write ops to {@link #outQueue}. */
        private void buildIndexWriteOps(ScoreDoc scoreDoc) throws Exception {
            fieldsVisitor.reset();
            searcher.getIndexReader().document(scoreDoc.doc, fieldsVisitor);

            String docId = fieldsVisitor.id();
            BytesReference sourceBytes = fieldsVisitor.source();
            if (docId == null || sourceBytes == null || sourceBytes.length() == 0) {
                throw new IllegalStateException("Document at Lucene docId=" + scoreDoc.doc + " has null/empty id or source");
            }
            String routing = fieldsVisitor.routing();
            Map<String, Object> sourceMap = XContentHelper.convertToMap(sourceBytes, true).v2();

            List<IndexDoc> outputs = transform.apply(new IndexDoc(docId, routing, sourceMap));

            int emitted = 0;
            for (IndexDoc out : outputs) {
                int counted = (emitted++ == 0) ? 1 : 0;
                outQueue.add(WriteOp.of(buildIndexRequest(out), new BackfillBatchMetrics(counted)));
            }
        }

        /** Builds an IndexRequest from a single transform output. */
        private IndexRequest buildIndexRequest(IndexDoc out) {
            IndexRequest req = new IndexRequest(targetIndex).id(out.id()).source(out.source()).opType(DocWriteRequest.OpType.INDEX);
            if (out.routing() != null) {
                req.routing(out.routing());
            }
            return req;
        }
    }
}
