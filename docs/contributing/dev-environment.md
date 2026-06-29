# Development Environment Setup

For the public contribution contract, use the repository root [`CONTRIBUTING.md`](https://github.com/atlassian-labs/opensearch-aosc/blob/develop/CONTRIBUTING.md). It covers issues, pull requests, commit-message conventions, code formatting, and license headers.

The contributing pages in this documentation are source-tree workflow references. They cover local setup, test selection, package layout, release mechanics, and extension points for people changing AOSC code.

## Prerequisites

| Tool | Minimum | Notes |
|------|---------|-------|
| JDK | 21 | OpenSearch 3.x plugin builds target Java 21 bytecode. |
| Git | 2.x | Source control. |
| Docker | Recent Docker Desktop or Docker Engine | Needed for Docker-backed smoke tests and local clusters. |
| Docker Compose | v2 plugin or v1 standalone | The Gradle task detects `docker compose` first, then `docker-compose`. |
| Gradle | Wrapper only | Use `./gradlew`; do not rely on a global Gradle install. |

## Build

The root build requires an OpenSearch version.

```bash
git clone https://github.com/atlassian-labs/opensearch-aosc.git
cd opensearch-aosc
./gradlew :aosc-plugin:assemble -Dopensearch.version=3.6.0
```

The plugin ZIP is written to `aosc-plugin/build/distributions/`.

## Environment Variables

```bash
export OPENSEARCH_INITIAL_ADMIN_PASSWORD=Admin@123
```

The OpenSearch version can be supplied per command:

```bash
./gradlew :aosc-plugin:fastCheck -Dopensearch.version=3.6.0
```

## IntelliJ IDEA

1. Open the repository root.
2. Use JDK 21 for Gradle import, with project language level 21.
3. Enable annotation processing for Lombok.
4. Import the formatter profile from `gradle/formatterConfig.xml` if you want IDE formatting to match Spotless.
5. Add Gradle run configurations with `OPENSEARCH_VERSION=3.6.0` or `-Dopensearch.version=3.6.0`.

## Local Docker Cluster

From the repository root:

```bash
export OPENSEARCH_INITIAL_ADMIN_PASSWORD=Admin@123
./gradlew :aosc-plugin:dockerUp -Dopensearch.version=3.6.0
curl -s http://localhost:9200/_cluster/health | jq '.'
./gradlew :aosc-plugin:dockerDown -Dopensearch.version=3.6.0
```

The compose files live in `aosc-plugin/opensearch-docker/`. Prefer the Gradle tasks above because they build the plugin ZIP, copy it into the Docker context, select Compose v1 or v2, wait for health, and remove volumes on shutdown.

If you debug Compose directly, first run `./gradlew :aosc-plugin:dockerCopyPlugin -Dopensearch.version=3.6.0`, then run Compose from `aosc-plugin/opensearch-docker/` with `OPENSEARCH_VERSION` and `OPENSEARCH_INITIAL_ADMIN_PASSWORD` set.

## Common Setup Issues

| Problem | Fix |
|---------|-----|
| `opensearch.version is required` | Export `OPENSEARCH_VERSION` or pass `-Dopensearch.version`. |
| `java: command not found` | Install a JDK and make it available on `PATH`. |
| Docker connection errors | Start Docker Desktop or Docker Engine. |
| Spotless violations | Run `./gradlew :aosc-plugin:spotlessApply -Dopensearch.version=3.6.0`. |
| Lombok symbols missing in IDE | Enable annotation processing and reload Gradle. |

## Repository Layout

```text
opensearch-aosc/
|-- aosc-plugin/
|   |-- src/main/java/          # Plugin source
|   |-- src/test/               # Unit tests
|   |-- src/itTest/             # In-JVM integration tests
|   |-- src/smokeTest/          # REST smoke tests
|   |-- src/scaleTest/          # Scale validation tests
|   |-- src/benchmarkTest/      # Benchmark tests
|   |-- src/yamlRestTest/       # YAML REST tests
|   `-- opensearch-docker/      # Local Docker cluster
|-- docs/                       # VitePress documentation
|-- gradle/                     # Wrapper and formatter config
`-- scripts/                    # Public helper scripts
```
