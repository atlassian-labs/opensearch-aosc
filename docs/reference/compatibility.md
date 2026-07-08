<script setup>
import { data } from "../.vitepress/data/compatibility.data.mts";
</script>

# Compatibility

AOSC has two version axes:

- **AOSC version**: the plugin release version, such as `0.1.0`. A single version supports both OpenSearch lines.
- **OpenSearch line**: the OpenSearch major line an individual artifact targets, such as `os2` or `os3`.

`develop` builds both lines, and each AOSC version is released once (tagged `v<version>`) with a ZIP per supported OpenSearch minor across both lines, because OpenSearch plugins are loaded with version compatibility checks. Documentation is versioned by AOSC version only, for example `/0.1.0/`.

The tables below are generated at docs-build time from `release/os2.properties` and `release/os3.properties` (the single source of truth), so they always match what this version of AOSC actually builds and validates.

<div v-for="line in data.lines" :key="line.line">

<h2>OpenSearch {{ line.display }} line</h2>

<table>
  <tbody>
    <tr><td>Primary OpenSearch version</td><td><code>{{ line.primaryVersion }}</code></td></tr>
    <tr><td>Shipped minors (one ZIP each)</td><td>{{ line.shippedMinors.join(", ") }}</td></tr>
    <tr><td>Validated versions (CI)</td><td>{{ line.validatedVersions.join(", ") }}</td></tr>
    <tr><td>Java version</td><td>{{ line.javaVersion }}</td></tr>
    <tr><td>Gradle version</td><td>{{ line.gradleVersion }}</td></tr>
  </tbody>
</table>

</div>

The ZIP name includes the OpenSearch minor it was built for:

```text
opensearch-aosc-0.1.0-os3.6.zip
opensearch-aosc-0.1.0-os2.19.zip
```

Use the ZIP matching your OpenSearch minor. A `3.6` ZIP is intended for the `3.6.x` patch line, and a `2.19` ZIP is intended for the `2.19.x` patch line, unless a release note says otherwise.

## Compatibility Policy

Patch-level compatibility is validated by CI for the exact OpenSearch versions listed above. Other patch releases in the same OpenSearch minor may work because the plugin descriptor uses a patch-compatible semver range, but test the exact OpenSearch version before production use.

Do not install an AOSC ZIP built for a different OpenSearch minor unless the release notes explicitly say it is supported.
