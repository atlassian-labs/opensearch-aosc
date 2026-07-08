#!/usr/bin/env python3
"""Single source of CI/release metadata for the AOSC two-line build.

Reads version.properties (aoscVersion) and release/os<major>.properties. Modes:
  --github-output       : key=value for $GITHUB_OUTPUT (per-line meta + validation matrix)
  --validation-matrix   : validation matrix JSON (selected versions x all suites)
  --build-matrix        : release build matrix JSON (build_versions x bundlePlugin)
  --release-compat-matrix : shipped-zip-per-minor x boundary-patch docker-smoke JSON
  --next-version <bump> : next X.Y.Z from git tags (patch|minor|major); line-independent
  --gradle-version <v>  : Gradle version required to build OpenSearch version <v>
  --summary             : human-readable
  --shell               : KEY=VALUE for bash `source` (single line; inspection)

The selected OpenSearch version chooses the module (settings.gradle includes only the
matching one), so gradle_task values are UNQUALIFIED (e.g. 'fastCheck', 'bundlePlugin')
and are run with -PopensearchVersion=<version>.

VALIDATION_TIER is a *version-selection* axis (all suites run in either tier):
  full : validate every patch in test_versions (comprehensive; develop-push + release).
  fast : validate only the min and max patch of each minor (boundary patches; PRs).

Usage: [VALIDATION_TIER=fast|full] [AOSC_VERSION_OVERRIDE=X.Y.Z] \\
       scripts/ci-metadata.py <os2|os3|all> <mode>
       scripts/ci-metadata.py --next-version <patch|minor|major>
"""
import json
import os
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
LINES = ("os2", "os3")

# Validation suites (id, label, gradle task, extra gradle args). All run in both tiers;
# the tier only narrows which OpenSearch versions they run against (see versions_for_tier).
VALIDATION_SUITES = [
    ("fast-check", "fastCheck", "fastCheck", ""),
    ("yaml-rest", "yamlRestTest", "yamlRestTest", ""),
    ("integration", "itTest", "itTest", ""),
    ("smoke-2n", "smokeTest2Nodes", "smokeTest2Nodes", ""),
    ("smoke-dedicated-cm", "smokeTestDedicatedCM", "smokeTestDedicatedCM", ""),
    ("smoke-docker", "smokeTestDocker", "smokeTestDocker", ""),
    ("scale-high-shard-2n", "scaleTest high-shard 2n", "scaleTest",
     "-Dcluster.topology=2n -Dscale.profile=high-shard"),
]
# Release build: one bundlePlugin per minor.
BUILD_TASKS = [("bundle", "bundlePlugin", "bundlePlugin", "")]


def load_props(path):
    props = {}
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def csv(value):
    return [v.strip() for v in value.split(",") if v.strip()]


def version_key(version):
    return [int(part) for part in version.split(".")]


def minor_of(version):
    major, minor = version.split(".")[:2]
    return (int(major), int(minor))


def minor_str(version):
    return ".".join(version.split(".")[:2])


def gradle_version_for(props, version):
    """Gradle version required to build an OpenSearch version: per-minor override, else line default.

    OpenSearch build-tools pins a minimum Gradle and older build-tools break on newer Gradle, so a
    single wrapper can't serve every line (e.g. 2.x needs Gradle 8.x, 3.7 needs >=9.4.1)."""
    return props.get(f"gradle_version.{minor_str(version)}") or props.get("gradle_version", "8.7")


def versions_for_tier(versions, tier):
    """full -> every version; fast -> min+max patch of each minor (boundary patches)."""
    if tier != "fast":
        return versions
    by_minor = {}
    for version in versions:
        by_minor.setdefault(minor_of(version), []).append(version)
    selected = []
    for minor in sorted(by_minor):
        patches = sorted(by_minor[minor], key=version_key)
        selected.append(patches[0])
        if patches[-1] != patches[0]:
            selected.append(patches[-1])
    # Preserve the original ordering of the input list.
    chosen = set(selected)
    return [v for v in versions if v in chosen]


