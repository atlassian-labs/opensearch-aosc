# Backpressure and Throttling

AOSC controls migration load with cross-shard permits and bulk write controllers.

## Cross-Shard Backfill Permits

`aosc.backfill.max_concurrent_per_node` limits how many shard workers on a node may hold a backfill permit at the same time.

- Default: `2`
- `0` pauses new backfill work.
- Increasing the setting grants queued workers when capacity is available.
- Decreasing the setting does not revoke already-held permits.

## Bulk Write Controllers

AOSC creates separate bulk writers for backfill and replay.

| Controller | Backfill | Replay | Behavior |
|------------|----------|--------|----------|
| `fixed` | Yes | Yes | Uses configured batch size and fixed concurrency. |
| `adaptive_batch` | Yes | Yes | Uses fixed concurrency with AIMD batch sizing. |
| `adaptive` | Yes | No | Uses adaptive batch size and adaptive backfill concurrency. |

## Fixed Mode

Fixed mode reads current settings dynamically:

- `aosc.backfill.controller.batch.size`
- `aosc.backfill.controller.concurrency`
- `aosc.replay.controller.batch.size`

## Adaptive Batch Mode

Adaptive batch mode adjusts a byte target for each bulk request. It estimates bytes per document and converts the byte target to a document count.

On overload or transient failures, it reduces the target size. After clean successes, it probes upward within configured bounds.

## Adaptive Backfill Mode

Backfill-only `adaptive` mode adjusts both batch size and intra-shard write concurrency. It uses latency-gradient signals and overload backoff to reduce pressure when the target slows down.

## Operator Tuning

Slow down backfill:

```bash
curl -X PUT 'http://localhost:9200/_cluster/settings' \
  -H 'Content-Type: application/json' \
  -d '{"transient":{"aosc.backfill.max_concurrent_per_node":2}}'
```

Pause new backfill permits:

```bash
curl -X PUT 'http://localhost:9200/_cluster/settings' \
  -H 'Content-Type: application/json' \
  -d '{"transient":{"aosc.backfill.max_concurrent_per_node":0}}'
```

Switch backfill to adaptive batch sizing:

```bash
curl -X PUT 'http://localhost:9200/_cluster/settings' \
  -H 'Content-Type: application/json' \
  -d '{"transient":{"aosc.backfill.controller.type":"adaptive_batch"}}'
```

Monitor cluster health, indexing pressure, JVM, disk, and bulk rejections while tuning.
