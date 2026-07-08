# AOSC Agent Guide

This guide is for AI agents and automation working in the public AOSC
repository. For project architecture, build commands, and contribution flow,
read `README.md` and `CONTRIBUTING.md` first.

## Attribution

Do not add AI/assistant attribution or fingerprints anywhere — no "Generated with
Claude Code", no `Co-Authored-By` an AI, no "written by an AI" notes — in commit
messages, pull request titles/bodies, code comments, or docs. Author contributions
as ordinary work under the human contributor's identity.

## OpenSearch API Compatibility

AOSC `develop` builds BOTH OpenSearch lines from two per-line source trees:
`aosc-plugin-os3` (3.x packages: `org.opensearch.transport.client.*`,
`action.support.clustermanager.*`, Java 21) and `aosc-plugin-os2` (2.x packages:
`org.opensearch.client.*`, `action.support.master.*`, Java 11). Supported versions
per line live in `release/os2.properties` / `release/os3.properties`. The
`-PopensearchVersion` value selects the line; tasks take no project prefix.

There is no shared Java `core` yet, so **any shared fix must be applied to
BOTH `aosc-plugin-os2/src` and `aosc-plugin-os3/src`** (keeping each line's
version-specific imports) until a shared `core` is extracted.

Common 3.x imports:

```java
import org.opensearch.transport.client.Client;
import org.opensearch.core.action.ActionListener;
import org.opensearch.action.ActionType;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
```

The AOSC OpenSearch 3.x plugin currently uses the positional `createComponents`
signature exposed by the target OpenSearch versions:

```java
@Override
public Collection<Object> createComponents(
    Client client,
    ClusterService clusterService,
    ThreadPool threadPool,
    ResourceWatcherService resourceWatcherService,
    ScriptService scriptService,
    NamedXContentRegistry xContentRegistry,
    Environment environment,
    NodeEnvironment nodeEnvironment,
    NamedWriteableRegistry namedWriteableRegistry,
    IndexNameExpressionResolver indexNameExpressionResolver,
    Supplier<RepositoriesService> repositoriesServiceSupplier
) {
    ...
}
```

Use `org.apache.lucene.tests.util.LuceneTestCase.AwaitsFix` for ignored/flaky
test annotations. Do not import `com.carrotsearch.randomizedtesting.annotations.AwaitsFix`.

## Structured Logging

AOSC uses `AoscLogger` for structured context. When a constructor receives an
`AoscLogger`, bind it to the concrete class:

```java
this.logger = Objects.requireNonNull(logger, "logger").forClass(MyClass.class);
```

Use `LC` constants for structured field keys. Keep log messages static and put
values in `kv()` fields:

```java
import static com.atlassian.opensearch.aosc.utils.AoscLogger.kv;

logger.info("Gradient decrease", kv(LC.EVENT, "gradient_decrease"), kv(LC.GRADIENT, gradient));
```

Do not introduce direct `LogManager.getLogger()` or `Loggers.getLogger()` calls
in AOSC components.

## Async Patterns

OpenSearch transport actions use `ActionListener<T>` callbacks. Propagate
failures with `ActionListener.onFailure()` and do not hold locks while invoking
callbacks.

Prefer explicit OpenSearch thread-pool scheduling over Java common-pool helpers
in test-sensitive code. In particular, avoid `CompletableFuture.delayedExecutor`
inside OpenSearch tests; the common pool is blocked by the test security manager.
Use project utilities that schedule through the OpenSearch `ThreadPool`.

## Core Safety Invariants

- Translog snapshots must be closed in a `finally` block or equivalent cleanup path.
- Bulk writes use idempotent index operations, not create-only writes.
- Source write blocking must use the OpenSearch add-block API so in-flight writes drain before cutover.
- Retention leases must not be released while a migration still needs source operation history.
- Background async failures must be surfaced to the migration state machine or caller; do not swallow them in logs only.

## Tests

Use targeted validation while developing:

```bash
# The -P version selects the OpenSearch line, so no project prefix is needed (set the version
# per invocation or once in ~/.gradle/gradle.properties).
./gradlew fastCheck -PopensearchVersion=3.6.0
./gradlew yamlRestTest -PopensearchVersion=3.6.0
./gradlew itTest -PopensearchVersion=3.6.0
# The 2.x line builds the same way — just change the version:
./gradlew fastCheck -PopensearchVersion=2.19.0
npm run docs:build
```

Gradle version: OpenSearch 2.15–3.6 build on the committed wrapper (Gradle 8.7). OpenSearch
**3.7 requires Gradle 9.4.1** (its build-tools rejects older Gradle, and 2.x build-tools breaks
on Gradle 9 — so no single wrapper serves both). Before building 3.7, run
`./scripts/set-gradle.sh 3.7.0` (it points the wrapper at 9.4.1); `./scripts/set-gradle.sh --reset`
restores 8.7. CI does this per job automatically. Do not commit the flipped wrapper.

Use `--no-daemon` for longer integration runs when debugging stale Gradle
workers:

```bash
./gradlew --no-daemon itTest -PopensearchVersion=3.6.0
```

Run broader version matrix checks before release or compatibility-sensitive
changes. The supported OpenSearch versions are declared in `release/os3.properties`.

## Documentation

Update documentation for any change to:

- REST API request or response fields
- migration phases or state transitions
- configuration settings
- operational behavior
- compatibility guarantees
- public extension points

Avoid unsupported performance claims. If a timing or scale statement is
included, state the observed conditions.

Keep public docs free of internal Atlassian systems, private links, internal
deployment process, and local-machine assumptions.
