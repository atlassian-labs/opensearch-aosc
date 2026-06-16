function initStateMachineViz() {
  const container = document.getElementById("sm-viz");
  if (!container) return;
  if (container.dataset.initialized) return;
  container.dataset.initialized = "true";

  // signal direction: "down" = coordinator to shards, "up" = shards to coordinator, "none" = no signal
  const steps = [
    {
      title: "1. Initializing",
      desc: "Coordinator validates the pre-created target index, applies transient settings, and prepares shard workers.",
      coord: "INITIALIZING",
      shards: ["PENDING"],
      signal: "Target validated, workers created",
      dir: "none",
    },
    {
      title: "2. Active",
      desc: "Gate opens. Shard workers begin acquiring retention leases on their source shards.",
      coord: "ACTIVE",
      shards: ["ACQUIRING_LEASE"],
      signal: "Gate opened; workers start",
      dir: "down",
    },
    {
      title: "3. Backfilling",
      desc: "Each shard worker copies source documents into the pre-created target index.",
      coord: "ACTIVE",
      shards: ["BACKFILLING"],
      signal: "Lease acquired; backfill started",
      dir: "up",
    },
    {
      title: "4. Replaying",
      desc: "Backfill done. Each shard replays source writes that arrived during backfill using operation history.",
      coord: "ACTIVE",
      shards: ["REPLAYING"],
      signal: "Backfill complete; replay started",
      dir: "up",
    },
    {
      title: "5. Converging",
      desc: "Replay loop continues. Gap between source and target shrinks each round.",
      coord: "ACTIVE",
      shards: ["CONVERGING"],
      signal: "Gap shrinking each round",
      dir: "up",
    },
    {
      title: "6. Shard converged",
      desc: "Gap is below configured threshold. Shard worker reports CONVERGED to coordinator.",
      coord: "ACTIVE",
      shards: ["CONVERGED"],
      signal: "Shard converged (gap below threshold)",
      dir: "up",
    },
    {
      title: "7. Preparing target",
      desc: "All shards converged. Coordinator restores target replicas and refresh interval, waits for recovery.",
      coord: "PREPARING_TARGET",
      shards: ["CONVERGED"],
      signal: "All shards converged; restore replicas",
      dir: "up",
    },
    {
      title: "8. Cutting over",
      desc: "Target ready. Coordinator applies write block to source index and flushes.",
      coord: "CUTTING_OVER",
      shards: ["CONVERGED"],
      signal: "Write block applied to source",
      dir: "down",
    },
    {
      title: "9. Final catch-up",
      desc: "Coordinator signals shards to do final replay. Each shard replays any remaining ops that arrived before the write block.",
      coord: "CATCHING_UP",
      shards: ["CATCHING_UP"],
      signal: "Final replay signal; shards catching up",
      dir: "down",
    },
    {
      title: "10. Shard workers completed",
      desc: "Each shard finishes final replay and reports COMPLETED to coordinator.",
      coord: "CATCHING_UP",
      shards: ["COMPLETED"],
      signal: "Shard worker completed",
      dir: "up",
    },
    {
      title: "11. Completing",
      desc: "All shards completed. Coordinator validates doc counts, swaps alias atomically, removes write block, restores settings.",
      coord: "COMPLETING",
      shards: ["COMPLETED"],
      signal: "Doc count verified; alias swapped",
      dir: "none",
    },
    {
      title: "12. Done",
      desc: "Migration finished. Target is live under the alias. Source concrete index remains available for operator review.",
      coord: "COMPLETED",
      shards: ["COMPLETED"],
      signal: "Migration complete",
      dir: "none",
    },
  ];

  const coordPhases = [
    "INITIALIZING", "ACTIVE", "PREPARING_TARGET", "CUTTING_OVER",
    "CATCHING_UP", "COMPLETING", "COMPLETED",
  ];
  const shardPhases = [
    "PENDING", "ACQUIRING_LEASE", "BACKFILLING", "REPLAYING",
    "CONVERGING", "CONVERGED", "CATCHING_UP", "COMPLETED",
  ];

  let current = 0;

  function phaseClass(phase, activePhases, allPrevPhases) {
    if (activePhases.includes(phase)) return "sm-active";
    if (allPrevPhases.includes(phase)) return "sm-done";
    return "sm-pending";
  }

  function signalArrow(dir) {
    if (dir === "down") return "Coordinator to shards";
    if (dir === "up") return "Shards to coordinator";
    return "";
  }

  function render() {
    const step = steps[current];
    const prevCoord = coordPhases.slice(0, coordPhases.indexOf(step.coord));
    const prevShard = [];
    for (let i = 0; i < current; i++) {
      steps[i].shards.forEach((s) => { if (!prevShard.includes(s)) prevShard.push(s); });
    }

    let html = `<div class="sm-controls">
      <button class="sm-btn" id="sm-prev" ${current === 0 ? "disabled" : ""}>Prev</button>
      <span class="sm-step-label">${step.title}</span>
      <button class="sm-btn" id="sm-next" ${current === steps.length - 1 ? "disabled" : ""}>Next</button>
    </div>`;

    html += `<div class="sm-desc">${step.desc}</div>`;

    html += `<div class="sm-tier"><div class="sm-tier-label">Coordinator</div><div class="sm-phases">`;
    coordPhases.forEach((p) => {
      const cls = phaseClass(p, [step.coord], prevCoord);
      html += `<div class="sm-phase ${cls}">${p.replace(/_/g, " ")}</div>`;
    });
    html += `</div></div>`;

    const arrowDir = signalArrow(step.dir);
    const arrowClass = step.dir === "down" ? "sm-signal-down" : step.dir === "up" ? "sm-signal-up" : "sm-signal-none";
    html += `<div class="sm-signal-row ${arrowClass}">`;
    if (arrowDir) {
      html += `<span class="sm-signal-dir">${arrowDir}</span>`;
    }
    html += `<span class="sm-signal-msg">${step.signal}</span>`;
    html += `</div>`;

    html += `<div class="sm-tier"><div class="sm-tier-label">Shard workers</div><div class="sm-phases">`;
    shardPhases.forEach((p) => {
      const cls = phaseClass(p, step.shards, prevShard);
      html += `<div class="sm-phase ${cls}">${p.replace(/_/g, " ")}</div>`;
    });
    html += `</div></div>`;

    html += `<div class="sm-progress">Step ${current + 1} of ${steps.length}</div>`;

    container.innerHTML = html;

    document.getElementById("sm-prev").addEventListener("click", () => { if (current > 0) { current--; render(); } });
    document.getElementById("sm-next").addEventListener("click", () => { if (current < steps.length - 1) { current++; render(); } });
  }

  render();
}

// Run on initial page load
document.addEventListener("DOMContentLoaded", initStateMachineViz);
// Re-run on MkDocs Material instant navigation
if (typeof document$ !== "undefined") { document$.subscribe(initStateMachineViz); }
