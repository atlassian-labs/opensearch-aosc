# Compatibility

AOSC has two version axes:

- **AOSC version**: the plugin release version, such as `0.1.0`.
- **OpenSearch line**: the OpenSearch major line the artifact targets, such as `os2`.

Release artifacts are built per supported OpenSearch minor because OpenSearch plugins are loaded with version compatibility checks. Documentation is versioned by AOSC minor line plus OpenSearch major line, for example `/0.1-os2/`.

## Current OpenSearch 2.x Line

| Field | Value |
| --- | --- |
| Release branch | `releases/2.x` |
| AOSC release line | `0.1` |
| Documentation version | `0.1-os2` |
| Primary OpenSearch version | `2.19.0` |
| Release ZIP minors | `2.15`, `2.17`, `2.19` |
| CI test versions | `2.15.0`, `2.17.0`, `2.17.1`, `2.19.0`, `2.19.3` |

The ZIP name includes the OpenSearch minor it was built for:

```text
opensearch-aosc-0.1.0-opensearch-2.19.zip
```

Use the ZIP matching your OpenSearch minor. A `2.19` ZIP is intended for the `2.19.x` patch line unless a release note says otherwise.

## Compatibility Policy

Patch-level compatibility is validated by CI for the exact OpenSearch versions listed above. Other patch releases in the same OpenSearch minor may work because the plugin descriptor uses a patch-compatible semver range, but test the exact OpenSearch version before production use.

Do not install an AOSC ZIP built for a different OpenSearch minor unless the release notes explicitly say it is supported.
