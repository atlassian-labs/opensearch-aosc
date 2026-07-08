# Releases

AOSC ships from `develop` alone. There are no release branches: `develop` builds both OpenSearch lines, and each release is cut from a `develop` commit.

A release has two version axes:

- **AOSC version**: the plugin project version, such as `0.1.0`. One AOSC version supports both OpenSearch lines.
- **OpenSearch minor**: the OpenSearch minor an individual ZIP was built against, such as `2.19` or `3.6`.

The plugin descriptor uses a patch-compatible semver range for the selected OpenSearch minor. For example, a ZIP built with OpenSearch `3.6.0` is intended for OpenSearch `3.6.x` unless a release note says otherwise.

## The Version Lives in Tags

`develop`'s `version.properties` intentionally stays at a non-release value and never changes for a release:

```properties
aoscVersion=0.0.0-dev
```

The released version is **not** committed to `develop`. Instead, the `Publish Release` workflow:

1. reads the latest `v*` tag (`git tag --list 'v*' --sort=-v:refname`),
2. applies the chosen `patch` / `minor` / `major` bump to compute the next version, and
3. stamps that version into the build with `-PaoscVersion=<next>` and creates the tag `v<next>` at publish time.

If no `v*` tag exists yet, the first release seeds from `0.0.0` (so `minor` produces `v0.1.0`). This means there are no version-bump commits: the tag history is the version history.

## OpenSearch Line Manifests

Line-specific build and compatibility data lives in one file per line:

```text
release/os2.properties
release/os3.properties
```

These are the single source of truth for each line's supported minors, JDK, Gradle, and dependency pins. Illustrative shape (see the files themselves, or the [Compatibility reference](../reference/compatibility.md) which renders the current set, for the authoritative values):

```properties
line=os2
primary_version=2.19.0
build_versions=2.15.0,…,2.19.0          # one X.Y.0 per shipped minor
test_versions=2.15.0,…,2.19.6           # every validated patch
java_version=11
gradle_version=8.7                      # per-version overrides, e.g. gradle_version.3.7=9.4.1
# plus java_agent, jackson_*, and error_prone.* keys consumed by the build
```

| Property | Meaning |
| --- | --- |
| `build_versions` | One version (X.Y.0) per minor that receives a release ZIP. Must cover the same minors as `test_versions` (enforced by `ci-metadata.py`). |
| `test_versions` | Every released patch covered by CI validation. |
| `java_version` | Bytecode target for that line (11 for os2, 21 for os3). |

Release ZIP names, CI matrices, and the release build matrix are derived from these files. Do not type OpenSearch versions directly into the workflow.

## Cutting a Release

Releases are tag-driven and fully automated from `develop`:

1. Ensure `develop` is in a releasable state (CI green).
2. Run the GitHub **Publish Release** workflow (`workflow_dispatch`) and pick a `bump`: `patch`, `minor`, or `major`.
3. The workflow computes the next version, validates it does not already exist, runs full validation for both lines at the `develop` SHA, builds one ZIP per supported minor, and publishes a single GitHub release tagged `v<version>`.
4. Release docs deploy once to `/<version>/`.

Choose the bump with the same semantics as before:

| Bump | Use when | From `v0.1.1` |
| --- | --- | --- |
| `patch` | Fixes, docs corrections, CI/release fixes, compatibility metadata corrections | `v0.1.2` |
| `minor` | New compatible behavior, new settings, new APIs, expanded compatibility | `v0.2.0` |
| `major` | Breaking API, behavior, state format, or operational contract changes | `v1.0.0` |

The workflow refuses to proceed if the computed tag or release already exists; pick a different bump or remove the stale tag.

## Release Pipeline Shape

`Publish Release` (`.github/workflows/publish-release.yml`) runs as a singleton (`concurrency: publish-release`) with these jobs:

| Job | Does |
| --- | --- |
| `metadata` | Resolves the next version from tags, records the `develop` SHA, emits the per-minor build matrix, and guards that `v<version>` is fresh. |
| `validation` | Calls `components.yml` with `os_line: all`, `tier: full` at the resolved SHA. |
| `build` | Fans out one job per supported minor (JDK per row), runs `bundlePlugin -PopensearchVersion=<minor> -PaoscVersion=<version>`, and renames the output to `opensearch-aosc-<version>-os<minor>.zip`. |
| `release-compat` | Downloads the built ZIPs and installs each **published** artifact onto a Docker node at its minor's boundary patches (`-Ptests.docker.pluginZip`), running the docker smoke suite — validating the shipped bytes across the patches they claim. Gates `publish`. |
| `publish` | Gathers all ZIPs, writes `SHA256SUMS` and release notes, attests provenance, and runs `gh release create v<version> --target <sha>` (which creates the tag atomically). Runs in the `release` environment. Needs `validation`, `build`, and `release-compat`. |
| `deploy_docs` | Deploys the docs once to `/<version>/`. |

