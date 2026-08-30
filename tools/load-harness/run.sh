#!/usr/bin/env bash
# The load harness runner. Compiles tools/load-harness against the reactor's own output and runs it.
#
# Why a script and not a Maven module: the harness must not become a reactor module. A module would
# put its own compile and its own dependency resolution into every `mvn install` on the repo, and
# ADR-0001's module graph is inward-only — a module that depends on eir-application's TEST classes
# (which is where SyntheticBook lives, so that the generator is compiled and asserted by the build)
# would be a new and ugly edge in that graph. So this is javac plus java, with no dependency of its
# own beyond what the reactor already built.
#
# Usage:
#   tools/load-harness/run.sh <size>[,<size>...] [harness options...]
#   HEAP=10g tools/load-harness/run.sh 1000000 --no-replay
#
# Prerequisite:
#   mvn -B -o install -DskipTests -pl eir-domain,eir-calc,eir-policy,eir-gl,eir-application
#   (-DskipTests still COMPILES the tests, which this needs; -Dmaven.test.skip would not.)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${HERE}/../.." && pwd)"
OUT="${HERE}/target/classes"
HEAP="${HEAP:-4g}"

MODULES=(eir-domain eir-calc eir-policy eir-gl eir-application)
CP=""
for module in "${MODULES[@]}"; do
  if [[ ! -d "${ROOT}/${module}/target/classes" ]]; then
    echo "missing ${module}/target/classes — run the mvn install above first" >&2
    exit 1
  fi
  CP="${CP}${ROOT}/${module}/target/classes:"
done
# eir-application's TEST classes carry SyntheticBook and Archetype: one source for the population,
# compiled and asserted by the ordinary build rather than living only in a tool.
if [[ ! -d "${ROOT}/eir-application/target/test-classes" ]]; then
  echo "missing eir-application/target/test-classes — SyntheticBook lives there" >&2
  exit 1
fi
CP="${CP}${ROOT}/eir-application/target/test-classes:"
# big-math: eir-calc's fractional powers at 28 significant digits (ADR-0002).
BIGMATH="$(find "${HOME}/.m2/repository/ch/obermuhlner/big-math" -name 'big-math-*.jar' | head -1)"
if [[ -z "${BIGMATH}" ]]; then
  echo "big-math jar not found in the local repository" >&2
  exit 1
fi
CP="${CP}${BIGMATH}"

rm -rf "${OUT}"
mkdir -p "${OUT}"
javac -encoding UTF-8 -nowarn -cp "${CP}" -d "${OUT}" \
  "${HERE}"/src/main/java/com/crisil/eir/tools/load/*.java

# No -XX:+HeapDumpOnOutOfMemoryError: an OOM here is a finding to report, not a defect to debug,
# and at the heap sizes a 1M-contract close needs the dump is several gigabytes of nothing anybody
# will read. The harness's own retained-bytes-per-contract figure is the number that matters.
#
# -Xms == -Xmx so a growing heap is not mistaken for a growing working set, and
# -XX:+AlwaysPreTouch so the first pass of the loop is not paying for page faults the later ones
# do not. Both make the timing figures comparable between sizes, which is the whole question.
exec java \
  -Xms"${HEAP}" -Xmx"${HEAP}" \
  -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
  -XX:+AlwaysPreTouch \
  -cp "${OUT}:${CP}" \
  com.crisil.eir.tools.load.LoadHarness "$@"
