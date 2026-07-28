/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.benchmark;

/**
 * Builds NDJSON bulk request bodies for OpenSearch _bulk API.
 * Supports index, update, and delete actions with optional routing.
 */
public final class BulkRequestBuilder {
    private final StringBuilder bulk = new StringBuilder();

    /** Append an index action. */
    public BulkRequestBuilder index(String index, String id, String routing, String docBody) {
        bulk.append("{\"index\":{\"_index\":\"").append(index).append("\",\"_id\":\"").append(id).append("\"");
        if (routing != null) {
            bulk.append(",\"routing\":\"").append(routing).append("\"");
        }
        bulk.append("}}\n");
        bulk.append(docBody).append("\n");
        return this;
    }

    /** Append an update action with doc_as_upsert. */
    public BulkRequestBuilder update(String index, String id, String routing, String partialDoc) {
        bulk.append("{\"update\":{\"_index\":\"").append(index).append("\",\"_id\":\"").append(id).append("\"");
        if (routing != null) {
            bulk.append(",\"routing\":\"").append(routing).append("\"");
        }
        bulk.append("}}\n");
        bulk.append("{\"doc\":").append(partialDoc).append(",\"doc_as_upsert\":true}\n");
        return this;
    }

    /** Append a delete action. */
    public BulkRequestBuilder delete(String index, String id, String routing) {
        bulk.append("{\"delete\":{\"_index\":\"").append(index).append("\",\"_id\":\"").append(id).append("\"");
        if (routing != null) {
            bulk.append(",\"routing\":\"").append(routing).append("\"");
        }
        bulk.append("}}\n");
        return this;
    }

    /** Append a seed doc (index action with simple fields). */
    public BulkRequestBuilder seedDoc(String index, int docNum, String routing) {
        String id = "seed-" + docNum;
        String body = "{\"field\":\"value-" + docNum + "\",\"counter\":" + docNum + ",\"tag\":\"seed\"}";
        return index(index, id, routing, body);
    }

    @Override
    public String toString() {
        return bulk.toString();
    }

}
