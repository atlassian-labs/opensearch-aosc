/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start.validation;

import com.atlassian.opensearch.aosc.model.MigrationRequest;
import com.atlassian.opensearch.aosc.transform.TransformFactory;

import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.transport.client.Client;

import java.util.Objects;

/**
 * Immutable per-request context shared across the validator pipeline.
 * {@code sourceMeta}/{@code targetMeta} may be null — {@link IndicesExistValidator} runs first and rejects.
 */
public final class ValidationContext {

    private final MigrationRequest request;
    private final ClusterState clusterState;
    private final IndexMetadata sourceMeta;
    private final IndexMetadata targetMeta;
    private final TransformFactory transformFactory;
    private final ClusterSettings clusterSettings;
    private final Client client;

    private ValidationContext(
        MigrationRequest request,
        ClusterState clusterState,
        IndexMetadata sourceMeta,
        IndexMetadata targetMeta,
        TransformFactory transformFactory,
        ClusterSettings clusterSettings,
        Client client
    ) {
        this.request = Objects.requireNonNull(request);
        this.clusterState = Objects.requireNonNull(clusterState);
        this.sourceMeta = sourceMeta;
        this.targetMeta = targetMeta;
        this.transformFactory = Objects.requireNonNull(transformFactory);
        this.clusterSettings = Objects.requireNonNull(clusterSettings);
        this.client = Objects.requireNonNull(client);
    }

    public static ValidationContext of(
        MigrationRequest request,
        ClusterState clusterState,
        IndexMetadata sourceMeta,
        IndexMetadata targetMeta,
        TransformFactory transformFactory,
        ClusterSettings clusterSettings,
        Client client
    ) {
        return new ValidationContext(request, clusterState, sourceMeta, targetMeta, transformFactory, clusterSettings, client);
    }

    public MigrationRequest request() {
        return request;
    }

    public ClusterState clusterState() {
        return clusterState;
    }

    public IndexMetadata sourceMeta() {
        return sourceMeta;
    }

    public IndexMetadata targetMeta() {
        return targetMeta;
    }

    public TransformFactory transformFactory() {
        return transformFactory;
    }

    public ClusterSettings clusterSettings() {
        return clusterSettings;
    }

    public Client client() {
        return client;
    }
}
