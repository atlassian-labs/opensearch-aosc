# Routing and Replay

AOSC preserves document IDs and document routing during backfill and replayed index operations. Delete replay is more subtle because OpenSearch operation history does not record the routing key for deletes.

This page explains the behavior AOSC relies on when an index uses custom routing or changes shard count.

## OpenSearch Routing Basics

OpenSearch routes a document to a primary shard by hashing an effective routing value:

```text
effective routing = explicit _routing, when provided
effective routing = _id, otherwise
```

With default routing, a document ID maps to one shard. With custom routing, the same `_id` can exist on multiple shards if clients index it with different routing values. This is valid OpenSearch behavior because document identity is enforced per shard.

That distinction matters during a migration because AOSC must write transformed documents into a different target index while the source remains live.

## What AOSC Preserves

Backfill reads source documents from Lucene stored fields. When a source document has routing, AOSC sends the target index request with the same routing value.

Replay reads source operation history:

| Operation | Routing available to AOSC | Target behavior |
|-----------|---------------------------|-----------------|
| Index/create | Yes | AOSC indexes the target document with the replayed routing value. |
| Delete | No | AOSC chooses the safest delete strategy available for the source and target shard topology. |

`Translog.Index` includes routing. `Translog.Delete` does not. This is an OpenSearch limitation rather than an AOSC-specific encoding choice. The upstream feature request is tracked in [OpenSearch issue 20907](https://github.com/opensearch-project/OpenSearch/issues/20907).

## Why Delete Replay Needs a Topology

OpenSearch's own replication paths avoid this problem because they operate shard-to-shard. Peer recovery, segment replication, and cross-cluster replication apply operations to a known target shard; they do not need the delete request to carry routing.

AOSC is different because it migrates into another index, possibly with a different shard count. For delete replay to be correct without the original routing key, AOSC must know which target shard or target shard group can contain documents copied from a source shard.

That is the reason the shard-count relationship matters.

## Routing Modes

AOSC detects a routing mode at migration start from the source and target index metadata.

| Source to target shards | Mode | Delete replay behavior | Custom-routed deletes |
|-------------------------|------|------------------------|-----------------------|
| `N -> N` | `SAME_SHARD` | Deletes are sent to the corresponding target shard using a synthetic routing value for that shard. | Safe. |
| `N -> kN`, where `k` is a power of 2 | `SPLIT_SHARD` | Deletes fan out to the `k` target shards that can contain documents from the source shard. | Safe. Extra fan-out deletes are no-ops. |
| Shrink, non-multiple change, or non-power-of-2 expansion | `BULK_API` | Deletes are sent without routing and are routed by `_id`. | Risky for custom-routed documents. Requires explicit consent. |

The `SPLIT_SHARD` case is restricted to power-of-2 expansion factors because that is the topology where OpenSearch's routing math keeps a bounded, non-overlapping target shard set for each source shard. A non-power-of-2 expansion can scatter documents from one source shard across target shards that overlap with other source shards.

Current implementation detail: AOSC computes synthetic routing values for the target index. In `SAME_SHARD`, a delete from source shard `S` is sent with a synthetic routing value that routes to target shard `S`. In `SPLIT_SHARD`, the delete is sent once to each target shard in the source shard's target group. Extra fan-out deletes are expected no-ops.

## Why `BULK_API` Can Lose Deletes

Consider a custom-routed source document:

```text
_id = doc-1
_routing = tenant-a
```

During backfill, AOSC copies it to the target with `_routing=tenant-a`.

If the source then receives a delete, OpenSearch records a `Translog.Delete` containing `doc-1`, but not `tenant-a`. In `BULK_API` mode, AOSC can only send:

```text
DELETE /target/_doc/doc-1
```

OpenSearch routes that delete by hashing `doc-1`, not `tenant-a`. If those hash to different shards, the delete is a no-op on the target and the stale document remains.

This is the main data-loss risk behind `accept_data_loss_if_custom_routing_is_used`.

There is a second edge case: a client can send a delete with the wrong routing key. OpenSearch records a delete in the shard that received the request, but the original document remains on the shard selected by its real routing key. AOSC must replay what the source shard history says; it must not search the target by `_id` and delete every matching routed copy, because that would delete data that still exists in the source.

## Container-Replicated Documents

Some applications intentionally write the same `_id` to multiple routing keys so the same logical document exists on multiple source shards. AOSC can preserve this only when the shard topology keeps source-shard ownership unambiguous:

| Topology | Behavior |
|----------|----------|
| `SAME_SHARD` | Each source shard copy maps to the same target shard number. |
| `SPLIT_SHARD` | Each source shard copy maps into that source shard's target shard group. Delete fan-out covers the group. |
| `BULK_API` | Copies can collide on fewer target shards, and unrouted deletes can miss stale copies. |

If your application depends on this pattern, avoid `BULK_API` migrations unless you have an application-specific repair or re-replication plan.

`SPLIT_SHARD` preserves source-shard ownership, but it does not invent new application-level replicas. For example, if each source shard has one routed copy of a container document and you migrate from `N` to `2N` shards, AOSC preserves the `N` source copies in the correct target shard groups. It does not create `2N` routed copies. Applications that require one copy per target shard need their own re-replication or repair step after cutover.

Shard-count changes that fall back to `BULK_API` are especially risky for container-replicated documents. Two routing keys that used to land on different source shards can land on the same target shard. Since `_id` uniqueness is enforced per shard, one copy can overwrite another during backfill. Later unrouted deletes can also miss the surviving copy. This can be silent because AOSC uses idempotent index operations.

## Consent Gate

AOSC currently requires `options.accept_data_loss_if_custom_routing_is_used=true` for every `BULK_API` topology. The gate is intentionally conservative: it is based on the source and target shard relationship, not a proof that every source document uses custom routing.

Use that option only after you have checked the source write path and accepted the possibility of stale custom-routed documents in the target.

## Practical Guidance

Use this decision path before changing shard count:

| Source index behavior | Recommended target shard count |
|-----------------------|--------------------------------|
| Default routing only | Any target shard count that meets your operational needs; `BULK_API` still requires explicit consent for unsupported topologies. |
| Client-supplied `_routing` | Prefer `N -> N` or `N -> kN` where `k` is a power of 2. |
| Container replication or same `_id` deliberately written with multiple routing keys | Prefer `N -> N`; use `SPLIT_SHARD` only with a post-cutover plan for any application-level re-replication requirement. |
| Unknown routing behavior | Treat it as custom routing until the write path and mappings prove otherwise. |
