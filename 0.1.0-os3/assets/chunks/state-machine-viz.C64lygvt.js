function m(){const t=document.getElementById("sm-viz");if(!t||t.dataset.initialized)return;t.dataset.initialized="true";const p=["INITIALIZING","ACTIVE","PREPARING_TARGET","CUTTING_OVER","CATCHING_UP","COMPLETING","COMPLETED","CANCELLING","CANCELLED","FAILING","FAILED"],w=["PENDING","ACQUIRING_LEASE","BACKFILLING","REPLAYING","CONVERGING","CONVERGED","CATCHING_UP","COMPLETING","COMPLETED","CANCELLING","CANCELLED","FAILING","FAILED"],f=`<svg class="aosc-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    <path d="M8 3H3v5M16 3h5v5M21 16v5h-5M3 16v5h5" />
  </svg>`,i=[{title:"Start accepted",kicker:"Coordinator prepares the migration",coord:"INITIALIZING",shards:["PENDING"],writes:"Source writes continue",alias:"Alias still points to source",target:"Target exists but is not live",signal:"Coordinator validates the request and creates durable migration state.",evidence:["Migration document appears in .aosc-migrations","Workers are assigned to source primary shards"],invariant:"No user traffic is moved until source, target, alias, and plugin installation checks pass.",operator:"Confirm target mappings/settings before starting; AOSC will not create the target index.",flow:"setup",sourceTone:"active",workerTone:"pending",targetTone:"idle",aliasTone:"source"},{title:"Workers start",kicker:"Coordinator opens the active gate",coord:"ACTIVE",shards:["ACQUIRING_LEASE"],writes:"Source writes continue",alias:"Alias still points to source",target:"Target receives copied data only",signal:"Workers acquire source retention leases so operation history remains available.",evidence:["Shard status moves to ACQUIRING_LEASE","Retention lease requests appear in logs"],invariant:"Source remains authoritative; target is still a private build artifact.",operator:"If this stalls, check source primary health and retention lease errors.",flow:"leases",sourceTone:"active",workerTone:"active",targetTone:"idle",aliasTone:"source"},{title:"Backfill",kicker:"Shard workers copy existing documents",coord:"ACTIVE",shards:["BACKFILLING"],writes:"Source writes continue",alias:"Alias still points to source",target:"Target is bulk indexed",signal:"Each source primary shard scans documents and writes transformed operations to the target.",evidence:["Backfill counters increase","Bulk requests target the new index","Backfill permits bound heavy work per node"],invariant:"Bulk writes use idempotent indexing; retrying a batch should not duplicate documents.",operator:"Watch bulk rejections, indexing pressure, and adaptive batch size.",flow:"backfill",sourceTone:"active",workerTone:"busy",targetTone:"building",aliasTone:"source"},{title:"Replay",kicker:"Workers apply writes that arrived during backfill",coord:"ACTIVE",shards:["REPLAYING"],writes:"Source writes continue",alias:"Alias still points to source",target:"Target catches up from translog history",signal:"Workers replay source operation history from retained sequence numbers.",evidence:["Replay counters increase","Global checkpoint and local checkpoint gaps shrink"],invariant:"The target is allowed to lag, but the retained operation range must cover the gap.",operator:"If replay cannot keep up, reduce source write rate or increase target capacity.",flow:"replay",sourceTone:"active",workerTone:"busy",targetTone:"catching-up",aliasTone:"source"},{title:"Converge",kicker:"Replay rounds shrink the remaining gap",coord:"ACTIVE",shards:["CONVERGING"],writes:"Source writes continue",alias:"Alias still points to source",target:"Target is nearly current",signal:"Workers run repeated replay rounds until the configured convergence threshold is met.",evidence:["Convergence rounds increase","Remaining operation gap falls below threshold"],invariant:"Cutover is not attempted while any shard is still materially behind.",operator:"Tune convergence threshold for the write rate and acceptable cutover window.",flow:"converge",sourceTone:"active",workerTone:"busy",targetTone:"catching-up",aliasTone:"source"},{title:"All shards converged",kicker:"Coordinator waits for every shard",coord:"ACTIVE",shards:["CONVERGED"],writes:"Source writes continue",alias:"Alias still points to source",target:"Target is ready for final preparation",signal:"Each worker reports CONVERGED; the coordinator can advance only after all workers agree.",evidence:["Every shard status is CONVERGED","Coordinator active phase has no unfinished workers"],invariant:"A single slow shard keeps the whole migration in the safe pre-cutover path.",operator:"Investigate the slowest shard instead of forcing cutover early.",flow:"converged",sourceTone:"active",workerTone:"ready",targetTone:"ready",aliasTone:"source"},{title:"Prepare target",kicker:"Restore serving settings and wait for readiness",coord:"PREPARING_TARGET",shards:["CONVERGED"],writes:"Source writes continue",alias:"Alias still points to source",target:"Target replicas and refresh behavior are restored",signal:"Coordinator restores target settings and waits for the target to become ready.",evidence:["Target readiness checks pass","Replica recovery and refresh settings settle"],invariant:"The target must be serving-ready before writes are blocked on the source.",operator:"Check allocation, disk watermarks, and target health if this phase stalls.",flow:"prepare",sourceTone:"active",workerTone:"ready",targetTone:"ready",aliasTone:"source"},{title:"Cutover begins",kicker:"Source writes are briefly blocked",coord:"CUTTING_OVER",shards:["CONVERGED"],writes:"Source writes blocked",alias:"Alias still points to source",target:"Target waits for final catch-up",signal:"Coordinator applies the source write block and flushes the source index.",evidence:["Source write block is visible in index metadata","Flush completes before final replay"],invariant:"Observed write-block time ranges from about 2s to 30s depending on index size.",operator:"Announce this as the only user-visible write interruption window.",flow:"block",sourceTone:"blocked",workerTone:"ready",targetTone:"ready",aliasTone:"source"},{title:"Final catch-up",kicker:"Workers replay the last operations",coord:"CATCHING_UP",shards:["CATCHING_UP"],writes:"Source writes blocked",alias:"Alias still points to source",target:"Target receives final operations",signal:"Workers replay operations that landed before the write block and then stop.",evidence:["Shard phases move to CATCHING_UP","Remaining operation gap reaches zero"],invariant:"No new source writes can appear after the write block, so final replay has a fixed end.",operator:"If this stalls, inspect worker logs and source translog replay errors.",flow:"final",sourceTone:"blocked",workerTone:"busy",targetTone:"catching-up",aliasTone:"source"},{title:"Workers finalize",kicker:"Shard workers release migration resources",coord:"CATCHING_UP",shards:["COMPLETING"],writes:"Source writes blocked",alias:"Alias still points to source",target:"Target has the complete migrated dataset",signal:"Shard workers release retention leases and finalize worker-local state.",evidence:["Shard phases move to COMPLETING","Retention lease release is attempted per shard"],invariant:"Alias swap still has not happened; source remains the application-facing index.",operator:"Do not manually redirect traffic while shard cleanup is still running.",flow:"worker-cleanup",sourceTone:"blocked",workerTone:"busy",targetTone:"ready",aliasTone:"source"},{title:"Workers complete",kicker:"Coordinator receives final shard acknowledgements",coord:"CATCHING_UP",shards:["COMPLETED"],writes:"Source writes blocked",alias:"Alias still points to source",target:"Target has the complete migrated dataset",signal:"Coordinator waits until every source-primary worker reports COMPLETED.",evidence:["Every shard status is COMPLETED","Coordinator CATCHING_UP gate can advance"],invariant:"Alias swap waits for all shard workers, not just most of them.",operator:"If one shard lags here, inspect that shard's catch-up and lease-release logs.",flow:"workers-done",sourceTone:"blocked",workerTone:"done",targetTone:"ready",aliasTone:"source"},{title:"Alias swap",kicker:"Coordinator makes the target live",coord:"COMPLETING",shards:["COMPLETED"],writes:"Writes resume on target after swap",alias:"Alias moves from source to target",target:"Target becomes live",signal:"Coordinator validates completion, swaps the alias atomically, and removes the source write block if configured.",evidence:["Alias update response succeeds","Status document records completion metadata"],invariant:"Before alias swap, failure leaves traffic on source; after swap, AOSC does not roll back automatically.",operator:"Verify alias membership and document counts immediately after completion.",flow:"swap",sourceTone:"blocked",workerTone:"done",targetTone:"live",aliasTone:"moving"},{title:"Migration complete",kicker:"Target is authoritative",coord:"COMPLETED",shards:["COMPLETED"],writes:"Writes go to target",alias:"Alias points to target",target:"Target is live",signal:"Migration reaches terminal success; source concrete index remains for operator review.",evidence:["Coordinator phase is COMPLETED","Shard phases are COMPLETED","Alias resolves to the target index"],invariant:"Terminal status is durable in .aosc-migrations for later inspection.",operator:"Keep or delete the old source index according to your rollback and retention plan.",flow:"done",sourceTone:"idle",workerTone:"done",targetTone:"live",aliasTone:"target"}];let r=0,n=null;function l(e){return e.replace(/_/g," ")}function E(e){return p.slice(0,p.indexOf(e.coord))}function k(){const e=[];for(let a=0;a<r;a+=1)i[a].shards.forEach(s=>{e.includes(s)||e.push(s)});return e}function T(e,a,s){return a.includes(e)?"sm-active":s.includes(e)?"sm-done":"sm-pending"}function h(e,a){return`sm-node sm-node-${e} sm-tone-${a}`}function g(e,a){return[0,1,2].map(s=>`<div class="sm-shard sm-shard-${a}">
          <span>${e}${s}</span>
          <i></i><i></i><i></i><i></i>
        </div>`).join("")}function b(e){const a={setup:[["shard 0","PENDING","waiting for coordinator",6,"pending"],["shard 1","PENDING","waiting for coordinator",6,"pending"],["shard 2","PENDING","waiting for coordinator",6,"pending"]],leases:[["shard 0","ACQUIRING_LEASE","pinning translog",38,"active"],["shard 1","ACQUIRING_LEASE","pinning translog",24,"active"],["shard 2","PENDING","permit queued",8,"pending"]],backfill:[["shard 0","BACKFILLING","bulk copy 78%",78,"busy"],["shard 1","BACKFILLING","bulk copy 44%",44,"busy"],["shard 2","ACQUIRING_LEASE","starting later",16,"active"]],replay:[["shard 0","REPLAYING","history gap 210 ops",72,"busy"],["shard 1","BACKFILLING","last backfill pages",58,"busy"],["shard 2","REPLAYING","history gap 840 ops",45,"busy"]],converge:[["shard 0","CONVERGING","below threshold soon",86,"busy"],["shard 1","REPLAYING","catching write burst",62,"busy"],["shard 2","CONVERGING","round 7",74,"busy"]],converged:[["shard 0","CONVERGED","ready",100,"done"],["shard 1","CONVERGED","ready",100,"done"],["shard 2","CONVERGED","ready",100,"done"]],prepare:[["shard 0","CONVERGED","waiting for target",100,"done"],["shard 1","CONVERGED","waiting for target",100,"done"],["shard 2","CONVERGED","waiting for target",100,"done"]],block:[["shard 0","CONVERGED","source blocked",100,"done"],["shard 1","CONVERGED","source blocked",100,"done"],["shard 2","CONVERGED","source blocked",100,"done"]],final:[["shard 0","CATCHING_UP","final replay done",100,"done"],["shard 1","CATCHING_UP","last ops applying",84,"busy"],["shard 2","CATCHING_UP","last ops applying",68,"busy"]],"worker-cleanup":[["shard 0","COMPLETING","lease released",100,"done"],["shard 1","COMPLETING","releasing lease",94,"busy"],["shard 2","COMPLETING","releasing lease",90,"busy"]],"workers-done":[["shard 0","COMPLETED","acknowledged",100,"done"],["shard 1","COMPLETED","acknowledged",100,"done"],["shard 2","COMPLETED","acknowledged",100,"done"]],swap:[["shard 0","COMPLETED","alias swap waits",100,"done"],["shard 1","COMPLETED","alias swap waits",100,"done"],["shard 2","COMPLETED","alias swap waits",100,"done"]],done:[["shard 0","COMPLETED","target authoritative",100,"done"],["shard 1","COMPLETED","target authoritative",100,"done"],["shard 2","COMPLETED","target authoritative",100,"done"]]};return a[e]||a.setup}function C(e){return`<div class="sm-worker-lanes" aria-label="Independent shard worker progress">
      <span class="sm-worker-lanes-note">source primaries advance independently</span>
      ${b(e).map(([a,s,c,u,L])=>`<div class="sm-worker-lane sm-worker-${L}">
            <div>
              <strong>${a}</strong>
              <span>${l(s)}</span>
            </div>
            <em>${c}</em>
            <i><b style="width: ${u}%"></b></i>
          </div>`).join("")}
    </div>`}function v(e,a,s,c){return`<section class="sm-phase-rail" aria-label="${e}">
      <div class="sm-phase-rail-title">${e}</div>
      <div class="sm-phase-list">
        ${a.map(u=>`<span class="sm-phase ${T(u,s,c)}">${l(u)}</span>`).join("")}
      </div>
    </section>`}function y(e){return e.map(a=>`<li>${a}</li>`).join("")}function I(e){return`<div class="sm-status-shape" aria-label="Status fields shown during this step">
      <span>Status fields</span>
      <code>phase: ${e.coord}</code>
      <code>shards.*.phase: ${e.shards.join(" | ")}</code>
    </div>`}function N(){return`<div class="sm-timeline" role="tablist" aria-label="Migration walkthrough steps">
      ${i.map((e,a)=>`<button class="sm-dot ${a===r?"sm-dot-active":""} ${a<r?"sm-dot-done":""}" type="button" role="tab" aria-selected="${a===r}" data-step="${a}">
            <span class="sm-dot-index">${a+1}</span>
            <span class="sm-dot-title">${e.title}</span>
          </button>`).join("")}
    </div>`}function A(e){const a=e.sourceTone==="blocked",s=e.aliasTone==="target"||e.aliasTone==="moving",c=["backfill","replay","converge","final"].includes(e.flow);return`<section class="sm-simulator sm-sim-${e.flow}" aria-label="Animated migration simulator">
      <div class="${h("source",e.sourceTone)}">
        <span class="sm-node-eyebrow">Source index</span>
        <strong>3 primary shards</strong>
        <span>${e.writes}</span>
        <div class="sm-write-stream ${a?"sm-write-blocked":""}" aria-label="${a?"Writes blocked":"Writes flowing"}">
          <b>app writes</b>
          <i></i><i></i><i></i>
          <em>${a?"blocked":"flowing"}</em>
        </div>
        <div class="sm-shards">${g("s",a?"blocked":"source")}</div>
      </div>

      <div class="${h("plugin",e.workerTone)}">
        <span class="sm-node-eyebrow">AOSC plugin</span>
        <strong>${e.shards.map(l).join(", ")}</strong>
        <span>${e.signal}</span>
        ${C(e.flow)}
      </div>

      <div class="${h("target",e.targetTone)}">
        <span class="sm-node-eyebrow">Target index</span>
        <strong>${s?"Serving alias traffic":"Building in background"}</strong>
        <span>${e.target}</span>
        <div class="sm-shards">${g("t",s?"live":"target")}</div>
      </div>

      <div class="sm-alias-route sm-alias-${s?"target":"source"}">
        <span>alias</span>
        <strong>${s?"target":"source"}</strong>
      </div>

      <div class="sm-control-plane">
        <span class="sm-node-eyebrow">Control plane</span>
        <strong>${l(e.coord)}</strong>
        <span>cluster state + .aosc-migrations</span>
      </div>

      <div class="sm-data-flight ${c?"sm-data-flight-active":""}" aria-hidden="true">
        <i></i><i></i><i></i><i></i><i></i><i></i>
      </div>
    </section>`}function d(){n&&(clearInterval(n),n=null)}function G(){t.querySelector("#sm-prev").addEventListener("click",()=>{d(),r>0&&(r-=1,o())}),t.querySelector("#sm-next").addEventListener("click",()=>{d(),r<i.length-1&&(r+=1,o())}),t.querySelector("#sm-play").addEventListener("click",()=>{if(n){d(),o();return}n=setInterval(()=>{r>=i.length-1?d():r+=1,o()},2400),o()}),t.querySelector("#sm-fullscreen").addEventListener("click",async()=>{document.fullscreenElement?await document.exitFullscreen():await t.requestFullscreen()}),t.querySelectorAll("[data-step]").forEach(e=>{e.addEventListener("click",()=>{d(),r=Number(e.getAttribute("data-step")),o()})})}function o(){const e=i[r],a=document.fullscreenElement===t;t.innerHTML=`<div class="sm-shell">
      <div class="sm-header">
        <div>
          <p class="sm-eyebrow">Successful migration path</p>
          <h3>${e.title}</h3>
          <p>${e.kicker}</p>
        </div>
        <div class="sm-actions">
          <button class="sm-btn" id="sm-prev" type="button" ${r===0?"disabled":""}>Prev</button>
          <button class="sm-btn sm-btn-primary" id="sm-play" type="button">${n?"Pause":"Play"}</button>
          <button class="sm-btn" id="sm-next" type="button" ${r===i.length-1?"disabled":""}>Next</button>
          <button class="sm-btn sm-icon-btn" id="sm-fullscreen" type="button" aria-label="${a?"Exit full screen":"Enter full screen"}" title="${a?"Exit full screen":"Enter full screen"}">${f}</button>
        </div>
      </div>

      ${N()}

      <div class="sm-main">
        ${A(e)}
        <aside class="sm-step-card">
          <div class="sm-step-count">Step ${r+1} of ${i.length}</div>
          <h4>${e.signal}</h4>
          <dl class="sm-facts">
            <div><dt>Coordinator</dt><dd>${l(e.coord)}</dd></div>
            <div><dt>Shard workers</dt><dd>${e.shards.map(l).join(", ")}</dd></div>
            <div><dt>Writes</dt><dd>${e.writes}</dd></div>
          </dl>
          ${I(e)}
        </aside>
      </div>

      ${v("Coordinator phases",p,[e.coord],E(e))}
      ${v("Shard worker phases",w,e.shards,k())}

      <p class="sm-path-note">
        This walkthrough shows the successful path. Cancellation and failure can interrupt any non-terminal coordinator or shard phase and settle in CANCELLED or FAILED.
        The three shard rows are examples; real source primaries advance independently and may remain in different shard phases at the same time.
      </p>

      <div class="sm-confidence-grid">
        <section>
          <span>Evidence</span>
          <ul>${y(e.evidence)}</ul>
        </section>
        <section>
          <span>Safety invariant</span>
          <p>${e.invariant}</p>
        </section>
        <section>
          <span>Operator focus</span>
          <p>${e.operator}</p>
        </section>
      </div>
    </div>`,G()}o()}typeof window<"u"&&(window.initStateMachineViz=m,document.addEventListener("DOMContentLoaded",m));
