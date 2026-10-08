# Transform Documents

AOSC can run a Painless update script for each source document during backfill and replay.

The built-in transform context is `update`. It is a 1:1 transform: each source document becomes one target document, and replayed deletes go to the target unchanged. Omit `transform_script` for an identity transform. To run the script on deletes too, see [Apply to Deletes](#apply-to-deletes).

## Script Context

AOSC compiles the script using OpenSearch's `UpdateScript` context. Use the standard update-script shape:

| Variable | Meaning |
|----------|---------|
| `ctx._source` | Mutable source map that will be indexed into the target. Not present on deletes. |
| `ctx._id` | Document ID. |
| `ctx._routing` | Document routing, when present. |
| `ctx.op_type` | `"index"` for documents, `"delete"` for deletes. Always set. |
| `ctx.source_shard_id` | Number of the source shard the document or delete was read from. Always set. |
| `params` | Parameters from `transform_script.params`. |

Use `ctx._source.field`, not `ctx.field`.

Changing `ctx._id` or `ctx._routing` changes the target document's ID or routing. AOSC ignores changes to `ctx.op_type`, `ctx.source_shard_id`, and `ctx.op`: setting one to `"noop"` doesn't skip the target write.

## Apply to Deletes

By default, the script runs only on documents. If it changes `ctx._id` or `ctx._routing`, replayed deletes still use the source ID and routing and can miss the transformed target documents. Set `apply_to_deletes` to run the same script on deletes:

```bash
curl -X POST 'http://localhost:9200/_plugins/_aosc/my-index-v1/_start' \
  -H 'Content-Type: application/json' \
  -d '{
    "target_index": "my-index-v2",
    "alias": "my-index",
    "transform_script": {
      "type": "inline",
      "source": "ctx._id = params.prefix + ctx._id; if (ctx.op_type == \"index\") { ctx._source.migrated = true }",
      "params": { "prefix": "v2-" },
      "apply_to_deletes": true
    }
  }'
```

With `apply_to_deletes`:

- Deletes run the script with `ctx.op_type = "delete"`, `ctx._id`, `ctx._routing`, and `ctx.source_shard_id`. They have no `ctx._source`, so guard source logic with `ctx.op_type == "index"`.
- `ctx._id` must stay a non-empty string, and `ctx._routing` must be a string or null.

AOSC routes each delete before the script runs, then sends the script's output as is. On OpenSearch 3.9 and later, `ctx._routing` is the routing the delete was sent with. On OpenSearch 3.8 or earlier, it's a value AOSC generates to reach a target shard, or null, rather than the document's routing. A split-shard migration on those versions also runs the script once for each target shard the delete goes to. If the script rewrites `ctx._routing` on those versions, deletes can miss target documents.

## Inline Script

```bash
curl -X POST 'http://localhost:9200/_plugins/_aosc/my-index-v1/_start' \
  -H 'Content-Type: application/json' \
  -d '{
    "target_index": "my-index-v2",
    "alias": "my-index",
    "transform_script": {
      "type": "inline",
      "source": "ctx._source.author = ctx._source.remove(\"author_name\")"
    }
  }'
```

With parameters:

```bash
curl -X POST 'http://localhost:9200/_plugins/_aosc/my-index-v1/_start' \
  -H 'Content-Type: application/json' \
  -d '{
    "target_index": "my-index-v2",
    "alias": "my-index",
    "transform_script": {
      "type": "inline",
      "source": "ctx._source.status = params.default_status",
      "params": { "default_status": "active" }
    }
  }'
```

## Stored Script

Register a stored script:

```bash
curl -X POST 'http://localhost:9200/_scripts/my-transform-v1' \
  -H 'Content-Type: application/json' \
  -d '{
    "script": {
      "lang": "painless",
      "source": "ctx._source.author = ctx._source.remove(\"author_name\")"
    }
  }'
```

Reference it from the migration request:

```bash
curl -X POST 'http://localhost:9200/_plugins/_aosc/my-index-v1/_start' \
  -H 'Content-Type: application/json' \
  -d '{
    "target_index": "my-index-v2",
    "alias": "my-index",
    "transform_script": {
      "type": "stored",
      "id": "my-transform-v1"
    }
  }'
```

## Common Patterns

Rename a field:

```text
ctx._source.new_field = ctx._source.remove("old_field")
```

Convert a value to a long:

```text
ctx._source.count = Long.parseLong(ctx._source.count.toString())
```

Create a computed field:

```text
ctx._source.full_name = ctx._source.first_name + " " + ctx._source.last_name
```

Remove a field:

```text
ctx._source.remove("unused_field")
```

Set a default value:

```text
if (ctx._source.region == null) {
  ctx._source.region = "unknown"
}
```

Update a nested field:

```text
ctx._source.author.name = ctx._source.author.name.toUpperCase()
```

## Validation Behavior

AOSC compiles and dry-runs the transform at migration start. This catches syntax errors, missing stored scripts, unknown `script_context` values, and some missing parameter errors before the migration is accepted. With `apply_to_deletes`, AOSC also dry-runs the script on a sample delete.

Runtime script errors on individual documents fail the shard worker to avoid silently indexing corrupted data. Test scripts against representative documents before starting a migration.
