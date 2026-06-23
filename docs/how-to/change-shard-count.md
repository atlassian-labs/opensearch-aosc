# Change Shard Count

Use AOSC when you need to move data into a target index with a different shard count and you also need operation-history replay while the source remains live.

## 1. Review Routing Risk

Shard count changes are safest when AOSC can keep source-shard ownership unambiguous. AOSC detects one of these routing modes:

| Source to target shards | Mode | Guidance |
|-------------------------|------|----------|
| `N -> N` | `SAME_SHARD` | Safest path for custom-routed indices. |
| `N -> kN`, where `k` is a power of 2 | `SPLIT_SHARD` | Supported for custom routing when routing metadata is compatible; delete replay fans out within the target shard group. |
| Shrink, non-multiple change, or non-power-of-2 expansion | `BULK_API` | Requires `accept_data_loss_if_custom_routing_is_used`; custom-routed deletes can leave stale target documents. |

Check the source index settings and application write path before proceeding. If the source uses tenant routing, container replication, or any client-supplied `_routing`, read [Routing and Replay](../concepts/routing-and-replay) before choosing the target shard count. That page includes the split fan-out proof and the reason `index.number_of_routing_shards` matters.

For split-style migrations from a source index with more than one primary shard, check the source routing-shard value:

```bash
curl -s 'http://localhost:9200/my-index-v1/_settings' \
  | jq -r '."my-index-v1".settings.index.number_of_routing_shards'
```

When creating the target, use that same value for `index.number_of_routing_shards`. This setting is fixed at index creation time.

## 2. Create the Target Index

Create the target with the desired shard count and copied or updated mappings:

```bash
curl -X PUT 'http://localhost:9200/my-index-v2' \
  -H 'Content-Type: application/json' \
  -d '{
    "settings": {
      "number_of_shards": 10,
      "number_of_replicas": 1
    },
    "mappings": {
      "properties": {
        "id": {"type": "keyword"},
        "title": {"type": "text"}
      }
    }
  }'
```

If the source has more than one primary shard and you are doing a power-of-two expansion, replace the example `number_of_routing_shards` with the source index's actual value. For a `3 -> 12` migration where the source has `index.number_of_routing_shards=12`, the target should use:

```json
{
  "settings": {
    "index.number_of_shards": 12,
    "index.number_of_routing_shards": 12
  }
}
```

## 3. Start the Migration

```bash
curl -X POST 'http://localhost:9200/_plugins/_aosc/my-index-v1/_start' \
  -H 'Content-Type: application/json' \
  -d '{
    "target_index": "my-index-v2",
    "alias": "my-index"
  }'
```

If AOSC rejects the migration because of routing risk, review the reason before setting `accept_data_loss_if_custom_routing_is_used`. Do not use that option as a generic bypass.

```bash
curl -X POST 'http://localhost:9200/_plugins/_aosc/my-index-v1/_start' \
  -H 'Content-Type: application/json' \
  -d '{
    "target_index": "my-index-v2",
    "alias": "my-index",
    "options": {
      "accept_data_loss_if_custom_routing_is_used": true
    }
  }'
```

Only set the option when you have accepted the possibility of stale target documents for custom-routed deletes.

## 4. Monitor

```bash
curl -s 'http://localhost:9200/_plugins/_aosc/my-index-v1/_status' | jq '{phase, shards}'
```

AOSC proceeds to cutover automatically after the coordinator and shard workers reach the required phases.

## 5. Verify

After completion:

```bash
curl -s 'http://localhost:9200/_alias/my-index' | jq '.'
curl -s 'http://localhost:9200/my-index-v2/_settings' | jq '."my-index-v2".settings.index.number_of_shards'
curl -s 'http://localhost:9200/my-index-v2/_count' | jq '.count'
```

Keep the source index until your operational checks are complete.
