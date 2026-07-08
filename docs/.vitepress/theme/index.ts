/*
 * SPDX-License-Identifier: Apache-2.0
 */
import DefaultTheme from "vitepress/theme";
import type { Theme } from "vitepress";
import "./styles.css";
import "../../stylesheets/state-machine.css";

declare global {
  interface Window {
    __aoscMermaid?: {
      initialize: (config: Record<string, unknown>) => void;
      run: (config?: Record<string, unknown>) => Promise<void>;
    };
    __aoscDrawioExporter?: DrawioExporter;
    initStateMachineViz?: () => void;
  }
}

type DrawioJob = {
  figure: HTMLElement;
  xml: string;
  page?: string;
};

type DrawioExporter = {
  enqueue: (job: DrawioJob) => void;
};

type DocsVersion = {
  version: string;
  title?: string;
};

let docsVersionsPromise: Promise<DocsVersion[]> | null = null;

function docsVersionRoot(): string {
  const base = import.meta.env.BASE_URL || "/";
  const normalized = base.endsWith("/") ? base : `${base}/`;
  const parts = normalized.split("/").filter(Boolean);
  if (parts.length < 2) return normalized;
  return `/${parts.slice(0, -1).join("/")}/`;
}

function currentDocsVersion(): string {
  const base = import.meta.env.BASE_URL || "/";
  const normalized = base.endsWith("/") ? base : `${base}/`;
  const parts = normalized.split("/").filter(Boolean);
  return parts.at(-1) || "develop";
}

