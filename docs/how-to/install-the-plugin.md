# Install the Plugin

Build AOSC for the OpenSearch version you run, then install the ZIP on every cluster node.

## Supported Versions

The build currently declares support for:

- OpenSearch `3.1.0`
- OpenSearch `3.3.0`
- OpenSearch `3.5.0`
- OpenSearch `3.6.0`

Patch releases within the same minor may be compatible because the plugin descriptor is rewritten to a `~X.Y.Z` semver range. Test the exact OpenSearch version before using it. See [Compatibility](../reference/compatibility.md) for the current build and test matrix.

## Build from Source

```bash
git clone https://github.com/atlassian-labs/opensearch-aosc.git
cd opensearch-aosc
./gradlew :aosc-plugin:assemble -Dopensearch.version=3.6.0
```

Output:

```text
aosc-plugin/build/distributions/opensearch-aosc-<version>.zip
```

Repeat the build command with a different `-Dopensearch.version=...` when you need a ZIP for another supported OpenSearch minor.

## Install from GitHub Release

Published releases attach one ZIP per supported OpenSearch minor:

```text
opensearch-aosc-<aosc-version>-opensearch-<opensearch-minor>.zip
```

For example, an AOSC `0.1.0` release for OpenSearch `3.6.x` uses:

```text
opensearch-aosc-0.1.0-opensearch-3.6.zip
```

Download the ZIP matching your OpenSearch minor, verify it against `SHA256SUMS`, then install that ZIP on each node.

## Install on Each Node

Install the plugin on every cluster-manager and data node. Installing it on all nodes is the safest default because transport actions and services are registered by the plugin.

```bash
$OPENSEARCH_HOME/bin/opensearch-plugin install file:///path/to/opensearch-aosc-<version>.zip
```

Check node roles:

```bash
curl -s 'http://localhost:9200/_cat/nodes?v&h=name,node.role'
```

Restart nodes one at a time according to your OpenSearch operating procedure.

## Verify Installation

```bash
curl -s http://localhost:9200/_cat/plugins?v | grep opensearch-aosc
curl -s http://localhost:9200/_plugins/_aosc/_list | jq '.'
```

A cluster with no migrations returns an empty `migrations` array.

## Common Problems

| Symptom | Check |
|---------|-------|
| Plugin version mismatch | Run `_cat/plugins` on all nodes and reinstall the same ZIP everywhere. |
| `opensearch.version` build error | Pass `-Dopensearch.version=...` or export `OPENSEARCH_VERSION`. |
| Plugin does not load | Check OpenSearch logs for version mismatch, permissions, or dependency errors. |
| REST action missing | Confirm every relevant node has the plugin installed and was restarted. |
