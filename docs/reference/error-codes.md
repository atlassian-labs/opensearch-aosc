# Error Reference

AOSC does not define a separate stable error-code namespace. Errors are surfaced as OpenSearch exceptions, validation messages, migration status fields, and logs.

## Start Request Validation

| Message fragment | Likely cause | Action |
|------------------|--------------|--------|
| `target.index is required` | Missing `target_index` in the body. | Add `target_index`. |
| `alias is required` | Missing alias in the body. | Add the alias AOSC should swap. |
| `source index and target.index must be different` | Source path and target body name match. | Use a separate target index. |
| `source index [...] not found` | Source index from the path is missing. | Create or correct the source index. |
| `target index [...] not found` | Target index does not exist. | Pre-create the target index. |
| `transform_script.source is required for inline scripts` | Inline transform has no source. | Provide Painless source or omit `transform_script`. |
| `transform_script.id is required for stored scripts` | Stored transform has no ID. | Provide a stored script ID. |
| `Unknown transform script_context` | Unsupported transform context. | Use `update` or omit `script_context`. |
| `Transform script dry-run failed` | Script failed validation against an empty context or missing params. | Add params or make the script defensive. |
| `validation_query failed on index` | The validation query failed against source or target. | Fix the query or mappings. |
| `transient_target_settings keys must start with 'index.'` | Option contains invalid target setting key. | Use OpenSearch index setting keys. |
| `Custom routing detected` or data-loss consent text | AOSC selected `BULK_API` routing mode. Custom-routed deletes cannot be guaranteed because OpenSearch delete history does not carry routing. | Prefer same-shard or power-of-2 expansion, or set consent only after review. |

## Runtime Failures

| Symptom | Possible cause | Action |
|---------|----------------|--------|
| Migration enters `FAILING` then `FAILED` | Worker failure, cutover failure, validation failure, or cleanup failure. | Read `_status.error_message`, shard errors, and cluster logs. |
| Shard fails during transform | Painless runtime exception for a document. | Fix the script and rerun with a new target or cleaned target. |
| Max convergence rounds exceeded | Source write rate or global checkpoint behavior prevents convergence. | Reduce write rate, tune replay/backfill settings, or retry with a larger threshold. |
| Target not ready | Target allocation or replicas did not become ready in time. | Check target health, disk, allocation, and `aosc.target.ready_timeout`. |
| Alias swap failed | Alias conflicts or cluster metadata update failure. | Inspect alias state and cluster-manager logs. |
| Lease cleanup needed | A crash left AOSC retention leases behind. | Use `_admin/_cleanup_leases` after checking active migrations. |

## Useful Diagnostics

```bash
curl -s 'http://localhost:9200/_plugins/_aosc/my-index-v1/_status' | jq '.'
curl -s 'http://localhost:9200/_plugins/_aosc/_list' | jq '.'
curl -s 'http://localhost:9200/_cluster/health' | jq '.'
curl -s 'http://localhost:9200/_cat/plugins?v'
curl -s 'http://localhost:9200/_cat/shards?v'
```

When reporting an issue, include the migration status document, relevant cluster logs, OpenSearch version, AOSC version or commit, and the start request with secrets removed.
