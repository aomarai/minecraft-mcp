#!/usr/bin/env bash
# Runs the Fabric smoke test inside a clean container, so it needs nothing but Docker on the host:
# no local Java, and the server, its world and the Minecraft EULA acceptance all disappear with the
# container.
#
#   scripts/smoke/run-in-docker.sh <path-to-mod-jar>
#
# The repository is mounted read-only. The smoke script does everything else inside the container.
set -eu

JAR="${1:?usage: run-in-docker.sh <mod-jar>}"
[ -f "$JAR" ] || { echo "mod jar not found: $JAR" >&2; exit 2; }
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAR_ABS="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"

case "$JAR_ABS" in
  "$ROOT"/*) ;;
  *) echo "the jar must be inside the repository: $JAR_ABS" >&2; exit 2 ;;
esac
JAR_IN_CONTAINER="/work/${JAR_ABS#"$ROOT"/}"

exec docker run --rm \
  --volume "$ROOT:/work:ro" \
  --workdir /work \
  --memory 2g \
  eclipse-temurin:21-jre-noble \
  bash -c 'apt-get update -qq && apt-get install -y -qq --no-install-recommends curl >/dev/null && bash scripts/smoke/fabric-smoke.sh "$1"' _ "$JAR_IN_CONTAINER"