Every ZIP for every minor of both lines is built and validated at the same `develop` commit, so a release is a coherent snapshot rather than an accumulation across branches.

## GitHub Release Assets

Each `v<version>` release contains, for both lines:

- one plugin ZIP per supported OpenSearch minor, named `opensearch-aosc-<version>-os<minor>.zip`
- `SHA256SUMS`
- release notes with download and compatibility guidance
- build provenance attestation

Example asset shape for `v0.1.0` (one ZIP per supported minor — the exact set is whatever the manifests declare at release time; see the [Compatibility reference](../reference/compatibility.md)):

```text
opensearch-aosc-0.1.0-os2.15.zip
opensearch-aosc-0.1.0-os2.17.zip
…
opensearch-aosc-0.1.0-os3.6.zip
opensearch-aosc-0.1.0-os3.7.zip
SHA256SUMS
```

Download the ZIP matching your OpenSearch minor. A `3.6` ZIP is intended for the `3.6.x` patch line unless a release note says otherwise.

## GitHub Actions

The workflow files are split by the reason a workflow runs:

| Workflow | File | Trigger | Publishes? |
| --- | --- | --- | --- |
| Pull Request | `.github/workflows/pull-request.yml` | Pull requests targeting `develop` | No |
| Branch: develop | `.github/workflows/branch-develop.yml` | Pushes to `develop` | Publishes `/develop/` docs after validation passes |
| Publish Release | `.github/workflows/publish-release.yml` | Manual dispatch (`bump` input) | Publishes the `v<version>` GitHub release and `/<version>/` docs |
| Components | `.github/workflows/components.yml` | Reusable workflow only | No |

`components.yml` builds a matrix over both lines' minors (each row carries `opensearch_version`, `java_version`, and an unqualified Gradle task run with `-PopensearchVersion`). A `tier` input (`fast` | `full`) controls breadth. Full validation runs these suites as separate jobs so a failure identifies the exact minor and suite:

- fast checks
- YAML REST tests
- Java integration tests
- 2-node smoke tests
- dedicated cluster-manager smoke tests
- Docker smoke tests
- high-shard 2-node scale tests

## Building Locally

Build a ZIP for one OpenSearch minor:

```bash
./gradlew bundlePlugin -PopensearchVersion=3.6.0
```

Stamp a specific release version into the descriptor (as the release workflow does):

```bash
./gradlew bundlePlugin -PopensearchVersion=3.6.0 -PaoscVersion=0.1.0
```

Inspect the release metadata the workflow uses:

```bash
./scripts/ci-metadata.py all --build-matrix
./scripts/ci-metadata.py --next-version minor
```

## Documentation Site

GitHub Pages uses VitePress. Pull requests build the docs as CI validation only; they do not publish the site.

The docs workflows store versioned site output on the `gh-pages` branch, then deploy the complete site with GitHub Pages Actions. The GitHub Pages repository setting should use GitHub Actions as the source.

Docs are versioned by AOSC version only (no OpenSearch line suffix), following two simple rules:

- Pushes to `develop` publish the current development docs to `/develop/` after validation passes.
- Each release `v<version>` deploys its docs once to `/<version>/`.

`versions.json` on `gh-pages` is the authoritative list of published versions; the deploy script maintains it and the theme builds the version switcher from it at runtime. The site config carries only a placeholder switcher entry that the theme replaces — no concrete version list is hand-maintained.

Build the docs locally with:

```bash
npm ci
npm run docs:build
```

The docs deploy script owns the generated `versions.json` and root redirect on `gh-pages`; do not edit those files by hand.

## CI Artifacts

CI uploads Gradle reports and test results only when a validation job fails. Normal CI artifacts expire after 7 days; publish workflow artifacts expire after 14 days. Release ZIPs are not CI artifacts; they are attached to the GitHub release.

## Runtime Version Checks

Installed plugin versions can be checked with:

```bash
curl -s 'http://localhost:9200/_cat/plugins?v'
```

A built ZIP descriptor can be inspected with:

```bash
unzip -p */build/distributions/opensearch-aosc-*.zip plugin-descriptor.properties
```

## First Public Import

Public GitHub history starts from the OSS import. Earlier internal development history is not part of the public repository.
