# Running Tests

All commands require `OPENSEARCH_VERSION` or `-Dopensearch.version`.

```bash
export OPENSEARCH_VERSION=3.6.0
```

## Test Matrix

| Task | Source | Purpose |
|------|--------|---------|
| `:aosc-plugin:unitTest` | `aosc-plugin/src/test/` | Unit tests. |
| `:aosc-plugin:fastCheck` | unit tests plus build checks | Fast local verification. |
| `:aosc-plugin:yamlRestTest` | `aosc-plugin/src/yamlRestTest/` | YAML REST API checks. |
| `:aosc-plugin:itTest` | `aosc-plugin/src/itTest/` | In-JVM OpenSearch integration tests. |
| `:aosc-plugin:smokeTest2Nodes` | `aosc-plugin/src/smokeTest/` | REST smoke tests against a 2-node test cluster. |
| `:aosc-plugin:smokeTestDedicatedCM` | `aosc-plugin/src/smokeTest/` | Smoke tests with a dedicated cluster-manager node. |
| `:aosc-plugin:smokeTestDocker` | `aosc-plugin/src/smokeTest/` | Smoke tests against the Docker cluster. |
| `:aosc-plugin:scaleTest` | `aosc-plugin/src/scaleTest/` | Larger correctness and shard-layout validation. |
| `:aosc-plugin:benchmark` | `aosc-plugin/src/benchmarkTest/` | Throughput and latency experiments. |

## Common Commands

The integration suite starts OpenSearch test clusters and can take several minutes on a developer laptop.

```bash
./gradlew :aosc-plugin:unitTest
./gradlew :aosc-plugin:fastCheck
./gradlew :aosc-plugin:yamlRestTest
./gradlew :aosc-plugin:itTest
```

Run a single unit test:

```bash
./gradlew :aosc-plugin:unitTest --tests '*TransformFactoryTests'
```

Run a single integration test:

```bash
./gradlew :aosc-plugin:itTest --tests '*TransformScriptIT'
```

## Smoke Tests

```bash
./gradlew :aosc-plugin:smokeTest2Nodes
./gradlew :aosc-plugin:smokeTestDedicatedCM
./gradlew :aosc-plugin:smokeTest -Dcluster.topology=2n
./gradlew :aosc-plugin:smokeTest -Dcluster.topology=cm
./gradlew :aosc-plugin:smokeTest -Dcluster.topology=docker
```

Docker smoke tests require Docker to be running.

## Scale and Benchmark Tests

Scale and benchmark tasks are opt-in. Use them before release work or after changing migration execution, routing, batching, or worker behavior.

```bash
./gradlew :aosc-plugin:scaleTest -Dcluster.topology=2n
./gradlew :aosc-plugin:scaleTest -Dcluster.topology=cm
./gradlew :aosc-plugin:benchmark
```

Some tests accept custom `-Dscale.*` or `-Dbenchmark.*` properties. Check the corresponding test class before relying on defaults.

## Debugging Failures

| Symptom | First checks |
|---------|--------------|
| Build cannot resolve OpenSearch build tools | Confirm `OPENSEARCH_VERSION` and network access. |
| Unit test failure | Re-run the specific test with `--info` or `--stacktrace`. |
| Integration test timeout | Check cluster logs under the Gradle test cluster output. |
| Docker smoke failure | Confirm Docker is running and ports are free. |
| Memory pressure | Lower Gradle workers or run one test class at a time. |

Use `--no-daemon` for long integration or Docker runs when investigating stale daemon state.
