# Contributing Guidelines

This project accepts issues and pull requests through GitHub. AOSC is an Atlassian Labs project maintained on a best-effort basis; there is no guaranteed response time or roadmap commitment.

## Where Contribution Information Lives

Use this file for the public contribution contract: how to report issues, open pull requests, write commit messages, format code, and satisfy licensing expectations.

Use the documentation site's [development environment guide](docs/contributing/dev-environment.md) and related contributing pages for source-tree workflow details such as local environment setup, test commands, code layout, release notes, and extension points. Those pages are practical engineering references; this file is the checklist a GitHub contributor should read before opening a pull request.

## Reporting Bugs and Requesting Features

Open a GitHub issue with:

- The OpenSearch version and AOSC version or commit.
- The request body and relevant cluster settings.
- Expected and actual behavior.
- Logs from the cluster-manager node and any affected data nodes.
- A small reproduction, if one is available.

Avoid attaching production data or secrets.

## Pull Requests

Before sending a pull request:

1. Work from the latest `develop` branch.
2. Check existing issues and pull requests for overlap.
3. Open an issue first for broad design changes or behavior changes.
4. Keep the pull request focused on one problem.
5. Review the local workflow docs when changing source code, tests, releases, or extension APIs.

Run at least the core checks for the OpenSearch version you changed against:

```bash
export OPENSEARCH_VERSION=2.19.0
./gradlew :aosc-plugin:fastCheck
./gradlew :aosc-plugin:yamlRestTest
./gradlew :aosc-plugin:itTest
```

Some changes need broader validation, such as `:aosc-plugin:smokeTest`, `:aosc-plugin:scaleTest`, or `:aosc-plugin:benchmark`. See [Running Tests](docs/contributing/running-tests.md).

GitHub Actions runs the public CI matrix for pull requests. Maintainers may ask for additional local or maintainer-run validation for compatibility-sensitive changes.

## AI-Assisted Contributions

AI-assisted changes are welcome when they are reviewed like any other contribution. Contributors are responsible for:

- verifying that generated code is correct
- running relevant tests
- checking that generated text is accurate and not exaggerated
- ensuring the contribution is license-compatible
- removing private data, credentials, internal URLs, and non-public operational details

The public `AGENTS.md` file contains repository-specific guidance for AI coding agents.

## Commit Messages

Use Conventional Commits:

```text
<type>(<optional scope>): <description>
```

Common types:

| Type | Use for |
|------|---------|
| `feat` | New behavior or capability. |
| `fix` | Bug fixes. |
| `docs` | Documentation-only changes. |
| `test` | Test additions or updates. |
| `refactor` | Code structure changes without behavior changes. |
| `perf` | Performance changes. |
| `build` | Build or dependency changes. |
| `ci` | CI configuration changes. |
| `chore` | Routine maintenance. |

Keep the first line short and use imperative mood, for example `fix(worker): release lease on cancellation`.

## Formatting

Java formatting is enforced with Spotless and the OpenSearch formatter profile.

```bash
./gradlew :aosc-plugin:spotlessApply
./gradlew :aosc-plugin:spotlessCheck
```

Rules enforced by the build include no wildcard imports and the repository import order.

## License Headers

New source files should include:

```java
/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
```

## License

See [LICENSE](LICENSE).