def line_meta(line):
    version_props = load_props(ROOT / "version.properties")
    line_file = ROOT / "release" / f"{line}.properties"
    if not line_file.exists():
        sys.exit(f"Unknown release line: {line} (expected {line_file})")
    p = load_props(line_file)
    if p.get("line") != line:
        sys.exit(f"Line mismatch: requested {line}, {line_file} declares {p.get('line')}")
    # Every validated minor must ship an installable zip and vice versa (the ~X.Y.0 descriptor
    # means a neighbouring-minor zip will not install on a different minor). Enforce that
    # build_versions and test_versions cover exactly the same set of minors.
    build_minors = {minor_of(v) for v in csv(p["build_versions"])}
    test_minors = {minor_of(v) for v in csv(p["test_versions"])}
    if build_minors != test_minors:
        only_build = [f"{a}.{b}" for a, b in sorted(build_minors - test_minors)]
        only_test = [f"{a}.{b}" for a, b in sorted(test_minors - build_minors)]
        sys.exit(f"{line_file}: build_versions and test_versions must cover the same minors "
                 f"(only in build_versions: {only_build}; only in test_versions: {only_test}).")
    # Release zips are named os<major.minor> and the descriptor is rewritten to ~X.Y.0 (covers the
    # whole minor). A non-.0 build version would ship an "os<minor>" zip whose range starts mid-minor.
    non_zero = [v for v in csv(p["build_versions"]) if version_key(v)[2] != 0]
    if non_zero:
        sys.exit(f"{line_file}: every build_version must be a .0 patch (descriptor ~X.Y.0 must cover "
                 f"the whole minor); got: {non_zero}.")
    # Releases are cut from develop (version.properties is 0.0.0-dev there); the publish
    # workflow passes the real X.Y.Z via AOSC_VERSION_OVERRIDE.
    aosc = os.environ.get("AOSC_VERSION_OVERRIDE") or version_props["aoscVersion"]
    return {
        "aosc_version": aosc,
        "os_line": line,
        "primary_version": p["primary_version"],
        "build_versions": p["build_versions"],
        "test_versions": p["test_versions"],
        "target_java_version": p["target_java_version"],                            # bytecode target
        "build_java_version": p.get("build_java_version", p["target_java_version"]),  # JVM that runs the build
        "release_tag": f"v{aosc}",
        "docs_version": aosc,
    }


def rows(line, versions, tasks):
    meta = line_meta(line)
    return [
        {
            "opensearch_version": version,
            "target_java_version": meta["target_java_version"],
            "build_java_version": meta["build_java_version"],  # setup-java installs this (Gradle JVM)
            "os_line": line,          # cosmetic (artifact naming); the version selects the module
            "id": id_,
            "label": label,
            "gradle_task": task,      # unqualified; run with -PopensearchVersion=<version>
            "gradle_args": args,
        }
        for version in versions
        for (id_, label, task, args) in tasks
    ]


def lines_for(line):
    return LINES if line == "all" else (line,)


def validation_matrix(line, tier):
    out = []
    for ln in lines_for(line):
        meta = line_meta(ln)
        out += rows(ln, versions_for_tier(csv(meta["test_versions"]), tier), VALIDATION_SUITES)
    return out


def build_matrix(line):
    out = []
    for ln in lines_for(line):
        meta = line_meta(ln)
        out += rows(ln, csv(meta["build_versions"]), BUILD_TASKS)
    return out


