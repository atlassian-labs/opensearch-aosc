# AOSC: Automatic Online Schema Change for OpenSearch

AOSC is an OpenSearch plugin for moving an index to a pre-created target index while the source index continues to receive traffic. It backfills existing documents, replays source-shard operation history, and performs an alias cutover after a brief source write block.

This is an Atlassian Labs project. Maintenance and issue triage are best effort, with no SLA or roadmap commitment.

Public repository history starts from the OSS import. Earlier internal development history is not part of this repository.

Documentation is published at <https://atlassian-labs.github.io/opensearch-aosc/>.

For a visual walkthrough of how writes, shard workers, and alias cutover behave during a migration, start with <https://atlassian-labs.github.io/opensearch-aosc/develop/how-it-works>.

## Supported OpenSearch Versions

The build currently declares support for OpenSearch `2.15.0`, `2.17.0`, and `2.19.0`. Build the plugin with the exact target version:

```bash
./gradlew :aosc-plugin:assemble -Dopensearch.version=2.19.0
```

The generated plugin descriptor uses a patch-compatible semver range for the selected minor version. See [Install the Plugin](docs/how-to/install-the-plugin.md) for details.

The AOSC release version is tracked in `version.properties`. Release tags include both the AOSC version and the OpenSearch major compatibility line, for example `aosc-0.1.0-os2`. See [Releases](docs/contributing/releases.md).

## Quick Start

For a copy-paste local walkthrough, use [Your First Migration](docs/get-started/your-first-migration.md). It creates the source index, target index, alias, and a small continuous writer before starting AOSC.

For an existing cluster, AOSC expects the target index to already exist with the desired mappings, shard count, and settings. The API call below shows the request shape; replace the index and alias names with resources that already exist in your cluster:

```bash
curl -X POST 'http://localhost:9200/_plugins/_aosc/my-index-v1/_start' \
  -H 'Content-Type: application/json' \
  -d '{
    "target_index": "my-index-v2",
    "alias": "my-index"
  }'
```

Monitor or cancel the migration:

```bash
curl -s 'http://localhost:9200/_plugins/_aosc/my-index-v1/_status' | jq '.'
curl -X POST 'http://localhost:9200/_plugins/_aosc/my-index-v1/_cancel'
```

See [REST API Reference](docs/reference/rest-api.md) for request and response fields.

## How It Works

AOSC uses a distributed worker model:

| Component | Responsibility |
|-----------|----------------|
| Coordinator | Runs on the cluster-manager node and advances the migration phase. |
| Shard workers | Run on data nodes that hold source primaries. They backfill documents, replay operation history, and report progress. |
| Cluster state | Stores active migration phase and shard phase information. |
| `.aosc-migrations` | Stores migration records and detailed progress snapshots. |

The high-level migration path is:

1. Validate source, target, alias, plugin installation, and options.
2. Apply transient target settings such as zero replicas and disabled refresh.
3. Backfill source documents into the target.
4. Replay source operation history until each shard is close enough to cut over.
5. Write-block the source, replay the final operations, validate document counts, and swap the alias.
6. Remove the source write block when configured and persist terminal state.

For architecture details, see [Architecture Overview](docs/concepts/architecture-overview.md).

## Build and Test

The root build requires `OPENSEARCH_VERSION` or `-Dopensearch.version`.

```bash
export OPENSEARCH_VERSION=2.19.0

./gradlew :aosc-plugin:unitTest
./gradlew :aosc-plugin:fastCheck
./gradlew :aosc-plugin:yamlRestTest
./gradlew :aosc-plugin:itTest
```

Useful tasks:

| Task | Purpose |
|------|---------|
| `:aosc-plugin:unitTest` | Unit tests only. |
| `:aosc-plugin:fastCheck` | Unit tests plus compile/precommit checks wired by the OpenSearch build. |
| `:aosc-plugin:yamlRestTest` | YAML REST API smoke coverage. |
| `:aosc-plugin:itTest` | In-JVM OpenSearch integration tests. |
| `:aosc-plugin:smokeTest` | REST smoke tests against a forked or Docker-backed cluster, selected by `-Dcluster.topology`. |

See [Running Tests](docs/contributing/running-tests.md) for the test matrix.

## Local Docker Cluster

The Docker test cluster lives under `aosc-plugin/opensearch-docker`. Prefer the Gradle wrappers from the repository root:

```bash
export OPENSEARCH_INITIAL_ADMIN_PASSWORD=Admin@123

./gradlew :aosc-plugin:dockerUp -Dopensearch.version=2.19.0
curl -s http://localhost:9200/_cluster/health | jq '.'
./gradlew :aosc-plugin:dockerDown -Dopensearch.version=2.19.0
```

## Repository Layout

```text
opensearch-aosc/
|-- aosc-plugin/        # Plugin source, tests, packaging, and Docker test cluster
|-- docs/               # VitePress documentation
|-- gradle/             # Gradle wrapper and formatter config
|-- scripts/            # Public helper scripts
|-- build.gradle        # Root build configuration
`-- settings.gradle     # Gradle project layout
```

## Documentation

- [What Is AOSC?](docs/get-started/what-is-aosc.md) - concepts and vocabulary
- [Your First Migration](docs/get-started/your-first-migration.md) - copy-paste local walkthrough
- [Install the Plugin](docs/how-to/install-the-plugin.md) - build and install steps
- [Monitor a Migration](docs/operations/monitor-a-migration.md) - status and progress interpretation
- [REST API Reference](docs/reference/rest-api.md) - endpoints, request fields, and response shapes
- [Architecture Overview](docs/concepts/architecture-overview.md) - architecture and tradeoffs
- [Development Environment](docs/contributing/dev-environment.md) - local setup and contribution workflow
- [Releases](docs/contributing/releases.md) - versioning and release assets
- [Extension Points](docs/contributing/extensions.md) - public extension API notes

## License

Apache 2.0. See [LICENSE](LICENSE).

[![With ❤️ from Atlassian](https://raw.githubusercontent.com/atlassian-internal/oss-assets/master/banner-cheers-light.png)](https://www.atlassian.com)
