/**
 * Mermaid DOM fixer for MkDocs Material.
 *
 * Problem: pymdownx.superfences.fence_code_format renders mermaid blocks as
 * <pre><code class="language-mermaid">...</code></pre> but Material's native
 * Mermaid integration looks for <pre class="mermaid"><code>...</code></pre>.
 *
 * This script runs early and fixes the class on matching elements so that
 * Material's built-in Mermaid JS (which handles dark/light mode automatically)
 * can find and render them.
 */
(function () {
  "use strict";

  var MERMAID_KEYWORDS = [
    "graph ", "graph\n",
    "flowchart ", "flowchart\n",
    "sequenceDiagram",
    "classDiagram",
    "stateDiagram",
    "erDiagram",
    "gantt",
    "pie",
    "gitgraph",
    "journey",
    "mindmap",
    "timeline",
    "quadrantChart",
    "sankey",
    "xychart",
    "block-beta"
  ];

  function isMermaid(text) {
    var trimmed = text.trim();
    for (var i = 0; i < MERMAID_KEYWORDS.length; i++) {
      if (trimmed.indexOf(MERMAID_KEYWORDS[i]) === 0) return true;
    }
    return false;
  }

  function fixMermaidBlocks() {
    var codeBlocks = document.querySelectorAll("pre code");
    var fixed = 0;
    codeBlocks.forEach(function (code) {
      if (isMermaid(code.textContent)) {
        var pre = code.parentElement;
        // Set the class on <pre> to "mermaid" — this is what Material looks for
        pre.className = "mermaid";
        // Move the text content directly into <pre> and remove <code>
        pre.textContent = code.textContent.trim();
        fixed++;
      }
    });
    if (fixed > 0) {
      console.log("[mermaid-init] Fixed " + fixed + " mermaid block(s) for Material rendering");
    }
  }

  // Run as early as possible — before Material's Mermaid init scans the DOM
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", fixMermaidBlocks);
  } else {
    fixMermaidBlocks();
  }
})();