async function docsVersions(): Promise<DocsVersion[]> {
  if (docsVersionsPromise) return docsVersionsPromise;
  const versionRoot = docsVersionRoot();
  const base = import.meta.env.BASE_URL || "/";
  const normalizedBase = base.endsWith("/") ? base : `${base}/`;
  const candidates = Array.from(new Set([`${versionRoot}versions.json`, `${normalizedBase}versions.json`]));

  docsVersionsPromise = (async () => {
    let lastError: unknown;
    for (const url of candidates) {
      try {
        const response = await fetch(url, { cache: "no-store" });
        if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`);
        const versions = await response.json();
        if (!Array.isArray(versions)) throw new Error(`${url}: versions.json must be an array`);
        return versions
          .filter((entry): entry is DocsVersion => typeof entry?.version === "string")
          .map((entry) => ({ version: entry.version, title: entry.title || entry.version }));
      } catch (error) {
        lastError = error;
      }
    }
    throw lastError || new Error("No versions.json candidates configured");
  })()
    .catch((error) => {
      console.warn("[aosc-docs] Failed to load versions.json", error);
      return [{ version: "develop", title: "Develop" }];
    });
  return docsVersionsPromise;
}

function versionMenuLinks(): HTMLAnchorElement[] {
  return Array.from(document.querySelectorAll<HTMLAnchorElement>('a[href$="#aosc-docs-version-develop"]'));
}

function versionMenus(): HTMLElement[] {
  const menus = new Set<HTMLElement>();
  versionMenuLinks().forEach((link) => {
    const menu = link.closest<HTMLElement>(".VPMenu");
    if (menu) menus.add(menu);
  });
  return Array.from(menus);
}

function versionLink(versionRoot: string, version: string): string {
  return `${window.location.origin}${versionRoot}${version}/`;
}

function versionTitle(version: DocsVersion): string {
  if (version.version === "develop") return "Develop";
  const match = version.version.match(/^(.*)-os(\d+)$/);
  if (!match) return version.title || version.version;
  return match[1];
}

function versionSubtitle(version: DocsVersion): string {
  if (version.version === "develop") return "Unreleased docs";
  // Legacy per-line docs versions (e.g. 0.1.0-os2) — kept for backward compatibility.
  const match = version.version.match(/^(.*)-os(\d+)$/);
  if (match) return `OpenSearch ${match[2]}.x`;
  // New model: one release covers both lines; avoid repeating the version as its own subtitle.
  return "Release";
}

function forceDocumentNavigation(link: HTMLAnchorElement) {
  if (link.dataset.aoscVersionNavigationBound === "true") return;
  link.dataset.aoscVersionNavigationBound = "true";
  link.target = "_self";
  link.addEventListener("click", (event) => {
    event.preventDefault();
    event.stopImmediatePropagation();
    event.stopPropagation();
    window.location.assign(link.href);
  }, { capture: true });
}

function renderVersionMenu(menu: HTMLElement, versions: DocsVersion[]) {
  const versionRoot = docsVersionRoot();
  const activeVersion = currentDocsVersion();
  const target = menu.querySelector<HTMLElement>(".items") || menu;

  menu.classList.add("aosc-version-menu");
  target.textContent = "";
  versions.forEach((entry) => {
    const item = document.createElement("div");
    item.className = "VPMenuLink";

    const link = document.createElement("a");
    link.className = entry.version === activeVersion ? "link active aosc-version-link" : "link aosc-version-link";
    link.href = versionLink(versionRoot, entry.version);

    const title = document.createElement("span");
    title.className = "aosc-version-title";
    title.textContent = versionTitle(entry);

    const subtitle = document.createElement("span");
    subtitle.className = "aosc-version-subtitle";
    subtitle.textContent = versionSubtitle(entry);

    link.append(title, subtitle);
    forceDocumentNavigation(link);

    item.appendChild(link);
    target.appendChild(item);
  });
}

function bindVersionMenu() {
  if (typeof document === "undefined") return;
  const menus = versionMenus();
  if (menus.length === 0) return;
  const versionRoot = docsVersionRoot();

  versionMenuLinks().forEach((link) => {
    link.href = versionLink(versionRoot, "develop");
    forceDocumentNavigation(link);
  });

  docsVersions()
    .then((versions) => {
      menus.forEach((menu) => renderVersionMenu(menu, versions));
    })
    .catch((error) => console.warn("[aosc-docs] Version menu render failed", error));
}

function decodeBase64Utf8(value: string): string {
  const bytes = Uint8Array.from(atob(value), (char) => char.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

function createDrawioExporter(): DrawioExporter {
  const iframe = document.createElement("iframe");
  iframe.src = "https://embed.diagrams.net/?embed=1&ui=atlas&spin=1&proto=json";
  iframe.title = "draw.io exporter";
  iframe.className = "drawio-exporter-frame";
  iframe.setAttribute("aria-hidden", "true");
  document.body.appendChild(iframe);

  const queue: DrawioJob[] = [];
  let ready = false;
  let active: DrawioJob | null = null;

  const send = (message: Record<string, unknown>) => {
    iframe.contentWindow?.postMessage(JSON.stringify(message), "https://embed.diagrams.net");
  };

  const startNext = () => {
    if (!ready || active || queue.length === 0) return;
    active = queue.shift() || null;
    if (!active) return;

    const loadMessage: Record<string, unknown> = {
      action: "load",
      xml: active.xml,
      autosave: 0,
      modified: false,
    };
    if (active.page) {
      loadMessage.page = active.page;
    }
    send(loadMessage);
  };

  const failActive = (message: string) => {
    if (!active) return;
    active.figure.classList.add("drawio-error");
    active.figure.querySelector(".drawio-loading")!.textContent = message;
    active = null;
    startNext();
  };

  window.addEventListener("message", (event) => {
    if (event.source !== iframe.contentWindow || event.origin !== "https://embed.diagrams.net" || typeof event.data !== "string") return;
    let message: { event?: string; data?: string } = {};
    try {
      message = JSON.parse(event.data);
    } catch {
      return;
    }

    if (message.event === "init") {
      ready = true;
      startNext();
      return;
    }

    if (!active) return;

    if (message.event === "load") {
      send({ action: "export", format: "svg", spinKey: "export" });
      return;
    }

    if (message.event === "export") {
      if (!message.data) {
        failActive("Diagram export returned no data.");
        return;
      }
      const image = active.figure.querySelector<HTMLImageElement>("img");
      const loading = active.figure.querySelector<HTMLElement>(".drawio-loading");
      if (!image || !loading) {
        failActive("Diagram export target is missing.");
        return;
      }
      image.src = message.data;
      image.hidden = false;
      loading.hidden = true;
      active.figure.classList.add("drawio-rendered");
      active = null;
      startNext();
    }
  });

  return {
    enqueue(job) {
      queue.push(job);
      startNext();
    },
  };
}

function bindDrawioExports() {
  if (typeof window === "undefined") return;
  const figures = Array.from(document.querySelectorAll<HTMLElement>(".drawio-export:not([data-aosc-bound])"));
  if (figures.length === 0) return;

  window.__aoscDrawioExporter = window.__aoscDrawioExporter || createDrawioExporter();
  figures.forEach((figure) => {
    figure.dataset.aoscBound = "true";
    const xmlBase64 = figure.dataset.drawioXml;
    if (!xmlBase64) return;
    window.__aoscDrawioExporter!.enqueue({
      figure,
      xml: decodeBase64Utf8(xmlBase64),
      page: figure.dataset.drawioPage,
    });
  });
}

function bindImageZoom() {
  if (typeof document === "undefined") return;
  document.querySelectorAll<HTMLImageElement>(".drawio-export img:not([data-aosc-zoom-bound])").forEach((image) => {
    image.dataset.aoscZoomBound = "true";
    image.addEventListener("click", () => {
      if (!image.src) return;

      const overlay = document.createElement("div");
      overlay.className = "aosc-image-zoom";
      overlay.innerHTML =
        '<button type="button" class="aosc-image-zoom-close" aria-label="Close diagram zoom"><svg class="aosc-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M18 6 6 18"/><path d="M6 6l12 12"/></svg></button>';

      const zoomedImage = document.createElement("img");
      zoomedImage.src = image.src;
      zoomedImage.alt = image.alt;
      overlay.appendChild(zoomedImage);

      const close = () => {
        overlay.remove();
        document.body.classList.remove("aosc-image-zoom-active");
      };

      overlay.addEventListener("click", (event) => {
        if (event.target === overlay) close();
      });
      overlay.querySelector("button")?.addEventListener("click", close);

      const onKeydown = (event: KeyboardEvent) => {
        if (event.key === "Escape") {
          close();
          document.removeEventListener("keydown", onKeydown);
        }
      };
      document.addEventListener("keydown", onKeydown);

      document.body.classList.add("aosc-image-zoom-active");
      document.body.appendChild(overlay);
    });
  });
}

async function renderMermaid() {
  if (typeof document === "undefined") return;
  const nodes = Array.from(
    document.querySelectorAll<HTMLElement>('.mermaid:not([data-processed="true"]):not([data-aosc-rendering="true"])'),
  );
  if (nodes.length === 0) return;

  nodes.forEach((node) => node.setAttribute("data-aosc-rendering", "true"));
  if (!window.__aoscMermaid) {
    const mermaid = await import("mermaid");
    const dark = document.documentElement.classList.contains("dark");
    window.__aoscMermaid = mermaid.default;
    window.__aoscMermaid.initialize({
      startOnLoad: false,
      securityLevel: "strict",
      theme: "base",
      themeVariables: {
        fontFamily: "var(--vp-font-family-base)",
        primaryColor: dark ? "#20242c" : "#f7f8fb",
        primaryTextColor: dark ? "#f5f7fb" : "#24272e",
        primaryBorderColor: dark ? "#3a404b" : "#d9dde6",
        lineColor: dark ? "#8892a6" : "#737b8c",
        secondaryColor: dark ? "#172033" : "#eef3ff",
        tertiaryColor: dark ? "#171b22" : "#ffffff",
        background: dark ? "#1b1b1f" : "#ffffff",
        mainBkg: dark ? "#20242c" : "#f7f8fb",
        secondBkg: dark ? "#172033" : "#eef3ff",
        nodeBorder: dark ? "#3a404b" : "#d9dde6",
        clusterBkg: dark ? "#171b22" : "#ffffff",
        clusterBorder: dark ? "#3a404b" : "#d9dde6",
        edgeLabelBackground: dark ? "#1b1b1f" : "#ffffff",
        labelTextColor: dark ? "#f5f7fb" : "#24272e",
      },
    });
  }
  try {
    await window.__aoscMermaid.run({ querySelector: '.mermaid[data-aosc-rendering="true"]' });
  } finally {
    nodes.forEach((node) => node.removeAttribute("data-aosc-rendering"));
  }
}

function enhanceCurrentPage() {
  bindVersionMenu();
  bindDrawioExports();
  bindImageZoom();
  window.initStateMachineViz?.();
  renderMermaid().catch((error) => console.warn("[aosc-docs] Mermaid render failed", error));
}

function scheduleEnhanceCurrentPage() {
  requestAnimationFrame(() => {
    requestAnimationFrame(enhanceCurrentPage);
  });
}

let pageObserverStarted = false;

function observePageEnhancements() {
  if (pageObserverStarted) return;
  pageObserverStarted = true;
  const root = document.querySelector("#app") || document.body;
  new MutationObserver(scheduleEnhanceCurrentPage).observe(root, {
    childList: true,
    subtree: true,
  });
}

const theme: Theme = {
  extends: DefaultTheme,
  enhanceApp(ctx) {
    DefaultTheme.enhanceApp?.(ctx);
    if (typeof window === "undefined") return;
    observePageEnhancements();
    scheduleEnhanceCurrentPage();
    import("../../javascripts/state-machine-viz.js")
      .then(scheduleEnhanceCurrentPage)
      .catch((error) => console.warn("[aosc-docs] State machine widget failed to load", error));
    ctx.router.onAfterRouteChanged = () => {
      scheduleEnhanceCurrentPage();
    };
  },
};

export default theme;
