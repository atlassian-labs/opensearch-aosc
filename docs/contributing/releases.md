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

Release tags, asset names, documentation versions, GitHub Actions matrices, and release branch checks are derived from these files. Do not type release versions directly into the GitHub workflow.

## Branches

Release branches are organized by OpenSearch major line:

| Branch | Purpose |
| --- | --- |
| `develop` | Active development. |
| `releases/2.x` | OpenSearch 2.x maintenance and releases. |
| `releases/3.x` | OpenSearch 3.x maintenance and releases once supported. |

GitHub release publishing must run from the branch declared in the matching `release/<line>.properties` file. For example, the OpenSearch 2.x release publisher should only publish plugin artifacts from `releases/2.x`.

Pushing to a release branch runs validation only. Publishing is manual: run the GitHub `Publish Release` workflow from the release branch after validation is green.

Do not publish plugin releases directly from `develop`. Merge or cherry-pick the intended release content into the matching `releases/*` branch first, then release from that branch. This keeps release artifacts tied to a maintenance line instead of a moving development branch.

Release tags should include the OpenSearch major line, for example:

```text
aosc-0.1.0-os2
aosc-0.1.0-os3
```

Avoid ambiguous tags such as `v0.1.0` once OpenSearch 2.x and 3.x can diverge.

The publish workflow fails before building if the computed release tag or GitHub release already exists. Bump `aosc.version` before publishing another release for the same OpenSearch line.

## OpenSearch Compatibility

Each release line declares two compatibility lists:

| Property | Meaning |
| --- | --- |
| `build_versions` | OpenSearch minors that receive release ZIPs. |
| `test_versions` | Exact OpenSearch patch versions covered by CI. |

For OpenSearch 2.x, AOSC currently builds release ZIPs for `2.15`, `2.17`, and `2.19`, and runs CI against `2.15.0`, `2.17.0`, `2.17.1`, `2.19.0`, and `2.19.3`.

The ZIP name intentionally uses the OpenSearch minor, for example `opensearch-aosc-0.1.0-opensearch-2.19.zip`. The plugin descriptor uses a patch-compatible semver range for that minor, so the `2.19` ZIP is intended for the `2.19.x` line unless the release notes call out an exception.

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

GitHub Pages uses VitePress. Pull requests build the docs as CI validation only; they do not publish the site.

The docs workflows store versioned site output on the `gh-pages` branch, then deploy the complete site with GitHub Pages Actions. The GitHub Pages repository setting should use GitHub Actions as the source.

Pushes to `develop` publish the current development docs to `/develop/` after full CI passes.

The current docs layout is:

| Source | Published path |
| --- | --- |
| `develop` | `/develop/` |
| `releases/2.x` with `aosc.version=0.1.0` | `/0.1-os2/` |
| `releases/3.x` with `aosc.version=0.1.0` | `/0.1-os3/` once the branch exists |

Release documentation is versioned by AOSC minor line plus OpenSearch major line. For example, `aosc.version=0.1.0` and `line=os2` publish to `/0.1-os2/`. A patch release such as `0.1.1` updates the same `/0.1-os2/` documentation version instead of creating a new docs version for every patch.

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
