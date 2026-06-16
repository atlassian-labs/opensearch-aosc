# Transform Documents

AOSC can run a Painless update script for each source document during backfill and replay.

The built-in transform context is `update`. It is a 1:1 transform: each source document becomes one target document. Omit `transform_script` for an identity transform.

## Script Context

AOSC compiles the script using OpenSearch's `UpdateScript` context. Use the standard update-script shape:

| Variable | Meaning |
|----------|---------|
| `ctx._source` | Mutable source map that will be indexed into the target. |
| `ctx._id` | Document ID. |
| `ctx._routing` | Document routing, when present. |
| `params` | Parameters from `transform_script.params`. |

Use `ctx._source.field`, not `ctx.field`.

AOSC currently ignores `ctx.op`. Setting `ctx.op = "noop"`, `ctx.op = "delete"`, or similar does not skip the target write in the built-in transform path.

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

AOSC compiles and dry-runs the transform at migration start. This catches syntax errors, missing stored scripts, unknown `script_context` values, and some missing parameter errors before the migration is accepted.

Runtime script errors on individual documents fail the shard worker to avoid silently indexing corrupted data. Test scripts against representative documents before starting a migration.
