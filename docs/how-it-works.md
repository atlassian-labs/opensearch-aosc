---
aside: false
---

# How AOSC Works

AOSC keeps the source index live while it builds a target index in the background. The only planned application-visible interruption is the cutover window, when source writes are briefly blocked so workers can replay the final operations and the coordinator can swap the alias.

The walkthrough below shows the successful path and maps each step to the `_status` fields operators see during a migration.

::: tip Start here
Use this page when you want the mental model before reading API details. The simulator follows one successful migration and keeps the source, target, alias, coordinator, and shard worker states visible together.
:::

<div id="sm-viz"></div>

## What to Notice

- Source writes continue through backfill, replay, convergence, and target preparation.
- Source-primary shard workers advance independently; a slow shard can hold the migration before cutover.
- The alias continues to point at the source until the coordinator reaches `COMPLETING`.
- Source writes are blocked during `CUTTING_OVER`, `CATCHING_UP`, and alias swap.
- Cancellation and failure can interrupt any non-terminal coordinator or shard phase.

::: warning Cutover window
The source write block is intentional. Applications must retry rejected writes during cutover; AOSC is designed to make that window short, not to make it disappear.
:::

::: details Status fields behind the simulator
The highlighted coordinator and shard phases correspond to fields returned by the `_status` API. The visual is explanatory, but the phase names come from the plugin state machine so operators can map the animation back to production status output.
:::

For exact phase names and transition diagrams, see [State Machine Reference](reference/state-machine.md). For a copy-paste local run, see [Your First Migration](get-started/your-first-migration.md).
