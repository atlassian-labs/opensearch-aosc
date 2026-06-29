# Compatibility

AOSC has two version axes:

- **AOSC version**: the plugin release version, such as `0.1.0`.
- **OpenSearch line**: the OpenSearch major line the artifact targets, such as `os3`.

Release artifacts are built per supported OpenSearch minor because OpenSearch plugins are loaded with version compatibility checks. Documentation is versioned by exact AOSC patch version plus OpenSearch major line, for example `/0.1.0-os3/`.

## Current OpenSearch 3.x Line

| Field | Value |
| --- | --- |
| Release branch | `releases/3.x` |
| AOSC version | `0.1.0` |
| Documentation version | `0.1.0-os3` |
| Primary OpenSearch version | `3.6.0` |
| Release ZIP minors | `3.1`, `3.3`, `3.5`, `3.6` |
| CI test versions | `3.1.0`, `3.3.0`, `3.5.0`, `3.6.0` |
| Java version | `21` |

The ZIP name includes the OpenSearch minor it was built for:

```text
opensearch-aosc-0.1.0-opensearch-3.6.zip
```

Use the ZIP matching your OpenSearch minor. A `3.6` ZIP is intended for the `3.6.x` patch line unless a release note says otherwise.

## Compatibility Policy

Patch-level compatibility is validated by CI for the exact OpenSearch versions listed above. Other patch releases in the same OpenSearch minor may work because the plugin descriptor uses a patch-compatible semver range, but test the exact OpenSearch version before production use.

Do not install an AOSC ZIP built for a different OpenSearch minor unless the release notes explicitly say it is supported.
