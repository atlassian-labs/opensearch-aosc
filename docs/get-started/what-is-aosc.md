# What Is AOSC?

AOSC is an OpenSearch plugin for online index migration. It is designed for cases where the target index needs a different schema, shard layout, or document shape than the source index.

AOSC does not modify an index in place. You create a new target index, then AOSC copies and replays source data into it before swapping an alias.

::: tip In one sentence
AOSC is for same-cluster migrations where the old index must keep serving traffic while a new index is populated and prepared for alias cutover.
:::

## Problem

OpenSearch supports some mapping additions, but many schema and layout changes require a new index. A plain `_reindex` copies a point-in-time view of the source. If the source keeps receiving writes, you need a second process to replay or reconcile writes that happened during the copy.

AOSC provides that replay and cutover workflow inside the cluster.

## Migration Flow

AOSC follows a guarded migration path:

1. Validate the source, target, alias, and options.
2. Backfill existing source documents into the target.
3. Replay source operation history while writes continue on the source.
4. Wait until every source-primary shard is close enough to cut over.
5. Briefly write-block the source, replay the final operations, validate counts, and swap the alias.

For the interactive phase-by-phase view, see [How AOSC Works](../how-it-works.md).

::: info Key invariant
Applications should talk to the alias, not directly to the concrete source index. Without an alias, AOSC cannot complete the cutover safely.
:::

## Vocabulary

| Term | Meaning |
|------|---------|
| Source index | The current index that holds live data. |
| Target index | The pre-created index with the desired mappings, settings, and shard count. |
| Alias | The application-facing name AOSC swaps from source to target at cutover. |
| Backfill | Copy of existing source documents into the target. |
| Replay | Applying source-shard operation history to the target. |
| Convergence | A shard is close enough to the source global checkpoint to proceed toward cutover. |
| Cutover | Write-block source, replay final operations, validate, and swap the alias. |
| Shard worker | Per-source-primary worker that performs backfill and replay. |
| Coordinator | Cluster-manager-side service that advances migration phases. |

## Required Assumptions

- The source and target are in the same OpenSearch cluster.
- The target index already exists.
- Applications use an alias that can be moved from source to target.
- Applications retry writes that are rejected during the cutover write block.
- The plugin is installed at the same version on all cluster nodes.

## Limitations to Understand First

- AOSC is not zero-interruption. Source writes are blocked briefly during cutover. Successful production cutovers have been observed where the application-visible write interruption was about 2 seconds to 30 seconds, including a 50 TB index at about 30 seconds. This is an observation, not an upper bound; validation, alias update, cluster-manager responsiveness, shard count, and write load can make specific runs shorter or longer.
- AOSC does not delete the source or target index for you after a migration.
- Cross-cluster migrations are not supported.
- Custom routing and shard count changes require careful review; some routes require explicit data-loss consent.
- Built-in document transforms use OpenSearch update-script style and are currently 1:1 for each source document.

::: warning Plan retries before production
Treat write retry behavior as a production readiness requirement, not an implementation detail. The plugin can coordinate the migration, but the application must tolerate the brief write block.
:::

See [Known Limitations](../reference/known-limitations.md) before planning a real migration.
