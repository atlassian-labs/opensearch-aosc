# AOSC - Automatic Online Schema Change

<p class="aosc-home-lede">
AOSC moves an OpenSearch index to a pre-created target index while the source remains live. It backfills existing documents, replays source operation history, then briefly blocks source writes to finish catch-up and swap an alias.
</p>

<div class="aosc-home-actions">
  <a class="aosc-primary-action" href="./get-started/your-first-migration">Run a first migration</a>
  <a class="aosc-secondary-action" href="./how-it-works">See how it works</a>
</div>

<div class="aosc-fact-grid">
  <section>
    <span>Online path</span>
    <p>Backfill and replay run while the source index continues serving application traffic.</p>
  </section>
  <section>
    <span>Explicit cutover</span>
    <p>Source writes are blocked only for final replay, validation, and alias swap.</p>
  </section>
  <section>
    <span>Operational visibility</span>
    <p>Status APIs expose coordinator phase, shard phase, counters, and errors.</p>
  </section>
</div>

::: tip When AOSC fits
AOSC is for same-cluster index migrations where you need a new target index for mappings, settings, shard layout, or document shape, but the source index must keep receiving traffic during most of the move.
:::

::: warning Cutover is not zero-interruption
Applications must retry writes rejected during the brief source write block. Successful cutovers have been observed in the 2s-30s range, but your window depends on index size, shard count, write load, and cluster-manager responsiveness.
:::

<div class="vp-card-grid">
  <a class="vp-card" href="./get-started/your-first-migration"><strong>Run it locally</strong><span>Build the plugin, start a test cluster, keep writes running, and watch cutover.</span></a>
  <a class="vp-card" href="./how-it-works"><strong>Understand the flow</strong><span>Follow source writes, shard workers, catch-up, and alias movement step by step.</span></a>
  <a class="vp-card" href="./reference/rest-api"><strong>Use the API</strong><span>Start, monitor, cancel, list, and clean up migrations from REST endpoints.</span></a>
</div>

## Why AOSC?

Many OpenSearch index properties cannot be changed in place. Changing field types, analyzers, routing assumptions, or shard counts usually means creating a new index and copying data.

AOSC automates the parts that are risky to do by hand:

- Backfill source documents into the target.
- Replay source-shard operation history so writes during backfill are applied to the target.
- Track per-shard progress and expose status through REST APIs.
- Perform alias cutover after a source write block and final catch-up.

AOSC still requires planning. The target index must be created first, applications must use an alias for cutover, and applications must retry writes rejected during the cutover write block.
