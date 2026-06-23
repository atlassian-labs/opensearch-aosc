# Configuration Reference

AOSC settings are cluster-level dynamic settings. Change them with `PUT /_cluster/settings`.

## Migration Defaults

| Setting | Default | Range | Type |
| --- | --- | --- | --- |
| `aosc.defaults.convergence_threshold` | `500` | `0` to `1000000` | int |
| `aosc.defaults.max_convergence_rounds` | `1000` | `1` to `100000` | int |
| `aosc.defaults.transient_target_settings` | `{"index.number_of_replicas":"0","index.refresh_interval":"-1"}` | JSON object with `index.` keys | string |
| `aosc.defaults.remove_source_write_block_on_success` | false | — | boolean |

## Liveness and Target Readiness

| Setting | Default | Range | Type |
| --- | --- | --- | --- |
| `aosc.liveness.check_interval` | `30s` | `>=5s` | TimeValue |
| `aosc.liveness.timeout` | `300s` | `>=10s` | TimeValue |
| `aosc.target.ready_timeout` | `4h` | `>=1m` | TimeValue |

## Cross-Shard Backfill Concurrency

| Setting | Default | Range | Type |
| --- | --- | --- | --- |
| `aosc.backfill.max_concurrent_per_node` | `2` | `0` to `1000` | int |

`0` pauses new backfill permits. Reducing the value does not revoke permits already held; the count drains as workers finish.

## Controller Type

| Setting | Default | Allowed values |
| --- | --- | --- |
| `aosc.backfill.controller.type` | `adaptive_batch` | `fixed`, `adaptive_batch`, `adaptive` |
| `aosc.replay.controller.type` | `adaptive_batch` | `fixed`, `adaptive_batch` |

`adaptive` is supported only for backfill. Replay rejects `adaptive` controller type.

## Fixed Controller Settings

| Setting | Default | Range | Type |
| --- | --- | --- | --- |
| `aosc.backfill.read.page_size` | `1000` | `1` to `50000` | int |
| `aosc.backfill.controller.batch.size` | `500` | `1` to `50000` | int |
| `aosc.backfill.controller.concurrency` | `1` | `1` to `32` | int |
| `aosc.replay.controller.batch.size` | `500` | `1` to `50000` | int |

## Shared Batch Limits

| Setting | Default | Range | Type |
| --- | --- | --- | --- |
| `aosc.backfill.controller.batch.max_bytes` | `100MB` | `1MB` to `512MB` | ByteSizeValue |
| `aosc.replay.controller.batch.max_bytes` | `100MB` | `1MB` to `512MB` | ByteSizeValue |

## Adaptive Batch Settings

| Setting | Default | Range | Type |
| --- | --- | --- | --- |
| `aosc.backfill.controller.batch.min_bytes` | `512KB` | `1KB` to `100MB` | ByteSizeValue |
| `aosc.backfill.controller.batch.max_docs` | `500` | `1` to `50000` | int |
| `aosc.backfill.controller.batch.start_bytes_per_doc` | `10KB` | `256B` to `1MB` | ByteSizeValue |
| `aosc.backfill.controller.aimd.increase_threshold` | `3` | `1` to `100` | int |
| `aosc.backfill.controller.aimd.increase_ratio` | `0.20` | `0.01` to `1.0` | double |
| `aosc.backfill.controller.aimd.min_step_bytes` | `256KB` | `1KB` to `100MB` | ByteSizeValue |
| `aosc.backfill.controller.aimd.cooldown_ticks` | `2` | `0` to `100` | int |
| `aosc.backfill.controller.aimd.trial_revert_threshold` | `0.10` | `0.0` to `1.0` | double |
| `aosc.replay.controller.batch.min_bytes` | `512KB` | `1KB` to `100MB` | ByteSizeValue |
| `aosc.replay.controller.batch.max_docs` | `500` | `1` to `50000` | int |
| `aosc.replay.controller.batch.start_bytes_per_doc` | `10KB` | `256B` to `1MB` | ByteSizeValue |
| `aosc.replay.controller.aimd.increase_threshold` | `3` | `1` to `100` | int |
| `aosc.replay.controller.aimd.increase_ratio` | `0.20` | `0.01` to `1.0` | double |
| `aosc.replay.controller.aimd.min_step_bytes` | `256KB` | `1KB` to `100MB` | ByteSizeValue |
| `aosc.replay.controller.aimd.cooldown_ticks` | `2` | `0` to `100` | int |
| `aosc.replay.controller.aimd.trial_revert_threshold` | `0.10` | `0.0` to `1.0` | double |

