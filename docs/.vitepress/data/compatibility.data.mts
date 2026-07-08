/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Build-time data loader: the supported-OpenSearch-version tables in the docs are generated from
 * release/os{2,3}.properties (the single source of truth) instead of being hand-maintained in
 * markdown. Because loaders run at docs-build time, each published doc version freezes the exact
 * versions that release supported.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const releaseDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../../release");

function loadProps(file: string): Record<string, string> {
  const props: Record<string, string> = {};
  for (const raw of fs.readFileSync(path.join(releaseDir, file), "utf8").split("\n")) {
    const line = raw.trim();
    if (!line || line.startsWith("#") || !line.includes("=")) continue;
    const idx = line.indexOf("=");
    props[line.slice(0, idx).trim()] = line.slice(idx + 1).trim();
  }
  return props;
}

function csv(value: string | undefined): string[] {
  return (value ?? "").split(",").map((s) => s.trim()).filter(Boolean);
}

function minorsOf(versions: string[]): string[] {
  const out: string[] = [];
  for (const v of versions) {
    const minor = v.split(".").slice(0, 2).join(".");
    if (!out.includes(minor)) out.push(minor);
  }
  return out;
}

function gradleSummary(p: Record<string, string>): string {
  const overrides = Object.keys(p)
    .filter((k) => k.startsWith("gradle_version."))
    .map((k) => `${k.slice("gradle_version.".length)} → ${p[k]}`);
  const base = p.gradle_version ?? "";
  return overrides.length ? `${base} (${overrides.join(", ")})` : base;
}

function lineData(file: string, display: string) {
  const p = loadProps(file);
  return {
    line: p.line,
    display,
    javaVersion: p.target_java_version,
    gradleVersion: gradleSummary(p),
    primaryVersion: p.primary_version,
    shippedMinors: minorsOf(csv(p.build_versions)),
    validatedVersions: csv(p.test_versions),
  };
}

export interface CompatibilityData {
  lines: ReturnType<typeof lineData>[];
}

declare const data: CompatibilityData;
export { data };

export default {
  watch: [releaseDir + "/os*.properties"],
  load(): CompatibilityData {
    return { lines: [lineData("os2.properties", "2.x"), lineData("os3.properties", "3.x")] };
  },
};
