# Plan a Migration

Use this checklist before starting a migration on a real cluster.

## Preconditions

- The plugin is installed at the same version on all nodes.
- The source index is healthy and has active primaries.
- The target index already exists with the desired mapping, settings, and shard count.
- Applications use an alias that AOSC can swap from source to target.
- Applications retry `cluster_block_exception` or equivalent write failures during cutover.
- You have enough capacity to hold source and target data at the same time.

## Pre-flight Checklist

```bash
curl -s http://localhost:9200/_cat/plugins?v
curl -s http://localhost:9200/_cluster/health | jq '.'
curl -s http://localhost:9200/_plugins/_aosc/_list | jq '.'
curl -s http://localhost:9200/my-source/_settings | jq '.'
curl -s http://localhost:9200/my-target/_settings | jq '.'
curl -s http://localhost:9200/_alias/my-alias | jq '.'
```

Confirm:

- [ ] Source and target index names are different.
- [ ] The alias points to the source before migration.
- [ ] The alias does not already point to the target.
- [ ] The target index mapping accepts the transformed documents.
- [ ] The target shard count and routing behavior have been reviewed.
- [ ] Disk, heap, and indexing pressure have headroom.
- [ ] A cancellation and cleanup plan is documented.
- [ ] The same migration has been tested on representative data when risk is material.

## Capacity Notes

AOSC writes to the target while the source remains in place. Plan for at least the source and target storage footprint at the same time, plus normal merge and replica overhead.

Useful inputs:

| Input | Command |
|-------|---------|
| Source size and docs | `GET /_cat/indices/{source}?v` |
| Shard layout | `GET /_cat/shards/{source}?v` and `GET /_cat/shards/{target}?v` |
| Write rate | Compare indexing counters from `GET /{source}/_stats` over time. |
| Node pressure | `GET /_nodes/stats/jvm,fs,thread_pool,indices` |

## Cutover Planning

During cutover AOSC blocks source writes, replays final operations, validates document counts, swaps the alias, and optionally removes the source write block. Successful production cutovers have been observed where the application-visible write interruption was about 2 seconds to 30 seconds, including a 50 TB index at about 30 seconds. This is an observation, not an upper bound; validation, alias update, cluster-manager responsiveness, shard count, cluster health, and write load can make specific runs shorter or longer. Measure it in staging or a representative environment before relying on a specific window.

## Rollback Planning

Before alias swap, cancellation leaves the alias on the source and the target remains available for inspection or deletion.

After alias swap, writes go to the target. Swapping the alias back to the source can lose writes accepted after cutover unless your application has a separate replay or reconciliation path. Treat post-cutover rollback as a data-recovery exercise, not a simple undo.
