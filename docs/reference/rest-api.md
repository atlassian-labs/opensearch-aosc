# REST API

AOSC exposes REST endpoints under `/_plugins/_aosc`.

## Start Migration

```text
POST /_plugins/_aosc/{index}/_start
```

`index` is the source index name. The request body names the pre-created target index and the alias to swap at cutover.

### Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `target_index` | string | Yes | Existing target index. |
| `alias` | string | Yes | Alias currently used for the source and moved to the target at cutover. |
| `transform_script` | object | No | Inline or stored Painless update script. Omit for identity. |
| `options` | object | No | Per-migration overrides. |

`transform_script` fields:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | string | Yes | `inline` or `stored`. |
| `source` | string | For inline | Painless source. |
| `id` | string | For stored | Stored script ID. |
| `params` | object | No | Script parameters. |
| `script_context` | string | No | Defaults to `update`. Only `update` is supported by the base plugin. |

`options` fields:

| Field | Default | Description |
|-------|---------|-------------|
| `convergence_threshold_per_shard` | cluster default, currently `500` | Remaining ops threshold before a shard is considered converged. |
| `max_convergence_rounds_per_shard` | cluster default, currently `1000` | Replay/convergence round limit. |
| `doc_count_tolerance` | `0` | Accepted source/target document count difference at cutover. |
| `validation_query` | unset | Query DSL used to filter source and target counts during validation. |
| `accept_data_loss_if_custom_routing_is_used` | `false` | Required for `BULK_API` routing topologies. In that mode, deletes are replayed without the original routing key and can miss custom-routed target documents. |
| `target_ready_timeout_seconds` | cluster default, currently `14400` | Target readiness timeout. |
| `remove_source_write_block_on_success` | cluster default, currently `false` | Remove the source write block after successful cutover. |
| `transient_target_settings` | cluster default | Target settings applied during migration and restored later. |

### Example

```bash
curl -X POST 'http://localhost:9200/_plugins/_aosc/my-index-v1/_start' \
  -H 'Content-Type: application/json' \
  -d '{
    "target_index": "my-index-v2",
    "alias": "my-index",
    "transform_script": {
      "type": "inline",
      "source": "ctx._source.migrated = true"
    },
    "options": {
      "convergence_threshold_per_shard": 200
    }
  }'
```

Response:

```json
{
  "migration_id": "my-index-v1__my-index-v2__1716700000000",
  "accepted": true
}
```

## Get Migration Status

```text
GET /_plugins/_aosc/{index}/_status
```

Returns the migration document for the source index. If no migration is found, the transport action returns a not-found error.

Example response excerpt for a completed migration. Real responses include one `shards` entry per source shard and can be large on high-shard indices.

```json
{
  "migration_id": "21b526c5-4794-4a55-9186-2020f702a731",
  "source_index": "my-index-v1",
  "target_index": "my-index-v2",
  "alias": "my-index",
  "phase": "COMPLETED",
  "shard_routing_mode": "SAME_SHARD",
  "start_time_millis": 1780991529496,
  "last_updated_millis": 1781030665647,
  "options": {
    "convergence_threshold_per_shard": 1000,
    "max_convergence_rounds_per_shard": 1000,
    "doc_count_tolerance": 0,
    "target_ready_timeout_seconds": 14400,
    "remove_source_write_block_on_success": true
  },
  "cutover_context": {
    "source_doc_count": 689217412,
    "target_doc_count": 689217412,
    "doc_count_tolerance": 0,
    "doc_count_validation_passed": true,
    "alias_swap_succeeded": true,
    "cutover_start_millis": 1781030652331,
    "cutover_end_millis": 1781030665206
  },
  "shards": {
    "0": {
      "phase": "COMPLETED",
      "last_replayed_seq_no": 8453324,
      "target_seq_no": 8453324,
      "backfill_cutoff_seq_no": 8448260,
      "backfill": {
        "status": "COMPLETED",
        "documents_indexed": 1012245,
        "bulk_retries": 0,
        "rounds": 2025,
        "start_time_millis": 1781008395372,
        "end_time_millis": 1781010339131
      },
      "replay": {
        "status": "COMPLETED",
        "operations_applied": 415,
        "rounds": 1,
        "current_gap": 0
      },
      "convergence": {
        "status": "COMPLETED",
        "operations_applied": 4649,
        "rounds": 3978,
        "current_gap": 0
      },
      "catching_up": {
        "status": "COMPLETED",
        "operations_applied": 0,
        "rounds": 1,
        "current_gap": 0
      },
      "transition_history": [
        { "phase": "PENDING", "start_time_millis": 1780991529840, "end_time_millis": 1781008395135 },
        { "phase": "BACKFILLING", "start_time_millis": 1781008395372, "end_time_millis": 1781010339131 },
        { "phase": "COMPLETING", "start_time_millis": 1781030651618, "end_time_millis": 1781030651640 }
      ]
    }
  }
}
```

