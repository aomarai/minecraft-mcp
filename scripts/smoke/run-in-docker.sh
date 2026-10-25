#!/usr/bin/env bash
# Runs a server smoke test inside a clean container, so it needs nothing but Docker on the host:
# no local Java, and the server, its world and the Minecraft EULA acceptance all disappear with the
# container.
#
#   scripts/smoke/run-in-docker.sh <fabric|neoforge> <path-to-mod-jar> [node-gradle.properties]
#   scripts/smoke/run-in-docker.sh <vanilla|paper> <path-to-universal-jar>
#
# For a mod, the node (its Minecraft, loader and Java versions) defaults to the loader's 1.21.1 node, and the
# container's Java is the node's java_version. The universal tier runs on Java 21 against an unmodded server.
# The repository is mounted read-only. The smoke script does everything else inside the container.
set -eu

KIND="${1:?usage: run-in-docker.sh <fabric|neoforge|vanilla|paper> <jar> [node-gradle.properties]}"
JAR="${2:?usage: run-in-docker.sh <fabric|neoforge|vanilla|paper> <jar> [node-gradle.properties]}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
[ -f "$JAR" ] || { echo "jar not found: $JAR" >&2; exit 2; }

case "$KIND" in
  fabric|neoforge)
    SCRIPT=smoke.sh
    NODE="${3:-adapters/$KIND/versions/1.21.1/gradle.properties}"
    [ -f "$ROOT/$NODE" ] || { echo "node properties not found: $ROOT/$NODE" >&2; exit 2; }
    JAVA="$(sed -n 's/^java_version=//p' "$ROOT/$NODE" | tr -d '\r')"
    [ -n "$JAVA" ] || { echo "no java_version in $NODE" >&2; exit 2; }
    EXTRA="/work/$NODE"
    ;;
  vanilla|paper)
    SCRIPT=universal-smoke.sh
    JAVA=21
    EXTRA=""
    ;;
  *)
    echo "unknown kind '$KIND' (fabric, neoforge, vanilla or paper)" >&2
    exit 2
    ;;
esac

JAR_ABS="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
case "$JAR_ABS" in
  "$ROOT"/*) ;;
  *) echo "the jar must be inside the repository: $JAR_ABS" >&2; exit 2 ;;
esac
JAR_IN_CONTAINER="/work/${JAR_ABS#"$ROOT"/}"

exec docker run --rm \
  --volume "$ROOT:/work:ro" \
  --workdir /work \
  --memory 3g \
  "eclipse-temurin:$JAVA-jre-noble" \
  bash -c 'apt-get update -qq && apt-get install -y -qq --no-install-recommends curl >/dev/null && bash "scripts/smoke/$1" "$2" "$3" ${4:+"$4"}' _ "$SCRIPT" "$KIND" "$JAR_IN_CONTAINER" "$EXTRA"
