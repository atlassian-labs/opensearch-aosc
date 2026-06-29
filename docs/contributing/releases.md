# Releases

AOSC releases have two version axes:

- **AOSC version**: the plugin project version, such as `0.1.0`.
- **OpenSearch compatibility line**: the OpenSearch major/minor line the ZIP was built against, such as `3.6`.

The plugin descriptor uses a patch-compatible semver range for the selected OpenSearch minor. For example, a ZIP built with OpenSearch `3.6.0` is intended for OpenSearch `3.6.x` unless a release note says otherwise.

## Source of Truth

The AOSC release version is tracked in `version.properties`:

```properties
aosc.version=0.0.0-dev
```

`develop` intentionally uses `0.0.0-dev`. Release branches are authoritative for release versions and should carry a concrete release candidate such as:

```properties
aosc.version=0.1.0-SNAPSHOT
```

OpenSearch compatibility metadata is tracked per release line:

```text
release/os2.properties
release/os3.properties
```

The current OpenSearch 3.x line is:

```properties
line=os3
branch=releases/3.x
primary_version=3.6.0
build_versions=3.1.0,3.3.0,3.5.0,3.6.0
test_versions=3.1.0,3.3.0,3.5.0,3.6.0
java_version=21
```

Release tags, asset names, documentation versions, GitHub Actions matrices, and release branch checks are derived from these files. Do not type release versions directly into the GitHub workflow.

## Branches

Release branches are organized by OpenSearch major line:

| Branch | Purpose |
| --- | --- |
| `develop` | Active development. |
| `releases/2.x` | OpenSearch 2.x maintenance and releases. |
| `releases/3.x` | OpenSearch 3.x maintenance and releases. |

GitHub release publishing must run from the branch declared in the matching `release/<line>.properties` file. For example, the OpenSearch 3.x release publisher should only publish plugin artifacts from `releases/3.x`.

Pushing to a release branch runs validation only. Publishing is manual: run the GitHub `Publish Release` workflow from the release branch after validation is green.

Do not publish plugin releases directly from `develop`. Merge or cherry-pick the intended release content into the matching `releases/*` branch first, then release from that branch. This keeps release artifacts tied to a maintenance line instead of a moving development branch.

Release tags should include the OpenSearch major line, for example:

```text
aosc-0.1.0-os2
aosc-0.1.0-os3
```

Avoid ambiguous tags such as `v0.1.0` once OpenSearch 2.x and 3.x can diverge.

The publish workflow fails before building if the computed GitHub release or tag already exists. Bump `aosc.version` before publishing another release for the same OpenSearch line.

## Release Version Lifecycle

Release branches use `-SNAPSHOT` as the pre-release marker for the next intended release:

```text
develop:      0.0.0-dev
releases/3.x: 0.1.0-SNAPSHOT
prepare:      human commits 0.1.0-SNAPSHOT -> 0.1.0
publish:      workflow tags 0.1.0, creates draft GitHub release, deploys docs
next work:    human commits 0.1.0 -> 0.1.1-SNAPSHOT, 0.2.0-SNAPSHOT, or 1.0.0-SNAPSHOT
```

The release workflow never commits back to the release branch. The release branch must already be in a releasable state before the workflow starts.

Manual release flow:

1. Merge or cherry-pick the intended changes into the matching release branch.
2. Set `aosc.version=X.Y.Z-SNAPSHOT` while preparing and validating the release branch.
3. Commit `aosc.version=X.Y.Z` when the branch is ready to release.
4. Run `Publish Release` from the release branch.
5. The workflow validates the current branch state, runs full CI, creates a tag such as `aosc-X.Y.Z-os3`, builds release ZIPs, creates a draft GitHub release, and publishes docs.
6. After release, commit the next `-SNAPSHOT` version to the release branch.

The workflow rejects `develop` versions such as `0.0.0-dev` and release-candidate versions such as `0.1.0-SNAPSHOT`; releases must be made from `releases/*` branches using exact `X.Y.Z` versions.

Use patch, minor, and major bumps this way:

| Bump | Use when | Example next snapshot |
| --- | --- | --- |
| Patch | Fixes, documentation corrections, CI/release fixes, compatibility metadata corrections | `0.1.1-SNAPSHOT` |
| Minor | New compatible user-visible behavior, new settings, new APIs, expanded compatibility | `0.2.0-SNAPSHOT` |
| Major | Breaking API, behavior, state format, or operational contract changes | `1.0.0-SNAPSHOT` |

## OpenSearch Compatibility

Each release line declares two compatibility lists:

| Property | Meaning |
| --- | --- |
| `build_versions` | OpenSearch minors that receive release ZIPs. |
| `test_versions` | Exact OpenSearch patch versions covered by CI. |
| `java_version` | JDK version used by CI and release builds for that OpenSearch line. |