## Adaptive Backfill Concurrency Settings

These apply when `aosc.backfill.controller.type=adaptive`.

| Setting | Default | Range | Type |
| --- | --- | --- | --- |
| `aosc.backfill.controller.concurrency.max` | `8` | `1` to `32` | int |
| `aosc.backfill.controller.concurrency.probe_interval` | `5` | `1` to `100` | int |
| `aosc.backfill.controller.concurrency.gradient_threshold` | `1.5` | `1.0` to `10.0` | double |

## Overload Protection

| Setting | Default | Range | Type |
| --- | --- | --- | --- |
| `aosc.overload.max_consecutive_failures` | `50` | `1` to `10000` | int |
| `aosc.backfill.controller.overload.base_pause` | `2000ms` | `100ms` to `600000ms` | long |
| `aosc.backfill.controller.overload.max_pause` | `120000ms` | `1000ms` to `600000ms` | long |
| `aosc.replay.controller.overload.base_pause` | `2000ms` | `100ms` to `600000ms` | long |
| `aosc.replay.controller.overload.max_pause` | `120000ms` | `1000ms` to `600000ms` | long |

## Per-Migration Options

Pass these in the start request under `options`:

| Field | Default | Validation |
| --- | --- | --- |
| `convergence_threshold_per_shard` | cluster default | `0` to `1000000` |
| `max_convergence_rounds_per_shard` | cluster default | `1` to `100000` |
| `doc_count_tolerance` | `0` | `>=0` |
| `validation_query` | unset | Valid query DSL on both source and target |
| `accept_data_loss_if_custom_routing_is_used` | `false` | boolean |
| `transient_target_settings` | cluster default | keys must start with `index.` |
| `target_ready_timeout_seconds` | cluster default | positive enough to satisfy cluster setting range |
| `remove_source_write_block_on_success` | cluster default, currently `false` | boolean |

## Opinionated Configuration Sets

For production clusters with live search traffic where minimizing CPU overhead matters more than migration speed, run one backfill worker per node:

```bash
curl -X PUT 'http://localhost:9200/_cluster/settings' \
  -H 'Content-Type: application/json' \
  -d '{
    "persistent": {
      "aosc.backfill.controller.batch.max_docs": "500",
      "aosc.backfill.controller.batch.size": "500",
      "aosc.backfill.read.page_size": "1000",
      "aosc.backfill.controller.type": "adaptive_batch",
      "aosc.replay.controller.batch.max_docs": "500",
      "aosc.replay.controller.batch.size": "500",
      "aosc.replay.controller.type": "adaptive_batch",
      "aosc.defaults.convergence_threshold": "500",
      "aosc.backfill.max_concurrent_per_node": "1"
    }
  }'
```

For write-only clusters or offline clusters where migration throughput is the priority, use a higher-throughput profile:

```bash
curl -X PUT 'http://localhost:9200/_cluster/settings' \
  -H 'Content-Type: application/json' \
  -d '{
    "persistent": {
      "aosc.backfill.controller.batch.max_docs": "5000",
      "aosc.backfill.controller.batch.size": "5000",
      "aosc.backfill.read.page_size": "5000",
      "aosc.backfill.controller.type": "adaptive",
      "aosc.replay.controller.batch.max_docs": "5000",
      "aosc.replay.controller.batch.size": "5000",
      "aosc.replay.controller.type": "adaptive_batch",
      "aosc.defaults.convergence_threshold": "5000",
      "aosc.backfill.max_concurrent_per_node": "10"
    }
  }'
```

## Example

```bash
curl -X PUT 'http://localhost:9200/_cluster/settings' \
  -H 'Content-Type: application/json' \
  -d '{
    "transient": {
      "aosc.backfill.max_concurrent_per_node": 1,
      "aosc.backfill.controller.batch.max_bytes": "50MB"
    }
  }'
```

Read current values with:

```bash
curl -s 'http://localhost:9200/_cluster/settings?include_defaults=true' | jq '.defaults.aosc'
```
