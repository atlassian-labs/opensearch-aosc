/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.compat;

import com.atlassian.opensearch.aosc.utils.AsyncClientHelper;

import org.opensearch.action.support.clustermanager.AcknowledgedResponse;
import org.opensearch.transport.client.AdminClient;
import org.opensearch.transport.client.Client;
import org.opensearch.transport.client.ClusterAdminClient;
import org.opensearch.transport.client.IndicesAdminClient;

import static org.mockito.Mockito.mock;

/** Version-specific mock factory for OS 3.x client types. */
public final class MockClientFactory {

    private MockClientFactory() {}

    public static Client mockClient() {
        return mock(Client.class);
    }

    public static AdminClient mockAdminClient() {
        return mock(AdminClient.class);
    }

    public static IndicesAdminClient mockIndicesAdminClient() {
        return mock(IndicesAdminClient.class);
    }

    public static ClusterAdminClient mockClusterAdminClient() {
        return mock(ClusterAdminClient.class);
    }

    public static AcknowledgedResponse acknowledgedResponse(boolean acknowledged) {
        return new AcknowledgedResponse(acknowledged) {
        };
    }

    /** Wraps a raw Client mock with pre-wired admin chain for test use. */
    public static final class Handle {
        private final Client client;
        private final AdminClient admin;
        private final IndicesAdminClient indicesAdmin;
        private final ClusterAdminClient clusterAdmin;

        public Handle() {
            this.client = mock(Client.class);
            this.admin = mock(AdminClient.class);
            this.indicesAdmin = mock(IndicesAdminClient.class);
            this.clusterAdmin = mock(ClusterAdminClient.class);
            org.mockito.Mockito.when(client.admin()).thenReturn(admin);
            org.mockito.Mockito.when(admin.indices()).thenReturn(indicesAdmin);
            org.mockito.Mockito.when(admin.cluster()).thenReturn(clusterAdmin);
        }

        /** Raw mock for {@code when(handle.client().xxx())} chains. */
        public Client client() {
            return client;
        }

        /** Admin client mock. */
        public AdminClient admin() {
            return admin;
        }

        /** Indices admin client mock. */
        public IndicesAdminClient indicesAdmin() {
            return indicesAdmin;
        }

        /** Cluster admin client mock. */
        public ClusterAdminClient clusterAdmin() {
            return clusterAdmin;
        }

        /** Wrapped helper for AOSC constructors. */
        public AsyncClientHelper helper() {
            return AsyncClientHelper.wrap(client);
        }
    }

    public static Handle createHandle() {
        return new Handle();
    }
}
