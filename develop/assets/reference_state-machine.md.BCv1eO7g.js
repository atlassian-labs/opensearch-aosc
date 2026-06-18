import{_ as e,o as a,c as d,a2 as r}from"./chunks/framework.C_Knpfj2.js";const h=JSON.parse('{"title":"State Machine Reference","description":"","frontmatter":{},"headers":[],"relativePath":"reference/state-machine.md","filePath":"reference/state-machine.md","lastUpdated":1781637126000}'),o={name:"reference/state-machine.md"};function n(s,t,c,i,l,I){return a(),d("div",null,[...t[0]||(t[0]=[r(`<h1 id="state-machine-reference" tabindex="-1">State Machine Reference <a class="header-anchor" href="#state-machine-reference" aria-label="Permalink to &quot;State Machine Reference&quot;">​</a></h1><p>AOSC tracks migration progress at two levels:</p><ul><li><code>CoordinatorPhase</code>: migration-level orchestration on the cluster-manager node.</li><li><code>ShardPhase</code>: per-source-shard worker progress on data nodes.</li></ul><p>Active state is coordinated through cluster state and in-memory coordinator caches. Detailed and terminal migration documents are stored in <code>.aosc-migrations</code>.</p><p>For the visual migration walkthrough, see <a href="./../how-it-works">How AOSC Works</a>. That walkthrough is checked against the phase enums and transition tables in:</p><ul><li><code>CoordinatorPhase</code> and <code>MigrationCoordinator</code></li><li><code>ShardPhase</code> and <code>ShardMigrationWorker</code></li><li>the <code>_status</code> response shape documented in <a href="./rest-api">REST API</a></li></ul><p>This page is the phase reference. Cancellation and failure are interrupt paths from non-terminal states; see the diagrams below for those transitions.</p><h2 id="coordinator-phases" tabindex="-1">Coordinator Phases <a class="header-anchor" href="#coordinator-phases" aria-label="Permalink to &quot;Coordinator Phases&quot;">​</a></h2><pre class="mermaid">stateDiagram-v2
    [*] --&gt; INITIALIZING
    INITIALIZING --&gt; ACTIVE
    ACTIVE --&gt; PREPARING_TARGET
    PREPARING_TARGET --&gt; CUTTING_OVER
    CUTTING_OVER --&gt; CATCHING_UP
    CATCHING_UP --&gt; COMPLETING
    COMPLETING --&gt; COMPLETED
    COMPLETED --&gt; [*]

    INITIALIZING --&gt; FAILING
    ACTIVE --&gt; FAILING
    PREPARING_TARGET --&gt; FAILING
    CUTTING_OVER --&gt; FAILING
    CATCHING_UP --&gt; FAILING
    COMPLETING --&gt; FAILING
    FAILING --&gt; FAILED
    FAILED --&gt; [*]

    INITIALIZING --&gt; CANCELLING
    ACTIVE --&gt; CANCELLING
    PREPARING_TARGET --&gt; CANCELLING
    CUTTING_OVER --&gt; CANCELLING
    CATCHING_UP --&gt; CANCELLING
    COMPLETING --&gt; CANCELLING
    CANCELLING --&gt; CANCELLED
    CANCELLED --&gt; [*]
</pre><div class="aosc-table-scroll"><table class="aosc-table"><thead><tr><th>Phase</th><th>Terminal?</th><th>Meaning</th></tr></thead><tbody><tr><td><code>INITIALIZING</code></td><td>No</td><td>Validate the request, capture metadata, create the migration document, and prepare worker state.</td></tr><tr><td><code>ACTIVE</code></td><td>No</td><td>Shard workers are backfilling, replaying, and converging.</td></tr><tr><td><code>PREPARING_TARGET</code></td><td>No</td><td>Restore target settings needed for cutover and wait for target readiness.</td></tr><tr><td><code>CUTTING_OVER</code></td><td>No</td><td>Apply source write block and flush source.</td></tr><tr><td><code>CATCHING_UP</code></td><td>No</td><td>Workers replay final source operations after the write block.</td></tr><tr><td><code>COMPLETING</code></td><td>No</td><td>Validate document counts, swap alias, remove source write block if configured, and clean up.</td></tr><tr><td><code>COMPLETED</code></td><td>Yes</td><td>Migration succeeded.</td></tr><tr><td><code>CANCELLING</code></td><td>No</td><td>Cancellation requested; workers and coordinator are cleaning up.</td></tr><tr><td><code>CANCELLED</code></td><td>Yes</td><td>Cancellation complete.</td></tr><tr><td><code>FAILING</code></td><td>No</td><td>Failure cleanup is running.</td></tr><tr><td><code>FAILED</code></td><td>Yes</td><td>Migration failed and reached terminal cleanup.</td></tr></tbody></table></div><h2 id="shard-phases" tabindex="-1">Shard Phases <a class="header-anchor" href="#shard-phases" aria-label="Permalink to &quot;Shard Phases&quot;">​</a></h2><pre class="mermaid">stateDiagram-v2
    [*] --&gt; PENDING
    PENDING --&gt; ACQUIRING_LEASE
    ACQUIRING_LEASE --&gt; BACKFILLING
    BACKFILLING --&gt; REPLAYING
    REPLAYING --&gt; CONVERGING
    CONVERGING --&gt; CONVERGED
    CONVERGED --&gt; CATCHING_UP
    CATCHING_UP --&gt; COMPLETING
    COMPLETING --&gt; COMPLETED
    COMPLETED --&gt; [*]

    PENDING --&gt; CANCELLING
    ACQUIRING_LEASE --&gt; CANCELLING
    BACKFILLING --&gt; CANCELLING
    REPLAYING --&gt; CANCELLING
    CONVERGING --&gt; CANCELLING
    CONVERGED --&gt; CANCELLING
    CATCHING_UP --&gt; CANCELLING
    COMPLETING --&gt; CANCELLING
    CANCELLING --&gt; CANCELLED
    CANCELLED --&gt; [*]

    PENDING --&gt; FAILING
    ACQUIRING_LEASE --&gt; FAILING
    BACKFILLING --&gt; FAILING
    REPLAYING --&gt; FAILING
    CONVERGING --&gt; FAILING
    CONVERGED --&gt; FAILING
    CATCHING_UP --&gt; FAILING
    COMPLETING --&gt; FAILING
    FAILING --&gt; FAILED
    FAILED --&gt; [*]
</pre><div class="aosc-table-scroll"><table class="aosc-table"><thead><tr><th>Phase</th><th>Terminal?</th><th>Meaning</th></tr></thead><tbody><tr><td><code>PENDING</code></td><td>No</td><td>Waiting for a backfill permit.</td></tr><tr><td><code>ACQUIRING_LEASE</code></td><td>No</td><td>Acquiring a retention lease on the source shard.</td></tr><tr><td><code>BACKFILLING</code></td><td>No</td><td>Copying source documents into the target.</td></tr><tr><td><code>REPLAYING</code></td><td>No</td><td>Applying operation history from the source shard.</td></tr><tr><td><code>CONVERGING</code></td><td>No</td><td>Replaying additional rounds until the gap is below the threshold.</td></tr><tr><td><code>CONVERGED</code></td><td>No</td><td>Waiting for the coordinator to enter cutover.</td></tr><tr><td><code>CATCHING_UP</code></td><td>No</td><td>Replaying final operations after the source write block.</td></tr><tr><td><code>COMPLETING</code></td><td>No</td><td>Releasing leases and finalizing worker state.</td></tr><tr><td><code>COMPLETED</code></td><td>Yes</td><td>Shard finished successfully.</td></tr><tr><td><code>CANCELLING</code></td><td>No</td><td>Worker is stopping and cleaning up after cancellation.</td></tr><tr><td><code>CANCELLED</code></td><td>Yes</td><td>Worker cancellation complete.</td></tr><tr><td><code>FAILING</code></td><td>No</td><td>Worker failure cleanup is running.</td></tr><tr><td><code>FAILED</code></td><td>Yes</td><td>Worker failed.</td></tr></tbody></table></div><h2 id="successful-path" tabindex="-1">Successful Path <a class="header-anchor" href="#successful-path" aria-label="Permalink to &quot;Successful Path&quot;">​</a></h2><div class="language-text vp-adaptive-theme"><button title="Copy Code" class="copy"></button><span class="lang">text</span><pre class="shiki shiki-themes github-light github-dark vp-code" tabindex="0"><code><span class="line"><span>Coordinator:</span></span>
<span class="line"><span>INITIALIZING -&gt; ACTIVE -&gt; PREPARING_TARGET -&gt; CUTTING_OVER -&gt; CATCHING_UP -&gt; COMPLETING -&gt; COMPLETED</span></span>
<span class="line"><span></span></span>
<span class="line"><span>Shard:</span></span>
<span class="line"><span>PENDING -&gt; ACQUIRING_LEASE -&gt; BACKFILLING -&gt; REPLAYING -&gt; CONVERGING -&gt; CONVERGED -&gt; CATCHING_UP -&gt; COMPLETING -&gt; COMPLETED</span></span></code></pre></div><h2 id="storage-and-status-reads" tabindex="-1">Storage and Status Reads <a class="header-anchor" href="#storage-and-status-reads" aria-label="Permalink to &quot;Storage and Status Reads&quot;">​</a></h2><ul><li>Active migration status is served from the coordinator cache when available.</li><li>Terminal and historical migration data is read from <code>.aosc-migrations</code>.</li><li><code>_list</code> returns a slim summary projection.</li><li><code>_status</code> returns the detailed migration document for one source index.</li></ul><h2 id="debugging-stuck-phases" tabindex="-1">Debugging Stuck Phases <a class="header-anchor" href="#debugging-stuck-phases" aria-label="Permalink to &quot;Debugging Stuck Phases&quot;">​</a></h2><div class="aosc-table-scroll"><table class="aosc-table"><thead><tr><th>Stuck Phase</th><th>First Checks</th></tr></thead><tbody><tr><td><code>PENDING</code></td><td>Backfill permit limit: <code>aosc.backfill.max_concurrent_per_node</code>.</td></tr><tr><td><code>ACQUIRING_LEASE</code></td><td>Source primary health and retention lease errors.</td></tr><tr><td><code>BACKFILLING</code></td><td>Target indexing pressure, bulk rejections, batch size, worker logs.</td></tr><tr><td><code>CONVERGING</code></td><td>Source write rate versus replay throughput and global checkpoint progress.</td></tr><tr><td><code>PREPARING_TARGET</code></td><td>Target shard allocation and target health.</td></tr><tr><td><code>CATCHING_UP</code></td><td>Source write block state, replay errors, and global checkpoint.</td></tr><tr><td><code>COMPLETING</code></td><td>Alias update errors, document count validation, source write block cleanup.</td></tr></tbody></table></div><p>See <a href="./../operations/runbook-stuck-migration">Runbook: Stuck Migration</a>.</p>`,20)])])}const g=e(o,[["render",n]]);export{h as __pageData,g as default};