def release_compat_matrix(line):
    """Validate the actual shipped artifact across a minor's patches.

    For each build_version (the version the release zip is built at, descriptor ~X.Y.0),
    install that same-built plugin onto a Docker node at each boundary patch (min/max) of
    the minor and run the docker smoke suite. This exercises the real release zip on the
    higher patches it claims (via the tilde range) to support — which the rebuild-per-patch
    validation matrix never does.
    """
    out = []
    for ln in lines_for(line):
        meta = line_meta(ln)
        by_minor = {}
        for v in csv(meta["test_versions"]):
            by_minor.setdefault(minor_of(v), []).append(v)
        for build_version in csv(meta["build_versions"]):
            patches = sorted(by_minor.get(minor_of(build_version), [build_version]), key=version_key)
            for container_version in sorted({patches[0], patches[-1]}, key=version_key):
                out.append({
                    "os_line": ln,
                    "opensearch_version": build_version,     # build the shipped zip at this
                    "container_version": container_version,  # install + docker-smoke on this patch
                    "target_java_version": meta["target_java_version"],
                    "build_java_version": meta["build_java_version"],
                    "id": f"{ln}-{build_version}-on-{container_version}",
                    "label": f"{build_version} zip on {container_version}",
                })
    return out


def next_version(bump):
    tags = subprocess.run(
        ["git", "tag", "--list", "v*", "--sort=-v:refname"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout.split()
    latest = next((t[1:] for t in tags if re.fullmatch(r"v\d+\.\d+\.\d+", t)), "0.0.0")
    major, minor, patch = (int(x) for x in latest.split("."))
    if bump == "major":
        return f"{major + 1}.0.0"
    if bump == "minor":
        return f"{major}.{minor + 1}.0"
    if bump == "patch":
        return f"{major}.{minor}.{patch + 1}"
    sys.exit(f"Unknown bump '{bump}' (use patch|minor|major)")


def main():
    args = sys.argv[1:]

    if args and args[0] == "--next-version":
        print(next_version(args[1] if len(args) > 1 else "patch"))
        return

    if args and args[0] == "--gradle-version":
        if len(args) < 2:
            sys.exit("Usage: ci-metadata.py --gradle-version <opensearch-version>")
        osv = args[1]
        line_file = ROOT / "release" / f"os{osv.split('.')[0]}.properties"
        if not line_file.exists():
            sys.exit(f"No release manifest for OpenSearch {osv} (expected {line_file})")
        print(gradle_version_for(load_props(line_file), osv))
        return

    line = args[0] if args else "os3"
    mode = args[1] if len(args) > 1 else "--summary"
    tier = os.environ.get("VALIDATION_TIER", "full")

    if mode == "--build-matrix":
        print(json.dumps(build_matrix(line)))

    elif mode == "--release-compat-matrix":
        print(json.dumps(release_compat_matrix(line)))

    elif mode == "--validation-matrix":
        print(json.dumps(validation_matrix(line, tier)))

    elif mode == "--github-output":
        lines = []
        if line != "all":
            meta = line_meta(line)
            lines += [f"{k}={v}" for k, v in meta.items()]
            lines.append("build_versions_json=" + json.dumps(csv(meta["build_versions"])))
            lines.append("test_versions_json=" + json.dumps(csv(meta["test_versions"])))
        lines.append("validation_matrix_json=" + json.dumps(validation_matrix(line, tier)))
        text = "\n".join(lines) + "\n"
        gh_output = os.environ.get("GITHUB_OUTPUT")
        if gh_output:
            with open(gh_output, "a") as fh:
                fh.write(text)
        else:
            sys.stdout.write(text)

    elif mode == "--shell":
        if line == "all":
            sys.exit("--shell requires a single line (os2|os3)")
        for key, value in line_meta(line).items():
            print(f"{key.upper()}={value}")

    elif mode == "--summary":
        if line == "all":
            print(f"Lines: os2 + os3 (tier={tier})")
        else:
            for key, value in line_meta(line).items():
                print(f"{key}: {value}")
        print(f"Validation rows: {len(validation_matrix(line, tier))}")
        print(f"Build rows: {len(build_matrix(line))}")

    else:
        sys.exit("Usage: [VALIDATION_TIER=fast|full] ci-metadata.py <os2|os3|all> "
                 "[--github-output|--validation-matrix|--build-matrix|--summary|--shell]  "
                 "|  ci-metadata.py --next-version <patch|minor|major>")


if __name__ == "__main__":
    main()
