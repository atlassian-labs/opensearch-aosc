# Releases

AOSC releases have two version axes:

- **AOSC version**: the plugin project version, such as `0.1.0`.
- **OpenSearch compatibility line**: the OpenSearch major/minor line the ZIP was built against, such as `2.19`.

The plugin descriptor uses a patch-compatible semver range for the selected OpenSearch minor. For example, a ZIP built with OpenSearch `2.19.0` is intended for OpenSearch `2.19.x` unless a release note says otherwise.

## Source of Truth

The AOSC release version is tracked in `version.properties`:

```properties
aosc.version=0.1.0
```

OpenSearch compatibility metadata is tracked per release line:

```text
release/os2.properties
release/os3.properties
```

The current OpenSearch 2.x line is:

```properties
line=os2
branch=releases/2.x
primary_version=2.19.0
build_versions=2.15.0,2.17.0,2.19.0
test_versions=2.15.0,2.17.0,2.17.1,2.19.0,2.19.3
```

Release tags, asset names, GitHub Actions matrices, and release branch checks are derived from these files. Do not type release versions directly into the GitHub workflow.

## Branches

Release branches are organized by OpenSearch major line:

| Branch | Purpose |
| --- | --- |
| `develop` | Active development. |
| `releases/2.x` | OpenSearch 2.x maintenance and releases. |
| `releases/3.x` | OpenSearch 3.x maintenance and releases once supported. |

GitHub release workflows must run from the branch declared in the matching `release/<line>.properties` file. For example, the OpenSearch 2.x release workflow should only publish from `releases/2.x`.

Pushing to a release branch runs CI only. Publishing is manual: run the GitHub `Release` workflow from the release branch after CI is green.

Release tags should include the OpenSearch major line, for example:

```text
aosc-0.1.0-os2
aosc-0.1.0-os3
```

Avoid ambiguous tags such as `v0.1.0` once OpenSearch 2.x and 3.x can diverge.

The release workflow fails before building if the computed release tag or GitHub release already exists. Bump `aosc.version` before publishing another release for the same OpenSearch line.

## GitHub CI Policy

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

The `Release` workflow runs the same full validation before it builds release ZIPs or creates a draft GitHub release.

## GitHub Release Assets

Each GitHub release should include:

- one plugin ZIP per supported OpenSearch build target
- `SHA256SUMS`
- release notes with compatibility and upgrade notes
- an SBOM when available
- artifact provenance or attestation when available

Example OpenSearch 2.x assets:

```text
opensearch-aosc-0.1.0-opensearch-2.15.zip
opensearch-aosc-0.1.0-opensearch-2.17.zip
opensearch-aosc-0.1.0-opensearch-2.19.zip
SHA256SUMS
```

## Building Locally

Build a ZIP for one OpenSearch version:

```bash
./gradlew :aosc-plugin:bundlePlugin -Dopensearch.version=2.19.0
```

Build and rename all currently supported OpenSearch 2.x ZIPs:

```bash
./scripts/build-release-zips.sh os2
```

The script writes release assets to `build/release/`.

Inspect the release metadata with:

```bash
./scripts/release-metadata.sh os2
```

## Documentation Site

GitHub Pages uses MkDocs Material with `mike` versioning. Pull requests and branch pushes build the docs as CI validation only; they do not publish the site.

The `Release` workflow publishes docs to the `gh-pages` branch after release validation, asset build, and draft release creation succeed. The GitHub Pages repository setting should serve from the `gh-pages` branch at `/`.

The current docs layout is:

| Source | Published path |
| --- | --- |
| `develop` | `/develop/` with `/latest/` alias |
| `releases/2.x` | `/2.x/` |
| `releases/3.x` | `/3.x/` once the branch exists. |

Build the docs locally with:

```bash
mkdocs build --strict
```

`mike` owns the version selector and generated `versions.json`; do not edit those files by hand.

## CI Artifacts

CI uploads Gradle reports and test results only when a validation job fails. Normal CI artifacts expire after 7 days; release workflow test artifacts expire after 14 days. Release ZIPs are not CI artifacts; they are attached to GitHub Releases.

## First Public Import

Public GitHub history starts from the OSS import. Earlier internal development history is not part of the public repository.
