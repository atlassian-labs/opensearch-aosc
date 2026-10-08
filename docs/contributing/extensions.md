# Extension Points

AOSC exposes a small extension surface for plugins that need to customize migration behavior while reusing the base coordinator, worker, and REST machinery.

These APIs should be treated as public compatibility surfaces within a supported OpenSearch major line.

## `AoscPlugin`

`AoscPlugin` is the base OpenSearch plugin entry point. Extension plugins may subclass it to customize component wiring while preserving the AOSC REST and transport action surface.

Compatibility expectations:

- constructors and protected factory methods used by extension plugins should remain source-compatible within a supported OpenSearch major line
- incompatible changes require release notes and a major compatibility-line decision
- extension plugins should keep the installed plugin identity compatible with the deployment model they target

## `TransformFactory`

`TransformFactory` creates the `TransformFunction` used during backfill and replay.

Extension plugins may override transform creation to support custom script contexts or transform behavior. Implementations should preserve the base behavior for existing AOSC transform modes unless intentionally replacing them.

## `TransformFunction`

`TransformFunction` receives an `IndexDoc` and returns the document or documents to write to the target index. `applyDelete` receives each routed delete as a `DeletedDoc` and returns the deletes to send; the default returns it unchanged. `IndexDoc.toCtx()` and `DeletedDoc.toCtx()` include `op_type` and `source_shard_id`.

Implementations must preserve AOSC correctness requirements:

- output must be deterministic for the same input document and script parameters
- routing changes must be compatible with the target index shard layout
- emitted documents must preserve IDs unless the extension intentionally documents a different contract
- failures must propagate to the migration instead of being swallowed

## Compatibility

Extension APIs are best-effort stable within an OpenSearch major release line. OpenSearch major-version migrations may require extension changes because OpenSearch plugin APIs can change across major versions.
