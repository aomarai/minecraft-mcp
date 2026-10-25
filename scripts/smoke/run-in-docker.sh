#!/usr/bin/env bash
# Runs the server smoke test inside a clean container, so it needs nothing but Docker on the host:
# no local Java, and the server, its world and the Minecraft EULA acceptance all disappear with the
# container.
#
#   scripts/smoke/run-in-docker.sh <fabric|neoforge> <path-to-mod-jar> [node-gradle.properties]
#
# The node (its Minecraft, loader and Java versions) defaults to the loader's 1.21.1 node. The
# container's Java is the node's java_version. The repository is mounted read-only. The smoke
# script does everything else inside the container.
set -eu

LOADER="${1:?usage: run-in-docker.sh <fabric|neoforge> <mod-jar> [node-gradle.properties]}"
JAR="${2:?usage: run-in-docker.sh <fabric|neoforge> <mod-jar> [node-gradle.properties]}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
NODE="${3:-adapters/$LOADER/versions/1.21.1/gradle.properties}"
[ -f "$JAR" ] || { echo "mod jar not found: $JAR" >&2; exit 2; }
[ -f "$ROOT/$NODE" ] || { echo "node properties not found: $ROOT/$NODE" >&2; exit 2; }
JAVA="$(sed -n 's/^java_version=//p' "$ROOT/$NODE" | tr -d '\r')"
[ -n "$JAVA" ] || { echo "no java_version in $NODE" >&2; exit 2; }
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
  "eclipse-temurin:$JAVA-jre-noble" \
  bash -c 'apt-get update -qq && apt-get install -y -qq --no-install-recommends curl >/dev/null && bash scripts/smoke/smoke.sh "$1" "$2" "$3"' _ "$LOADER" "$JAR_IN_CONTAINER" "/work/$NODE"
