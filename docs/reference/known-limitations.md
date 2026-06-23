---
icon: material/alert-circle-outline
---

# Known Limitations

This page documents current constraints visible from the public source tree. Treat it as operational guidance, not a performance guarantee.

## Source Writes Are Blocked During Cutover

AOSC applies a source write block during cutover. Writes to the source fail while the block is active.

Applications must retry rejected writes. Test your retry behavior before using AOSC on important write paths.

Successful production cutovers have been observed where the application-visible write interruption was about 2 seconds to 30 seconds, including a 50 TB index at about 30 seconds. This is an observation, not an upper bound; validation, alias update, target health, cluster-manager responsiveness, shard count, document-count validation, and write load can make specific runs shorter or longer. Measure it in your environment.

## Target Index Must Already Exist

AOSC validates that the target index exists before accepting a migration. It does not create the target index for you.

Create the target with the desired mappings, settings, shard count, and replica settings before calling `_start`.

## Same Cluster Only

AOSC migrates from source to target in the same OpenSearch cluster. Cross-cluster migrations are not supported.

For cross-cluster movement, use OpenSearch features such as remote reindex, snapshot and restore, or a separate replication pipeline, depending on your requirements.

## Alias-Based Cutover Required

AOSC swaps an alias from source to target. Applications that read or write direct concrete index names will not be moved by the alias swap.

## Custom Routing and Shard Count Changes Need Review

AOSC preserves document IDs and routing values when indexing target documents. Replayed deletes are harder because OpenSearch `Translog.Delete` entries contain the deleted `_id` but not the routing key. The upstream feature request for recording delete routing is [OpenSearch issue 20907](https://github.com/opensearch-project/OpenSearch/issues/20907).

This is safe in AOSC's `SAME_SHARD` and `SPLIT_SHARD` routing modes because AOSC can route the delete to the target shard or target shard group that can contain documents copied from the source shard. For source indices with more than one primary shard, `SPLIT_SHARD` requires the source and target to have the same `index.number_of_routing_shards`; AOSC rejects the migration at start if that precondition is not met. In `BULK_API` mode, a delete for a custom-routed document is sent without routing and OpenSearch routes it by `_id`. If the original document was routed by a different key, the delete can miss the copied target document and leave stale data.

`BULK_API` mode includes shrink, non-multiple shard-count changes, and non-power-of-2 expansions. AOSC requires explicit consent through `accept_data_loss_if_custom_routing_is_used` for these topologies. The consent gate is conservative: it is based on shard topology, not a full scan proving that custom routing is present.

The highest-risk case is an application that writes the same `_id` with multiple routing keys, such as container or tenant-shard replication. In `BULK_API` topologies, target-shard collisions can cause one routed copy to overwrite another during backfill, and later unrouted deletes can miss stale target copies. This can be silent.

Review [Routing and Replay](../concepts/routing-and-replay) before setting `accept_data_loss_if_custom_routing_is_used`.

## Built-In Transforms Are 1:1

The built-in `update` transform modifies `ctx._source` and emits one target document per source document. It does not currently implement public skip, split, or fan-out behavior through `ctx.op`.

## Transform Runtime Errors Fail the Migration

AOSC compiles and dry-runs scripts at start, but a script can still fail on a specific document during migration. Runtime script errors fail the affected worker so the migration does not silently write incorrect data.

Write defensive scripts and test against representative data.

## No General Dry Run

AOSC has dry-run behavior for some admin cleanup APIs, but it does not provide a full migration dry-run mode. Validate risky migrations in a staging or representative cluster.

## Source and Target Cleanup Is Manual

AOSC does not delete source or target indices after completion, cancellation, or failure. Keep both until verification is complete, then delete manually according to your retention policy.

## Metrics Export Is Not Implemented

AOSC exposes progress through status APIs and logs. It does not currently export Prometheus or OpenSearch metrics.
