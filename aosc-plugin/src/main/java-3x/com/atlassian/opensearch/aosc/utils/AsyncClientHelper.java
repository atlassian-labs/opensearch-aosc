/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.utils;

import org.apache.logging.log4j.Logger;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionType;
import org.opensearch.action.admin.cluster.health.ClusterHealthRequest;
import org.opensearch.action.admin.cluster.health.ClusterHealthResponse;
import org.opensearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.opensearch.action.admin.indices.create.CreateIndexRequest;
import org.opensearch.action.admin.indices.create.CreateIndexResponse;
import org.opensearch.action.admin.indices.flush.FlushRequest;
import org.opensearch.action.admin.indices.flush.FlushResponse;
import org.opensearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.opensearch.action.admin.indices.readonly.AddIndexBlockRequest;
import org.opensearch.action.admin.indices.readonly.AddIndexBlockResponse;
import org.opensearch.action.admin.indices.refresh.RefreshRequest;
import org.opensearch.action.admin.indices.refresh.RefreshResponse;
import org.opensearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.opensearch.action.bulk.BackoffPolicy;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.RetryableAction;
import org.opensearch.action.support.clustermanager.AcknowledgedResponse;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Utility that wraps OpenSearch's {@link Client#execute} with {@link CompletableFuture}
 * for consistent async patterns across the AOSC codebase.
 */
public final class AsyncClientHelper {

    private AsyncClientHelper() {} // utility class

    /**
     * Execute a transport action and return a {@link CompletableFuture}.
     *
     * @param client the OpenSearch client
     * @param action the action type
     * @param request the request
     * @param <Req> the request type
     * @param <Resp> the response type
     * @return a future that completes with the response or fails with the exception
     */
    public static <Req extends ActionRequest, Resp extends ActionResponse> CompletableFuture<Resp> executeAsync(
        Client client,
        ActionType<Resp> action,
        Req request
    ) {
        CompletableFuture<Resp> future = new CompletableFuture<>();
        try {
            client.execute(action, request, ActionListener.wrap(future::complete, future::completeExceptionally));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Retry an async operation with bounded, exponentially-backed-off retries and return a
     * {@link CompletableFuture}. Hides {@link RetryableAction} and {@link ActionListener} so callers
     * stay on the {@code CompletableFuture} API, exactly like {@link #executeAsync}.
     *
     * <p>{@code attempt} performs a single try and returns its future; it is invoked again on each retry,
     * so callers can rebuild fresh, "built-at-send" state per attempt. {@code retryable} decides whether a
     * given failure is retried — returning {@code false} (e.g. on exhausted attempts or a non-retryable
     * error) fails the returned future.</p>
     *
     * <p>Note: {@link RetryableAction} terminates on its {@code giveUpTimeout} and advances the backoff
     * iterator unconditionally, so an <em>infinite</em> (delay-capped) backoff policy is used here; bound
     * the number of attempts inside {@code retryable} if a count-based limit is desired.</p>
     */
    public static <Resp> CompletableFuture<Resp> executeAsyncWithRetry(
        Logger logger,
        ThreadPool threadPool,
        Supplier<CompletableFuture<Resp>> attempt,
        TimeValue initialDelay,
        TimeValue maxDelay,
        TimeValue giveUpTimeout,
        String executor,
        Predicate<Exception> retryable
    ) {
        CompletableFuture<Resp> result = new CompletableFuture<>();
        new RetryableAction<Resp>(
            logger,
            threadPool,
            initialDelay,
            giveUpTimeout,
            ActionListener.wrap(result::complete, result::completeExceptionally),
            BackoffPolicy.exponentialEqualJitterBackoff(initialDelay.millis(), maxDelay.millis()),
            executor
        ) {
            @Override
            public void tryAction(ActionListener<Resp> listener) {
                AsyncUtils.bridgeToListener(attempt.get(), listener);
            }

            @Override
            public boolean shouldRetry(Exception e) {
                return retryable.test(e);
            }
        }.run();
        return result;
    }

    public static CompletableFuture<BulkResponse> executeBulkAsync(Client client, BulkRequest request) {
        CompletableFuture<BulkResponse> future = new CompletableFuture<>();
        try {
            client.bulk(request, ActionListener.wrap(future::complete, future::completeExceptionally));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public static CompletableFuture<CreateIndexResponse> executeCreateIndexAsync(Client client, CreateIndexRequest request) {
        CompletableFuture<CreateIndexResponse> future = new CompletableFuture<>();
        try {
            client.admin().indices().create(request, ActionListener.wrap(future::complete, future::completeExceptionally));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public static CompletableFuture<IndexResponse> executeIndexAsync(Client client, IndexRequest request) {
        CompletableFuture<IndexResponse> future = new CompletableFuture<>();
        try {
            client.index(request, ActionListener.wrap(future::complete, future::completeExceptionally));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public static CompletableFuture<GetResponse> executeGetAsync(Client client, GetRequest request) {
        CompletableFuture<GetResponse> future = new CompletableFuture<>();
        try {
            client.get(request, ActionListener.wrap(future::complete, future::completeExceptionally));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public static CompletableFuture<SearchResponse> executeSearchAsync(Client client, SearchRequest request) {
        CompletableFuture<SearchResponse> future = new CompletableFuture<>();
        try {
            client.search(request, ActionListener.wrap(future::complete, future::completeExceptionally));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public static CompletableFuture<AcknowledgedResponse> executeUpdateSettingsAsync(Client client, UpdateSettingsRequest request) {
        CompletableFuture<AcknowledgedResponse> future = new CompletableFuture<>();
        try {
            client.admin().indices().updateSettings(request, ActionListener.wrap(future::complete, future::completeExceptionally));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public static CompletableFuture<AcknowledgedResponse> executePutMappingAsync(Client client, PutMappingRequest request) {
        CompletableFuture<AcknowledgedResponse> future = new CompletableFuture<>();
        try {
            client.admin().indices().putMapping(request, ActionListener.wrap(future::complete, future::completeExceptionally));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public static CompletableFuture<FlushResponse> executeFlushAsync(Client client, FlushRequest request) {
        CompletableFuture<FlushResponse> future = new CompletableFuture<>();
        try {
            client.admin().indices().flush(request, ActionListener.wrap(future::complete, future::completeExceptionally));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public static CompletableFuture<AcknowledgedResponse> executeAliasesAsync(Client client, IndicesAliasesRequest request) {
        CompletableFuture<AcknowledgedResponse> future = new CompletableFuture<>();
        try {
            client.admin().indices().aliases(request, ActionListener.wrap(future::complete, future::completeExceptionally));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public static CompletableFuture<RefreshResponse> executeRefreshAsync(Client client, RefreshRequest request) {
        CompletableFuture<RefreshResponse> future = new CompletableFuture<>();
        try {
            client.admin().indices().refresh(request, ActionListener.wrap(future::complete, future::completeExceptionally));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public static CompletableFuture<AddIndexBlockResponse> executeAddIndexBlockAsync(Client client, AddIndexBlockRequest request) {
        CompletableFuture<AddIndexBlockResponse> future = new CompletableFuture<>();
        try {
            client.admin().indices().addBlock(request, ActionListener.wrap(future::complete, future::completeExceptionally));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public static CompletableFuture<ClusterHealthResponse> executeClusterHealthAsync(Client client, ClusterHealthRequest request) {
        CompletableFuture<ClusterHealthResponse> future = new CompletableFuture<>();
        try {
            client.admin().cluster().health(request, ActionListener.wrap(future::complete, future::completeExceptionally));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }
}