For OpenSearch 3.x, AOSC currently builds release ZIPs for `3.1`, `3.3`, `3.5`, and `3.6`, and runs CI against `3.1.0`, `3.3.0`, `3.5.0`, and `3.6.0`.

The ZIP name intentionally uses the OpenSearch minor, for example `opensearch-aosc-0.1.0-opensearch-3.6.zip`. The plugin descriptor uses a patch-compatible semver range for that minor, so the `3.6` ZIP is intended for the `3.6.x` line unless the release notes call out an exception.

## GitHub Actions Policy

The workflow files are split by the reason a workflow runs:

| Workflow | File | Trigger | Publishes? |
| --- | --- | --- | --- |
| Pull Request | `.github/workflows/pull-request.yml` | Pull requests targeting `develop` or `releases/**` | No |
| Branch: develop | `.github/workflows/branch-develop.yml` | Pushes to `develop` | Publishes `/develop/` docs after validation passes |
| Branch: release | `.github/workflows/branch-release.yml` | Pushes to `releases/**` | No |
| Publish Release | `.github/workflows/publish-release.yml` | Manual dispatch from a release branch | Publishes GitHub release assets and release docs |
| Components | `.github/workflows/components.yml` | Reusable workflow only | No |

Pull requests run full CI automatically. Full CI builds the docs and validates every OpenSearch version in `test_versions` from `release/<line>.properties`.

Full CI runs these suite categories as separate jobs so failures identify the exact affected OpenSearch version and suite:

- fast checks
- YAML REST tests
- Java integration tests
- 2-node smoke tests
- dedicated cluster-manager smoke tests
- Docker smoke tests
- high-shard 2-node scale tests

Pushes to `develop` and `releases/**` also run full CI. Branch protection should require the stable check name:

- `Full CI`

The `Publish Release` workflow runs the same full validation before it builds release ZIPs or creates a draft GitHub release.

## GitHub Release Assets

Each GitHub release should include:

- one plugin ZIP per supported OpenSearch build target
- `SHA256SUMS`
- release notes with compatibility and upgrade notes
- an SBOM when available
- artifact provenance or attestation when available

Example OpenSearch 3.x assets:

```text
opensearch-aosc-0.1.0-opensearch-3.1.zip
opensearch-aosc-0.1.0-opensearch-3.3.zip
opensearch-aosc-0.1.0-opensearch-3.5.zip
opensearch-aosc-0.1.0-opensearch-3.6.zip
SHA256SUMS
```

## Building Locally

Build a ZIP for one OpenSearch version:

```bash
./gradlew :aosc-plugin:bundlePlugin -Dopensearch.version=3.6.0
```

Build and rename all currently supported OpenSearch 3.x ZIPs:

```bash
./scripts/build-release-zips.sh os3
```

The script writes release assets to `build/release/`.

Inspect the release metadata with:

```bash
./scripts/release-metadata.sh os3
```

## Documentation Site

GitHub Pages uses VitePress. Pull requests build the docs as CI validation only; they do not publish the site.

The docs workflows store versioned site output on the `gh-pages` branch, then deploy the complete site with GitHub Pages Actions. The GitHub Pages repository setting should use GitHub Actions as the source.

Pushes to `develop` publish the current development docs to `/develop/` after full CI passes.

The current docs layout is:

| Source | Published path |
| --- | --- |
| `develop` | `/develop/` |
| `releases/2.x` with `aosc.version=0.1.0` | `/0.1.0-os2/` |
| `releases/3.x` with `aosc.version=0.1.0` | `/0.1.0-os3/` |

Release documentation is versioned by exact AOSC patch version plus OpenSearch major line. For example, `aosc.version=0.1.0` and `line=os3` publish to `/0.1.0-os3/`. A patch release such as `0.1.1` publishes separate documentation at `/0.1.1-os3/`.

Build the docs locally with:

```bash
npm ci
npm run docs:build
```

The docs deploy script owns the generated `versions.json` and root redirect on `gh-pages`; do not edit those files by hand.

## CI Artifacts

CI uploads Gradle reports and test results only when a validation job fails. Normal CI artifacts expire after 7 days; publish workflow test artifacts expire after 14 days. Release ZIPs are not CI artifacts; they are attached to GitHub Releases.

## Runtime Version Checks

Installed plugin versions can be checked with:

```bash
curl -s 'http://localhost:9200/_cat/plugins?v'
```

A built ZIP descriptor can be inspected with:

```bash
unzip -p aosc-plugin/build/distributions/opensearch-aosc-*.zip plugin-descriptor.properties
```

## First Public Import

Public GitHub history starts from the OSS import. Earlier internal development history is not part of the public repository.
