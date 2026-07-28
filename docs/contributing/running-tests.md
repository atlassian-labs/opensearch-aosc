# Running Tests

Every command takes `-PopensearchVersion=<version>`, which selects the OpenSearch line (os2 vs os3). Set it per invocation or once in `~/.gradle/gradle.properties`. It is shown in the first block below and omitted afterwards for brevity.

## Test Matrix

Source paths are under `aosc-plugin/src/` (shared code in `java/`, version-specific in `java-2x/`/`java-3x/`).

| Task | Source | Purpose |
|------|--------|---------|
| `test` | `.../src/test/` | Unit tests. |
| `fastCheck` | unit tests plus build checks | Fast local verification. |
| `yamlRestTest` | `.../src/yamlRestTest/` | YAML REST API checks. |
| `itTest` | `.../src/itTest/` | In-JVM OpenSearch integration tests. |
| `smokeTest2Nodes` | `.../src/smokeTest/` | REST smoke tests against a 2-node test cluster. |
| `smokeTestDedicatedCM` | `.../src/smokeTest/` | Smoke tests with a dedicated cluster-manager node. |
| `smokeTestDocker` | `.../src/smokeTest/` | Smoke tests against the Docker cluster. |
| `scaleTest` | `.../src/scaleTest/` | Larger correctness and shard-layout validation. |
| `benchmark` | `.../src/benchmarkTest/` | Throughput and latency experiments. |

## Common Commands

The integration suite starts OpenSearch test clusters and can take several minutes on a developer laptop.

```bash
./gradlew fastCheck -PopensearchVersion=3.6.0
./gradlew yamlRestTest -PopensearchVersion=3.6.0
./gradlew itTest -PopensearchVersion=3.6.0
```

Run a single unit test:

```bash
./gradlew test -PopensearchVersion=3.6.0 --tests '*TransformFactoryTests'
```

Run a single integration test:

```bash
./gradlew itTest --tests '*TransformScriptIT'
```

## Smoke Tests

```bash
./gradlew smokeTest2Nodes
./gradlew smokeTestDedicatedCM
./gradlew smokeTest -Dcluster.topology=2n
./gradlew smokeTest -Dcluster.topology=cm
./gradlew smokeTest -Dcluster.topology=docker
```

Docker smoke tests require Docker to be running.

## Scale and Benchmark Tests

Scale and benchmark tasks are opt-in. Use them before release work or after changing migration execution, routing, batching, or worker behavior.

```bash
./gradlew scaleTest -Dcluster.topology=2n
./gradlew scaleTest -Dcluster.topology=cm
./gradlew benchmark
```

Some tests accept custom `-Dscale.*` or `-Dbenchmark.*` properties. Check the corresponding test class before relying on defaults.

## Debugging Failures

| Symptom | First checks |
|---------|--------------|
| Build cannot resolve OpenSearch build tools | Confirm `-PopensearchVersion` is set and network access. |
| Unit test failure | Re-run the specific test with `--info` or `--stacktrace`. |
| Integration test timeout | Check cluster logs under the Gradle test cluster output. |
| Docker smoke failure | Confirm Docker is running and ports are free. |
| Memory pressure | Lower Gradle workers or run one test class at a time. |

Use `--no-daemon` for long integration or Docker runs when investigating stale daemon state.
