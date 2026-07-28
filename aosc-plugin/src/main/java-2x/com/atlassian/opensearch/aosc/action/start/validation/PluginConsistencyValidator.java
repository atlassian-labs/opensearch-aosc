/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.action.start.validation;

import com.atlassian.opensearch.aosc.AoscPlugin;

import org.opensearch.action.admin.cluster.node.info.NodeInfo;
import org.opensearch.action.admin.cluster.node.info.NodesInfoRequest;
import org.opensearch.action.admin.cluster.node.info.PluginsAndModules;
import org.opensearch.client.Client;
import org.opensearch.core.action.ActionListener;
import org.opensearch.plugins.PluginInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Async: verifies every node has the AOSC plugin installed at the same version. */
public final class PluginConsistencyValidator implements AsyncMigrationStartValidator {

    @Override
    public CompletableFuture<Void> validate(ValidationContext ctx) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        validatePluginOnAllNodes(
            ctx.client(),
            AoscPlugin.PLUGIN_NAME,
            ActionListener.wrap(v -> future.complete(null), future::completeExceptionally)
        );
        return future;
    }

    static void validatePluginOnAllNodes(Client client, String pluginName, ActionListener<Void> listener) {
        NodesInfoRequest nodesInfoRequest = new NodesInfoRequest();
        nodesInfoRequest.clear().addMetric("plugins");
        client.admin().cluster().nodesInfo(nodesInfoRequest, ActionListener.wrap(response -> {
            List<String> errors = validatePluginConsistency(response.getNodes(), pluginName);
            if (!errors.isEmpty()) {
                listener.onFailure(new IllegalStateException(String.join("; ", errors)));
                return;
            }
            listener.onResponse(null);
        }, listener::onFailure));
    }

    public static List<String> validatePluginConsistency(List<NodeInfo> nodes, String pluginName) {
        List<String> errors = new ArrayList<>();
        List<String> missingPlugin = new ArrayList<>();
        Map<String, List<String>> versionToNodes = new HashMap<>();

        for (NodeInfo nodeInfo : nodes) {
            String nodeName = nodeInfo.getNode().getName();
            PluginsAndModules pluginsAndModules = nodeInfo.getInfo(PluginsAndModules.class);
            if (pluginsAndModules == null) {
                missingPlugin.add(nodeName);
                continue;
            }
            String aoscVersion = pluginsAndModules.getPluginInfos()
                .stream()
                .filter(p -> isAoscPlugin(p, pluginName))
                .map(PluginInfo::getVersion)
                .findFirst()
                .orElse(null);
            if (aoscVersion == null) {
                missingPlugin.add(nodeName);
            } else {
                versionToNodes.computeIfAbsent(aoscVersion, k -> new ArrayList<>()).add(nodeName);
            }
        }

        if (!missingPlugin.isEmpty()) {
            errors.add(
                "Cannot start migration: "
                    + missingPlugin.size()
                    + " node(s) do not have the AOSC plugin installed: "
                    + missingPlugin
                    + ". A blue-green deployment or rolling restart may be in progress."
            );
        }

        if (versionToNodes.size() > 1) {
            errors.add(
                "Cannot start migration: AOSC plugin version mismatch across nodes: "
                    + versionToNodes
                    + ". A rolling upgrade may be in progress."
            );
        }

        return errors;
    }

    private static boolean isAoscPlugin(PluginInfo p, String pluginName) {
        if (pluginName.equals(p.getName())) return true;
        try {
            return AoscPlugin.class.isAssignableFrom(Class.forName(p.getClassname()));
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
