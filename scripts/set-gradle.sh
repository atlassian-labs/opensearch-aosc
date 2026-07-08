#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# set-gradle.sh — point the Gradle wrapper at the Gradle version required to build a given
# OpenSearch version.
#
# OpenSearch build-tools pins a minimum Gradle version and older build-tools break on newer
# Gradle, so no single wrapper serves every line: 2.x needs Gradle 8.x, while 3.7 requires
# >=9.4.1 (and Gradle 9 needs JVM 17+, which os3's JDK 21 already satisfies). This rewrites
# gradle/wrapper/gradle-wrapper.properties, which drives BOTH the CLI (./gradlew) and the
# IntelliJ IDE (it reads the wrapper properties directly). Work on one OpenSearch line per
# session; run this when you cross the 8.x<->9.x boundary (i.e. when working on 3.7), then re-sync
# the IDE. CI runs it per matrix job before ./gradlew.
#
# Usage:
#   ./scripts/set-gradle.sh <opensearch-version>   # e.g. 3.7.0 -> Gradle 9.4.1
#   ./scripts/set-gradle.sh --reset                # restore the committed default (Gradle 8.7)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPS="${ROOT}/gradle/wrapper/gradle-wrapper.properties"
# Committed default; covers OpenSearch 2.15-3.6. Mirrors the gradle_version default in the manifests.
DEFAULT_GRADLE="8.7"

arg="${1:-}"
if [[ -z "${arg}" ]]; then
  echo "usage: set-gradle.sh <opensearch-version> | --reset" >&2
  exit 2
fi
if [[ "${arg}" == "--reset" ]]; then
  gradle_version="${DEFAULT_GRADLE}"
else
  gradle_version="$("${ROOT}/scripts/ci-metadata.py" --gradle-version "${arg}")"
fi

# Rewrite distributionUrl, preserving Gradle's canonical escaped ':' form. Use a Python
# function-replacement so the backslash is written literally (sed/regex-replacement would eat it).
PROPS="${PROPS}" GRADLE_VERSION="${gradle_version}" python3 - <<'PY'
import os, re
props = os.environ["PROPS"]
url = "https\\://services.gradle.org/distributions/gradle-%s-all.zip" % os.environ["GRADLE_VERSION"]
text = open(props).read()
text, n = re.subn(r"^distributionUrl=.*$", lambda _m: "distributionUrl=" + url, text, flags=re.M)
if n != 1:
    raise SystemExit("set-gradle: expected exactly one distributionUrl line, found %d" % n)
open(props, "w").write(text)
PY
echo "set-gradle: gradle-wrapper.properties -> Gradle ${gradle_version} (for ${arg})"