Common top-level fields:

| Field | Description |
|-------|-------------|
| `migration_id` | Unique migration identifier. |
| `source_index` | Source index name. |
| `target_index` | Target index name. |
| `alias` | Alias used for cutover. |
| `phase` | Coordinator phase. |
| `shard_routing_mode` | Routing strategy selected for shard movement, such as `SAME_SHARD` or `SPLIT_SHARD`. |
| `options` | Resolved migration options. |
| `transform_script` | Transform script, if set. |
| `shards` | Map of shard ID to shard progress. |
| `cutover_context` | Document-count validation and alias-swap details once cutover reaches completion. |
| `transition_history` | Coordinator phase transition history when present. Per-shard transition history is stored under each shard. |
| `error_message` | Top-level error on failure. |

## Cancel Migration

```text
POST /_plugins/_aosc/{index}/_cancel
```

Response:

```json
{
  "accepted": true,
  "phase": "CANCELLING"
}
```

Cancellation is asynchronous. Poll status until the coordinator reaches `CANCELLED` or `FAILED`.

## List Migrations

```text
GET /_plugins/_aosc/_list
```

Query parameters:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `status` | `all` | Comma-separated coordinator phases. The pseudo-token `ACTIVE` expands to all non-terminal phases. |
| `size` | `50` | Maximum number of results. Values above `500` are clamped. |

Example:

```bash
curl -s 'http://localhost:9200/_plugins/_aosc/_list?status=ACTIVE&size=100' | jq '.'
```

Response:

```json
{
  "migrations": [
    {
      "migration_id": "my-index-v1__my-index-v2__1716700000000",
      "source_index": "my-index-v1",
      "target_index": "my-index-v2",
      "alias": "my-index",
      "phase": "COMPLETED",
      "shards_by_phase": { "COMPLETED": 5 },
      "shard_count": 5,
      "is_terminal": true,
      "elapsed_seconds": 142
    }
  ],
  "truncated": false,
  "active_count": 0
}
```

## Cleanup Retention Leases

```text
POST /_plugins/_aosc/_admin/_cleanup_leases
```

Query parameters:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `index` | unset | Restrict cleanup to one index. Omit to scan open indices. |
| `dry_run` | `true` | When true, list matching AOSC leases without removing them. |

Examples:

```bash
curl -X POST 'http://localhost:9200/_plugins/_aosc/_admin/_cleanup_leases'
curl -X POST 'http://localhost:9200/_plugins/_aosc/_admin/_cleanup_leases?index=my-index-v1&dry_run=false'
```

Only retention leases whose ID starts with `aosc-migration-` are considered.

## Clear Cluster State

```text
POST /_plugins/_aosc/_admin/_clear_state
```

Query parameters:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `dry_run` | `true` | Preview what would be cleared. |
| `try_close` | `true` | Try to close in-memory coordinators on the cluster-manager node. |
| `migration_id` | unset | Clear only one migration. Omit to target all AOSC migration entries. |
| `detailed` | `false` | Include detailed state in the response. |

Examples:

```bash
curl -X POST 'http://localhost:9200/_plugins/_aosc/_admin/_clear_state'
curl -X POST 'http://localhost:9200/_plugins/_aosc/_admin/_clear_state?dry_run=false'
```

Use this endpoint only after confirming that normal cancellation or failure cleanup cannot resolve the state.
