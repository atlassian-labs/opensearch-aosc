# Development Environment Setup

For the public contribution contract, use the repository root [`CONTRIBUTING.md`](https://github.com/atlassian-labs/opensearch-aosc/blob/develop/CONTRIBUTING.md). It covers issues, pull requests, commit-message conventions, code formatting, and license headers.

The contributing pages in this documentation are source-tree workflow references. They cover local setup, test selection, package layout, release mechanics, and extension points for people changing AOSC code.

## Prerequisites

| Tool | Minimum | Notes |
|------|---------|-------|
| JDK (build/Gradle JVM) | 21 for the `os3` line; 17 for the `os2` line | The JVM that runs the build (`build_java_version` in `release/os*.properties`). os2 emits Java 11 bytecode (`java_version`) but its build-tools requires JDK 17; os3 uses 21. Match the Gradle JVM to the line you build. |
| Git | 2.x | Source control. |
| Docker | Recent Docker Desktop or Docker Engine | Needed for Docker-backed smoke tests and local clusters. |
| Docker Compose | v2 plugin or v1 standalone | The Gradle task detects `docker compose` first, then `docker-compose`. |
| Gradle | Wrapper only | Use `./gradlew`; do not rely on a global Gradle install. |

## Build

The root build requires an OpenSearch version.

```bash
git clone https://github.com/atlassian-labs/opensearch-aosc.git
cd opensearch-aosc
./gradlew assemble -PopensearchVersion=3.6.0
```

The plugin ZIP is written under the selected line's `build/distributions/` (Gradle prints the exact path).

### Gradle version per OpenSearch version

OpenSearch 2.15–3.6 build on the committed wrapper (Gradle 8.7). **OpenSearch 3.7 requires Gradle 9.4.1** — its build-tools rejects older Gradle, and the 2.x build-tools breaks on Gradle 9, so no single wrapper serves both lines. Before working on 3.7:

```bash
./scripts/set-gradle.sh 3.7.0   # points the wrapper at Gradle 9.4.1 (drives ./gradlew and the IDE)
./scripts/set-gradle.sh --reset # restore the default (8.7) when you switch back
```

CI runs this per job automatically. Don't commit the flipped wrapper.

## Environment Variables

```bash
export OPENSEARCH_INITIAL_ADMIN_PASSWORD=Admin@123
```

Supply the OpenSearch version per command, or set a default once in `~/.gradle/gradle.properties` (`opensearchVersion=3.6.0`) so a bare `./gradlew` and IntelliJ import resolve it. The version selects the OpenSearch line (os2/os3):

```bash
./gradlew fastCheck -PopensearchVersion=3.6.0
```

## IntelliJ IDEA

1. Open the repository root.
2. Set the Gradle JVM to JDK 21 for the `os3` line or JDK 17 for the `os2` line (os2 emits Java 11 bytecode but the build needs JDK 17). Only one line imports per `opensearchVersion`, so switch the Gradle JVM when you switch lines. For 3.7 work, run `./scripts/set-gradle.sh 3.7.0` first (IntelliJ reads the wrapper's Gradle 9.4.1) and re-sync.
3. Enable annotation processing for Lombok.
4. Import the formatter profile from `gradle/formatterConfig.xml` if you want IDE formatting to match Spotless.
5. Set `opensearchVersion` in `~/.gradle/gradle.properties` (e.g. `3.6.0`) so import resolves the line; override per run configuration with `-PopensearchVersion`.

## Local Docker Cluster

From the repository root:

```bash
export OPENSEARCH_INITIAL_ADMIN_PASSWORD=Admin@123
./gradlew dockerUp -PopensearchVersion=3.6.0
curl -s http://localhost:9200/_cluster/health | jq '.'
./gradlew dockerDown -PopensearchVersion=3.6.0
```

The compose files live in the selected line's `opensearch-docker/` directory. Prefer the Gradle tasks above because they build the plugin ZIP, copy it into the Docker context, select Compose v1 or v2, wait for health, and remove volumes on shutdown.

If you debug Compose directly, first run `./gradlew dockerCopyPlugin -PopensearchVersion=3.6.0`, then run Compose from `aosc-plugin/opensearch-docker/` with `OPENSEARCH_VERSION` (the container image tag) and `OPENSEARCH_INITIAL_ADMIN_PASSWORD` set.

## Common Setup Issues

| Problem | Fix |
|---------|-----|
| `opensearchVersion is required` | Pass `-PopensearchVersion=<version>` or set it in `~/.gradle/gradle.properties`. |
| `java: command not found` | Install a JDK and make it available on `PATH`. |
| Docker connection errors | Start Docker Desktop or Docker Engine. |
| Spotless violations | Run `./gradlew spotlessApply -PopensearchVersion=3.6.0`. |
| Lombok symbols missing in IDE | Enable annotation processing and reload Gradle. |

## Repository Layout

A single `aosc-plugin/` module contains shared source and version-specific compat directories. Gradle selects `java-2x/` or `java-3x/` based on `-PopensearchVersion`.

```text
opensearch-aosc/
|-- aosc-plugin/
|   |-- src/main/java/          # Shared plugin source (both 2.x and 3.x)
|   |-- src/main/java-2x/       # OpenSearch 2.x-specific source
|   |-- src/main/java-3x/       # OpenSearch 3.x-specific source
|   |-- src/test/java/           # Shared unit tests
|   |-- src/test/java-2x/        # 2.x-specific tests
|   |-- src/test/java-3x/        # 3.x-specific tests
|   |-- src/itTest/              # In-JVM integration tests (shared)
|   |-- src/smokeTest/           # REST smoke tests
|   |-- src/scaleTest/           # Scale validation tests
|   |-- src/benchmarkTest/       # Benchmark tests
|   |-- src/yamlRestTest/        # YAML REST tests
|   `-- opensearch-docker/       # Local Docker cluster
|-- docs/                        # VitePress documentation
|-- gradle/                      # Wrapper, formatter config, shared aosc-plugin.gradle
|-- release/                     # Per-line build manifests
`-- scripts/                     # Public helper scripts
```
