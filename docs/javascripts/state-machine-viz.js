/*
 * SPDX-License-Identifier: Apache-2.0
 */
function initStateMachineViz() {
  const container = document.getElementById("sm-viz");
  if (!container || container.dataset.initialized) return;
  container.dataset.initialized = "true";

  const coordinatorPhases = [
    "INITIALIZING",
    "ACTIVE",
    "PREPARING_TARGET",
    "CUTTING_OVER",
    "CATCHING_UP",
    "COMPLETING",
    "COMPLETED",
    "CANCELLING",
    "CANCELLED",
    "FAILING",
    "FAILED",
  ];
  const shardPhases = [
    "PENDING",
    "ACQUIRING_LEASE",
    "BACKFILLING",
    "REPLAYING",
    "CONVERGING",
    "CONVERGED",
    "CATCHING_UP",
    "COMPLETING",
    "COMPLETED",
    "CANCELLING",
    "CANCELLED",
    "FAILING",
    "FAILED",
  ];
  const fullscreenIcon = `<svg class="aosc-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    <path d="M8 3H3v5M16 3h5v5M21 16v5h-5M3 16v5h5" />
  </svg>`;

  const steps = [
    {
      title: "Start accepted",
      kicker: "Coordinator prepares the migration",
      coord: "INITIALIZING",
      shards: ["PENDING"],
      writes: "Source writes continue",
      alias: "Alias still points to source",
      target: "Target exists but is not live",
      signal: "Coordinator validates the request and creates durable migration state.",
      evidence: ["Migration document appears in .aosc-migrations", "Workers are assigned to source primary shards"],
      invariant: "No user traffic is moved until source, target, alias, and plugin installation checks pass.",
      operator: "Confirm target mappings/settings before starting; AOSC will not create the target index.",
      flow: "setup",
      sourceTone: "active",
      workerTone: "pending",
      targetTone: "idle",
      aliasTone: "source",
    },
    {
      title: "Workers start",
      kicker: "Coordinator opens the active gate",
      coord: "ACTIVE",
      shards: ["ACQUIRING_LEASE"],
      writes: "Source writes continue",
      alias: "Alias still points to source",
      target: "Target receives copied data only",
      signal: "Workers acquire source retention leases so operation history remains available.",
      evidence: ["Shard status moves to ACQUIRING_LEASE", "Retention lease requests appear in logs"],
      invariant: "Source remains authoritative; target is still a private build artifact.",
      operator: "If this stalls, check source primary health and retention lease errors.",
      flow: "leases",
      sourceTone: "active",
      workerTone: "active",
      targetTone: "idle",
      aliasTone: "source",
    },
    {
      title: "Backfill",
      kicker: "Shard workers copy existing documents",
      coord: "ACTIVE",
      shards: ["BACKFILLING"],
      writes: "Source writes continue",
      alias: "Alias still points to source",
      target: "Target is bulk indexed",
      signal: "Each source primary shard scans documents and writes transformed operations to the target.",
      evidence: ["Backfill counters increase", "Bulk requests target the new index", "Backfill permits bound heavy work per node"],
      invariant: "Bulk writes use idempotent indexing; retrying a batch should not duplicate documents.",
      operator: "Watch bulk rejections, indexing pressure, and adaptive batch size.",
      flow: "backfill",
      sourceTone: "active",
      workerTone: "busy",
      targetTone: "building",
      aliasTone: "source",
    },
    {
      title: "Replay",
      kicker: "Workers apply writes that arrived during backfill",
      coord: "ACTIVE",
      shards: ["REPLAYING"],
      writes: "Source writes continue",
      alias: "Alias still points to source",
      target: "Target catches up from translog history",
      signal: "Workers replay source operation history from retained sequence numbers.",
      evidence: ["Replay counters increase", "Global checkpoint and local checkpoint gaps shrink"],
      invariant: "The target is allowed to lag, but the retained operation range must cover the gap.",
      operator: "If replay cannot keep up, reduce source write rate or increase target capacity.",
      flow: "replay",
      sourceTone: "active",
      workerTone: "busy",
      targetTone: "catching-up",
      aliasTone: "source",
    },
    {
      title: "Converge",
      kicker: "Replay rounds shrink the remaining gap",
      coord: "ACTIVE",
      shards: ["CONVERGING"],
      writes: "Source writes continue",
      alias: "Alias still points to source",
      target: "Target is nearly current",
      signal: "Workers run repeated replay rounds until the configured convergence threshold is met.",
      evidence: ["Convergence rounds increase", "Remaining operation gap falls below threshold"],
      invariant: "Cutover is not attempted while any shard is still materially behind.",
      operator: "Tune convergence threshold for the write rate and acceptable cutover window.",
      flow: "converge",
      sourceTone: "active",
      workerTone: "busy",
      targetTone: "catching-up",
      aliasTone: "source",
    },
    {
      title: "All shards converged",
      kicker: "Coordinator waits for every shard",
      coord: "ACTIVE",
      shards: ["CONVERGED"],
      writes: "Source writes continue",
      alias: "Alias still points to source",
      target: "Target is ready for final preparation",
      signal: "Each worker reports CONVERGED; the coordinator can advance only after all workers agree.",
      evidence: ["Every shard status is CONVERGED", "Coordinator active phase has no unfinished workers"],
      invariant: "A single slow shard keeps the whole migration in the safe pre-cutover path.",
      operator: "Investigate the slowest shard instead of forcing cutover early.",
      flow: "converged",
      sourceTone: "active",
      workerTone: "ready",
      targetTone: "ready",
      aliasTone: "source",
    },
    {
      title: "Prepare target",
      kicker: "Restore serving settings and wait for readiness",
      coord: "PREPARING_TARGET",
      shards: ["CONVERGED"],
      writes: "Source writes continue",
      alias: "Alias still points to source",
      target: "Target replicas and refresh behavior are restored",
      signal: "Coordinator restores target settings and waits for the target to become ready.",
      evidence: ["Target readiness checks pass", "Replica recovery and refresh settings settle"],
      invariant: "The target must be serving-ready before writes are blocked on the source.",
      operator: "Check allocation, disk watermarks, and target health if this phase stalls.",
      flow: "prepare",
      sourceTone: "active",
      workerTone: "ready",
      targetTone: "ready",
      aliasTone: "source",
    },
    {
      title: "Cutover begins",
      kicker: "Source writes are briefly blocked",
      coord: "CUTTING_OVER",
      shards: ["CONVERGED"],
      writes: "Source writes blocked",
      alias: "Alias still points to source",
      target: "Target waits for final catch-up",
      signal: "Coordinator applies the source write block and flushes the source index.",
      evidence: ["Source write block is visible in index metadata", "Flush completes before final replay"],
      invariant: "Observed write-block time ranges from about 2s to 30s depending on index size.",
      operator: "Announce this as the only user-visible write interruption window.",
      flow: "block",
      sourceTone: "blocked",
      workerTone: "ready",
      targetTone: "ready",
      aliasTone: "source",
    },
    {
      title: "Final catch-up",
      kicker: "Workers replay the last operations",
      coord: "CATCHING_UP",
      shards: ["CATCHING_UP"],
      writes: "Source writes blocked",
      alias: "Alias still points to source",
      target: "Target receives final operations",
      signal: "Workers replay operations that landed before the write block and then stop.",
      evidence: ["Shard phases move to CATCHING_UP", "Remaining operation gap reaches zero"],
      invariant: "No new source writes can appear after the write block, so final replay has a fixed end.",
      operator: "If this stalls, inspect worker logs and source translog replay errors.",
      flow: "final",
      sourceTone: "blocked",
      workerTone: "busy",
      targetTone: "catching-up",
      aliasTone: "source",
    },
    {
      title: "Workers finalize",
      kicker: "Shard workers release migration resources",
      coord: "CATCHING_UP",
      shards: ["COMPLETING"],
      writes: "Source writes blocked",
      alias: "Alias still points to source",
      target: "Target has the complete migrated dataset",
      signal: "Shard workers release retention leases and finalize worker-local state.",
      evidence: ["Shard phases move to COMPLETING", "Retention lease release is attempted per shard"],
      invariant: "Alias swap still has not happened; source remains the application-facing index.",
      operator: "Do not manually redirect traffic while shard cleanup is still running.",
      flow: "worker-cleanup",
      sourceTone: "blocked",
      workerTone: "busy",
      targetTone: "ready",
      aliasTone: "source",
    },
    {
      title: "Workers complete",
      kicker: "Coordinator receives final shard acknowledgements",
      coord: "CATCHING_UP",
      shards: ["COMPLETED"],
      writes: "Source writes blocked",
      alias: "Alias still points to source",
      target: "Target has the complete migrated dataset",
      signal: "Coordinator waits until every source-primary worker reports COMPLETED.",
      evidence: ["Every shard status is COMPLETED", "Coordinator CATCHING_UP gate can advance"],
      invariant: "Alias swap waits for all shard workers, not just most of them.",
      operator: "If one shard lags here, inspect that shard's catch-up and lease-release logs.",
      flow: "workers-done",
      sourceTone: "blocked",
      workerTone: "done",
      targetTone: "ready",
      aliasTone: "source",
    },
    {
      title: "Alias swap",
      kicker: "Coordinator makes the target live",
      coord: "COMPLETING",
      shards: ["COMPLETED"],
      writes: "Writes resume on target after swap",
      alias: "Alias moves from source to target",
      target: "Target becomes live",
      signal: "Coordinator validates completion, swaps the alias atomically, and removes the source write block if configured.",
      evidence: ["Alias update response succeeds", "Status document records completion metadata"],
      invariant: "Before alias swap, failure leaves traffic on source; after swap, AOSC does not roll back automatically.",
      operator: "Verify alias membership and document counts immediately after completion.",
      flow: "swap",
      sourceTone: "blocked",
      workerTone: "done",
      targetTone: "live",
      aliasTone: "moving",
    },
    {
      title: "Migration complete",
      kicker: "Target is authoritative",
      coord: "COMPLETED",
      shards: ["COMPLETED"],
      writes: "Writes go to target",
      alias: "Alias points to target",
      target: "Target is live",
      signal: "Migration reaches terminal success; source concrete index remains for operator review.",
      evidence: ["Coordinator phase is COMPLETED", "Shard phases are COMPLETED", "Alias resolves to the target index"],
      invariant: "Terminal status is durable in .aosc-migrations for later inspection.",
      operator: "Keep or delete the old source index according to your rollback and retention plan.",
      flow: "done",
      sourceTone: "idle",
      workerTone: "done",
      targetTone: "live",
      aliasTone: "target",
    },
  ];

  let current = 0;
  let autoplay = null;

  function displayPhase(phase) {
    return phase.replace(/_/g, " ");
  }

  function previousCoordinatorPhases(step) {
    return coordinatorPhases.slice(0, coordinatorPhases.indexOf(step.coord));
  }

  function previousShardPhases() {
    const seen = [];
    for (let i = 0; i < current; i += 1) {
      steps[i].shards.forEach((phase) => {
        if (!seen.includes(phase)) seen.push(phase);
      });
    }
    return seen;
  }

  function phaseClass(phase, activePhases, donePhases) {
    if (activePhases.includes(phase)) return "sm-active";
    if (donePhases.includes(phase)) return "sm-done";
    return "sm-pending";
  }

  function nodeClass(name, tone) {
    return `sm-node sm-node-${name} sm-tone-${tone}`;
  }

  function renderShardRows(prefix, tone) {
    return [0, 1, 2]
      .map(
        (shard) => `<div class="sm-shard sm-shard-${tone}">
          <span>${prefix}${shard}</span>
          <i></i><i></i><i></i><i></i>
        </div>`,
      )
      .join("");
  }

  function shardLanesFor(flow) {
    const byFlow = {
      setup: [
        ["shard 0", "PENDING", "waiting for coordinator", 6, "pending"],
        ["shard 1", "PENDING", "waiting for coordinator", 6, "pending"],
        ["shard 2", "PENDING", "waiting for coordinator", 6, "pending"],
      ],
      leases: [
        ["shard 0", "ACQUIRING_LEASE", "pinning translog", 38, "active"],
        ["shard 1", "ACQUIRING_LEASE", "pinning translog", 24, "active"],
        ["shard 2", "PENDING", "permit queued", 8, "pending"],
      ],
      backfill: [
        ["shard 0", "BACKFILLING", "bulk copy 78%", 78, "busy"],
        ["shard 1", "BACKFILLING", "bulk copy 44%", 44, "busy"],
        ["shard 2", "ACQUIRING_LEASE", "starting later", 16, "active"],
      ],
      replay: [
        ["shard 0", "REPLAYING", "history gap 210 ops", 72, "busy"],
        ["shard 1", "BACKFILLING", "last backfill pages", 58, "busy"],
        ["shard 2", "REPLAYING", "history gap 840 ops", 45, "busy"],
      ],
      converge: [
        ["shard 0", "CONVERGING", "below threshold soon", 86, "busy"],
        ["shard 1", "REPLAYING", "catching write burst", 62, "busy"],
        ["shard 2", "CONVERGING", "round 7", 74, "busy"],
      ],
      converged: [
        ["shard 0", "CONVERGED", "ready", 100, "done"],
        ["shard 1", "CONVERGED", "ready", 100, "done"],
        ["shard 2", "CONVERGED", "ready", 100, "done"],
      ],
      prepare: [
        ["shard 0", "CONVERGED", "waiting for target", 100, "done"],
        ["shard 1", "CONVERGED", "waiting for target", 100, "done"],
        ["shard 2", "CONVERGED", "waiting for target", 100, "done"],
      ],
      block: [
        ["shard 0", "CONVERGED", "source blocked", 100, "done"],
        ["shard 1", "CONVERGED", "source blocked", 100, "done"],
        ["shard 2", "CONVERGED", "source blocked", 100, "done"],
      ],
      final: [
        ["shard 0", "CATCHING_UP", "final replay done", 100, "done"],
        ["shard 1", "CATCHING_UP", "last ops applying", 84, "busy"],
        ["shard 2", "CATCHING_UP", "last ops applying", 68, "busy"],
      ],
      "worker-cleanup": [
        ["shard 0", "COMPLETING", "lease released", 100, "done"],
        ["shard 1", "COMPLETING", "releasing lease", 94, "busy"],
        ["shard 2", "COMPLETING", "releasing lease", 90, "busy"],
      ],
      "workers-done": [
        ["shard 0", "COMPLETED", "acknowledged", 100, "done"],
        ["shard 1", "COMPLETED", "acknowledged", 100, "done"],
        ["shard 2", "COMPLETED", "acknowledged", 100, "done"],
      ],
      swap: [
        ["shard 0", "COMPLETED", "alias swap waits", 100, "done"],
        ["shard 1", "COMPLETED", "alias swap waits", 100, "done"],
        ["shard 2", "COMPLETED", "alias swap waits", 100, "done"],
      ],
      done: [
        ["shard 0", "COMPLETED", "target authoritative", 100, "done"],
        ["shard 1", "COMPLETED", "target authoritative", 100, "done"],
        ["shard 2", "COMPLETED", "target authoritative", 100, "done"],
      ],
    };
    return byFlow[flow] || byFlow.setup;
  }

  function renderWorkerLanes(flow) {
    return `<div class="sm-worker-lanes" aria-label="Independent shard worker progress">
      <span class="sm-worker-lanes-note">source primaries advance independently</span>
      ${shardLanesFor(flow)
        .map(
          ([id, phase, detail, progress, tone]) => `<div class="sm-worker-lane sm-worker-${tone}">
            <div>
              <strong>${id}</strong>
              <span>${displayPhase(phase)}</span>
            </div>
            <em>${detail}</em>
            <i><b style="width: ${progress}%"></b></i>
          </div>`,
        )
        .join("")}
    </div>`;
  }

  function renderPhaseRail(label, phases, active, done) {
    return `<section class="sm-phase-rail" aria-label="${label}">
      <div class="sm-phase-rail-title">${label}</div>
      <div class="sm-phase-list">
        ${phases
          .map((phase) => `<span class="sm-phase ${phaseClass(phase, active, done)}">${displayPhase(phase)}</span>`)
          .join("")}
      </div>
    </section>`;
  }

  function renderEvidence(items) {
    return items.map((item) => `<li>${item}</li>`).join("");
  }

  function renderStatusShape(step) {
    return `<div class="sm-status-shape" aria-label="Status fields shown during this step">
      <span>Status fields</span>
      <code>phase: ${step.coord}</code>
      <code>shards.*.phase: ${step.shards.join(" | ")}</code>
    </div>`;
  }

  function renderTimeline() {
    return `<div class="sm-timeline" role="tablist" aria-label="Migration walkthrough steps">
      ${steps
        .map(
          (step, index) => `<button class="sm-dot ${index === current ? "sm-dot-active" : ""} ${
            index < current ? "sm-dot-done" : ""
          }" type="button" role="tab" aria-selected="${index === current}" data-step="${index}">
            <span class="sm-dot-index">${index + 1}</span>
            <span class="sm-dot-title">${step.title}</span>
          </button>`,
        )
        .join("")}
    </div>`;
  }

  function renderTopology(step) {
    const blocked = step.sourceTone === "blocked";
    const targetLive = step.aliasTone === "target" || step.aliasTone === "moving";
    const flowActive = ["backfill", "replay", "converge", "final"].includes(step.flow);
    return `<section class="sm-simulator sm-sim-${step.flow}" aria-label="Animated migration simulator">
      <div class="${nodeClass("source", step.sourceTone)}">
        <span class="sm-node-eyebrow">Source index</span>
        <strong>3 primary shards</strong>
        <span>${step.writes}</span>
        <div class="sm-write-stream ${blocked ? "sm-write-blocked" : ""}" aria-label="${blocked ? "Writes blocked" : "Writes flowing"}">
          <b>app writes</b>
          <i></i><i></i><i></i>
          <em>${blocked ? "blocked" : "flowing"}</em>
        </div>
        <div class="sm-shards">${renderShardRows("s", blocked ? "blocked" : "source")}</div>
      </div>

      <div class="${nodeClass("plugin", step.workerTone)}">
        <span class="sm-node-eyebrow">AOSC plugin</span>
        <strong>${step.shards.map(displayPhase).join(", ")}</strong>
        <span>${step.signal}</span>
        ${renderWorkerLanes(step.flow)}
      </div>

      <div class="${nodeClass("target", step.targetTone)}">
        <span class="sm-node-eyebrow">Target index</span>
        <strong>${targetLive ? "Serving alias traffic" : "Building in background"}</strong>
        <span>${step.target}</span>
        <div class="sm-shards">${renderShardRows("t", targetLive ? "live" : "target")}</div>
      </div>

      <div class="sm-alias-route sm-alias-${targetLive ? "target" : "source"}">
        <span>alias</span>
        <strong>${targetLive ? "target" : "source"}</strong>
      </div>

      <div class="sm-control-plane">
        <span class="sm-node-eyebrow">Control plane</span>
        <strong>${displayPhase(step.coord)}</strong>
        <span>cluster state + .aosc-migrations</span>
      </div>

      <div class="sm-data-flight ${flowActive ? "sm-data-flight-active" : ""}" aria-hidden="true">
        <i></i><i></i><i></i><i></i><i></i><i></i>
      </div>
    </section>`;
  }

  function stopAutoplay() {
    if (autoplay) {
      clearInterval(autoplay);
      autoplay = null;
    }
  }

  function bindEvents() {
    container.querySelector("#sm-prev").addEventListener("click", () => {
      stopAutoplay();
      if (current > 0) {
        current -= 1;
        render();
      }
    });
    container.querySelector("#sm-next").addEventListener("click", () => {
      stopAutoplay();
      if (current < steps.length - 1) {
        current += 1;
        render();
      }
    });
    container.querySelector("#sm-play").addEventListener("click", () => {
      if (autoplay) {
        stopAutoplay();
        render();
        return;
      }
      autoplay = setInterval(() => {
        if (current >= steps.length - 1) {
          stopAutoplay();
        } else {
          current += 1;
        }
        render();
      }, 2400);
      render();
    });
    container.querySelector("#sm-fullscreen").addEventListener("click", async () => {
      if (document.fullscreenElement) {
        await document.exitFullscreen();
      } else {
        await container.requestFullscreen();
      }
    });
    container.querySelectorAll("[data-step]").forEach((button) => {
      button.addEventListener("click", () => {
        stopAutoplay();
        current = Number(button.getAttribute("data-step"));
        render();
      });
    });
  }

  function render() {
    const step = steps[current];
    const isFullscreen = document.fullscreenElement === container;
    container.innerHTML = `<div class="sm-shell">
      <div class="sm-header">
        <div>
          <p class="sm-eyebrow">Successful migration path</p>
          <h3>${step.title}</h3>
          <p>${step.kicker}</p>
        </div>
        <div class="sm-actions">
          <button class="sm-btn" id="sm-prev" type="button" ${current === 0 ? "disabled" : ""}>Prev</button>
          <button class="sm-btn sm-btn-primary" id="sm-play" type="button">${autoplay ? "Pause" : "Play"}</button>
          <button class="sm-btn" id="sm-next" type="button" ${current === steps.length - 1 ? "disabled" : ""}>Next</button>
          <button class="sm-btn sm-icon-btn" id="sm-fullscreen" type="button" aria-label="${
            isFullscreen ? "Exit full screen" : "Enter full screen"
          }" title="${isFullscreen ? "Exit full screen" : "Enter full screen"}">${fullscreenIcon}</button>
        </div>
      </div>

      ${renderTimeline()}

      <div class="sm-main">
        ${renderTopology(step)}
        <aside class="sm-step-card">
          <div class="sm-step-count">Step ${current + 1} of ${steps.length}</div>
          <h4>${step.signal}</h4>
          <dl class="sm-facts">
            <div><dt>Coordinator</dt><dd>${displayPhase(step.coord)}</dd></div>
            <div><dt>Shard workers</dt><dd>${step.shards.map(displayPhase).join(", ")}</dd></div>
            <div><dt>Writes</dt><dd>${step.writes}</dd></div>
          </dl>
          ${renderStatusShape(step)}
        </aside>
      </div>

      ${renderPhaseRail("Coordinator phases", coordinatorPhases, [step.coord], previousCoordinatorPhases(step))}
      ${renderPhaseRail("Shard worker phases", shardPhases, step.shards, previousShardPhases())}

      <p class="sm-path-note">
        This walkthrough shows the successful path. Cancellation and failure can interrupt any non-terminal coordinator or shard phase and settle in CANCELLED or FAILED.
        The three shard rows are examples; real source primaries advance independently and may remain in different shard phases at the same time.
      </p>

      <div class="sm-confidence-grid">
        <section>
          <span>Evidence</span>
          <ul>${renderEvidence(step.evidence)}</ul>
        </section>
        <section>
          <span>Safety invariant</span>
          <p>${step.invariant}</p>
        </section>
        <section>
          <span>Operator focus</span>
          <p>${step.operator}</p>
        </section>
      </div>
    </div>`;

    bindEvents();
  }

  render();
}

if (typeof window !== "undefined") {
  window.initStateMachineViz = initStateMachineViz;
  document.addEventListener("DOMContentLoaded", initStateMachineViz);
}
