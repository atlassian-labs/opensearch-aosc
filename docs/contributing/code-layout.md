# Code Layout

AOSC source lives under `aosc-plugin/src/main/java/com/atlassian/opensearch/aosc/`.

## Top-Level Directories

| Directory | Purpose |
|-----------|---------|
| `aosc-plugin/src/main/java/` | Plugin source. |
| `aosc-plugin/src/test/` | Unit tests. |
| `aosc-plugin/src/itTest/` | In-JVM integration tests. |
| `aosc-plugin/src/smokeTest/` | REST smoke tests. |
| `aosc-plugin/src/scaleTest/` | Scale validation tests. |
| `aosc-plugin/src/benchmarkTest/` | Benchmark tests. |
| `aosc-plugin/src/yamlRestTest/` | YAML REST tests. |
| `aosc-plugin/opensearch-docker/` | Local Docker cluster. |
| `docs/` | VitePress documentation. |

## Main Packages

| Package | Responsibility |
|---------|----------------|
| `action.*` | Transport actions and request/response types. |
| `rest.*` | REST handlers that parse HTTP requests and call transport actions. |
| `model.*` | Migration documents, options, phases, routing mode, and DTOs. |
| `service.coordinator.*` | Cluster-manager-side orchestration and cutover. |
| `service.worker.*` | Data-node shard workers, backfill, replay, retention leases. |
| `service.bulk.*` | Bulk writer pipeline and write controllers. |
| `service.adaptive.*` | AIMD and adaptive control helpers. |
| `transform.*` | Identity and update-script transform functions. |
| `statemachine.*` | Async state-machine framework. |
| `utils.*` | Logging, JSON, async, and OpenSearch helper utilities. |

## Key Classes

| Class | Why it matters |
|-------|----------------|
| `AoscPlugin` | Plugin entry point and component registration. |
| `RestStartMigrationAction` | `POST /_plugins/_aosc/{index}/_start`. |
| `TransportStartMigrationAction` | Start validation and coordinator handoff. |
| `AoscCoordinatorService` | Manages active coordinators on the cluster-manager node. |
| `MigrationCoordinator` | Coordinator phase handlers and cutover orchestration. |
| `AoscShardService` | Creates and manages shard workers on data nodes. |
| `ShardMigrationWorker` | Worker state machine. |
| `BackfillEngine` | Reads source documents and emits target index requests. |
| `TranslogReplayEngine` | Replays source operation history into the target. |
| `TransformFactory` | Builds transform functions from `transform_script`. |
| `AoscSettings` | Cluster setting definitions and defaults. |

## Where Changes Usually Go

| Change | Likely files |
|--------|--------------|
| New REST endpoint | `rest/`, `action/`, REST specs/tests. |
| New start option | `MigrationRequestOptions`, validators, docs, tests. |
| Coordinator phase behavior | `MigrationCoordinator`, `CoordinatorPhase`, state-machine tests. |
| Worker phase behavior | `ShardMigrationWorker`, `ShardPhase`, worker tests. |
| Backfill or replay behavior | `BackfillEngine`, `TranslogReplayEngine`, bulk tests. |
| Transform behavior | `transform/`, `TransformFactory`, transform tests. |
| New setting | `AoscSettings`, configuration docs, tests. |

## Style Notes

- Use `AoscLogger` for plugin logging.
- Keep public wire fields stable and documented.
- Prefer existing async `ActionListener` and `CompletableFuture` bridging patterns.
- Run Spotless before sending a pull request.
