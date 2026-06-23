/*
 * SPDX-License-Identifier: Apache-2.0
 */
import fs from "node:fs";
import path from "node:path";
import { Buffer } from "node:buffer";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitepress";
import type { DefaultTheme } from "vitepress";

const docsRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const docsBase = process.env.DOCS_BASE || "/opensearch-aosc/";

function sidebar(): DefaultTheme.Sidebar {
  return [
    {
      text: "Get Started",
      items: [
        { text: "What Is AOSC?", link: "/get-started/what-is-aosc" },
        { text: "How AOSC Works", link: "/how-it-works" },
        { text: "Your First Migration", link: "/get-started/your-first-migration" },
      ],
    },
    {
      text: "How-to Guides",
      items: [
        { text: "Install the Plugin", link: "/how-to/install-the-plugin" },
        { text: "Plan a Migration", link: "/how-to/plan-a-migration" },
        { text: "Change Shard Count", link: "/how-to/change-shard-count" },
        { text: "Change Mappings or Settings", link: "/how-to/change-mappings-or-settings" },
        { text: "Transform Documents", link: "/how-to/transform-documents" },
        { text: "Cancel and Clean Up", link: "/how-to/cancel-and-clean-up" },
      ],
    },
    {
      text: "Operations",
      items: [
        { text: "Monitor a Migration", link: "/operations/monitor-a-migration" },
        { text: "Runbook: Stuck Migration", link: "/operations/runbook-stuck-migration" },
        { text: "Troubleshoot by Symptom", link: "/operations/troubleshoot-by-symptom" },
      ],
    },
    {
      text: "Reference",
      items: [
        { text: "REST API", link: "/reference/rest-api" },
        { text: "Compatibility", link: "/reference/compatibility" },
        { text: "Configuration", link: "/reference/configuration" },
        { text: "State Machine", link: "/reference/state-machine" },
        { text: "Error Reference", link: "/reference/error-codes" },
        { text: "Metrics", link: "/reference/metrics" },
        { text: "Known Limitations", link: "/reference/known-limitations" },
      ],
    },
    {
      text: "Concepts",
      items: [
        { text: "Architecture Overview", link: "/concepts/architecture-overview" },
        { text: "Correctness Model", link: "/concepts/correctness-model" },
        { text: "Routing and Replay", link: "/concepts/routing-and-replay" },
        { text: "Backpressure & Throttling", link: "/concepts/backpressure-and-throttling" },
        { text: "Comparison & Tradeoffs", link: "/concepts/comparison-and-tradeoffs" },
      ],
    },
    {
      text: "Contributing",
      items: [
        { text: "Dev Environment", link: "/contributing/dev-environment" },
        { text: "Running Tests", link: "/contributing/running-tests" },
        { text: "Code Layout", link: "/contributing/code-layout" },
        { text: "Releases", link: "/contributing/releases" },
        { text: "Extension Points", link: "/contributing/extensions" },
      ],
    },
  ];
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function resolveMarkdownAsset(env: Record<string, unknown>, src: string): string {
  const markdownPath = String(env.path || env.relativePath || "");
  const markdownFile = path.isAbsolute(markdownPath)
    ? markdownPath
    : path.resolve(docsRoot, markdownPath);
  return path.resolve(path.dirname(markdownFile), src);
}

function drawioMarkdownPlugin(md: any) {
  const defaultImage = md.renderer.rules.image;
  const defaultParagraphOpen = md.renderer.rules.paragraph_open;
  const defaultParagraphClose = md.renderer.rules.paragraph_close;

  function isStandaloneDrawioParagraph(tokens: any[], idx: number): boolean {
    const inline = tokens[idx + 1];
    const child = inline?.children?.[0];
    const src = child?.attrGet?.("src") || "";
    return (
      inline?.type === "inline" &&
      inline.children?.length === 1 &&
      child?.type === "image" &&
      src.toLowerCase().split("#", 1)[0].endsWith(".drawio")
    );
  }

  md.renderer.rules.paragraph_open = (tokens: any[], idx: number, options: unknown, env: unknown, self: any) => {
    if (isStandaloneDrawioParagraph(tokens, idx)) return "";
    return defaultParagraphOpen ? defaultParagraphOpen(tokens, idx, options, env, self) : self.renderToken(tokens, idx, options);
  };

  md.renderer.rules.paragraph_close = (tokens: any[], idx: number, options: unknown, env: unknown, self: any) => {
    if (idx >= 2 && isStandaloneDrawioParagraph(tokens, idx - 2)) return "";
    return defaultParagraphClose ? defaultParagraphClose(tokens, idx, options, env, self) : self.renderToken(tokens, idx, options);
  };

  function tableClassFor(tokens: any[], tableIndex: number): string {
    const headers: string[] = [];
    let inHead = false;
    for (let i = tableIndex + 1; i < tokens.length; i += 1) {
      const token = tokens[i];
      if (token.type === "table_close") break;
      if (token.type === "thead_open") inHead = true;
      if (token.type === "thead_close") break;
      if (inHead && token.type === "inline" && tokens[i - 1]?.type === "th_open") {
        headers.push(token.content.trim().toLowerCase());
      }
    }

    const signature = headers.join("|");
    if (signature === "setting|default|range|type") return "aosc-table aosc-table-settings";
    if (signature === "field|type|required|description") return "aosc-table aosc-table-request";
    return "aosc-table";
  }

  md.renderer.rules.table_open = (tokens: any[], idx: number) => {
    return `<div class="aosc-table-scroll"><table class="${escapeHtml(tableClassFor(tokens, idx))}">`;
  };

  md.renderer.rules.table_close = () => {
    return "</table></div>";
  };

  md.renderer.rules.image = (tokens: any[], idx: number, options: unknown, env: Record<string, unknown>, self: unknown) => {
    const token = tokens[idx];
    const src = token.attrGet("src") || "";
    const [fileRef, pageRef] = src.split("#", 2);

    if (!fileRef.toLowerCase().endsWith(".drawio")) {
      return defaultImage ? defaultImage(tokens, idx, options, env, self) : md.renderer.renderToken(tokens, idx, options);
    }

    const filePath = resolveMarkdownAsset(env, decodeURIComponent(fileRef));
    const xml = fs.readFileSync(filePath, "utf8");
    const title = token.attrGet("title") || token.content || path.basename(fileRef);
    const page = pageRef ? ` data-drawio-page="${escapeHtml(decodeURIComponent(pageRef))}"` : "";
    const xmlBase64 = Buffer.from(xml, "utf8").toString("base64");

    return `<figure class="drawio-figure drawio-export" data-drawio-xml="${xmlBase64}" data-drawio-title="${escapeHtml(title)}"${page}><div class="drawio-loading">Rendering diagram...</div><img alt="${escapeHtml(title)}" loading="lazy" hidden><figcaption>${escapeHtml(title)}</figcaption></figure>`;
  };

  const defaultFence = md.renderer.rules.fence;
  md.renderer.rules.fence = (tokens: any[], idx: number, options: unknown, env: Record<string, unknown>, self: unknown) => {
    const token = tokens[idx];
    const info = token.info.trim().split(/\s+/)[0];
    if (info === "mermaid") {
      return `<pre class="mermaid">${escapeHtml(token.content)}</pre>`;
    }
    return defaultFence(tokens, idx, options, env, self);
  };
}

export default defineConfig({
  title: "AOSC",
  description: "Automatic Online Schema Change for OpenSearch",
  base: docsBase,
  cleanUrls: true,
  lastUpdated: true,
  ignoreDeadLinks: "localhostLinks",
  markdown: {
    math: true,
    config(md) {
      drawioMarkdownPlugin(md);
    },
  },
  head: [
    ["meta", { name: "theme-color", content: "#2451d6" }],
    ["link", { rel: "icon", href: "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E%3Crect width='64' height='64' rx='14' fill='%232451d6'/%3E%3Cpath d='M17 41V23h7l8 10 8-10h7v18h-7V33l-6 8h-4l-6-8v8z' fill='white'/%3E%3C/svg%3E" }],
  ],
  themeConfig: {
    siteTitle: "AOSC",
    nav: [
      { text: "Home", link: "/" },
      { text: "Get Started", link: "/get-started/what-is-aosc" },
      { text: "How It Works", link: "/how-it-works" },
      { text: "Reference", link: "/reference/rest-api" },
      {
        text: "Version",
        items: [
          { text: "Develop", link: "#aosc-docs-version-develop" },
        ],
      },
    ],
    sidebar: sidebar(),
    socialLinks: [{ icon: "github", link: "https://github.com/atlassian-labs/opensearch-aosc" }],
    search: {
      provider: "local",
    },
    editLink: {
      pattern: "https://github.com/atlassian-labs/opensearch-aosc/edit/develop/docs/:path",
      text: "Edit this page on GitHub",
    },
    footer: {
      message: "Released under the Apache 2.0 License.",
      copyright: "Copyright © Atlassian",
    },
  },
});
