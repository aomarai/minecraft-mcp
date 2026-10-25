#!/usr/bin/env bash
# Checks the built release jars in a directory: the mod metadata carries the release version, and no jar contains
# Minecraft's own classes (Mojang code must not be redistributed). Usage: check-jars.sh <dir> <version>
set -u
dir="$1"; version="$2"
fail=0
problem() { echo "release check: $*" >&2; fail=1; }

for jar in "$dir"/minecraft-mcp-*.jar; do
  name=$(basename "$jar")
  entries=$(unzip -Z1 "$jar")
  if printf '%s\n' "$entries" | grep -Eq '^(net/minecraft/|com/mojang/)'; then
    problem "$name contains Minecraft classes"
  fi
  case "$name" in
    *-fabric.jar)
      # Compared as plain text on purpose: the metadata is generated from the release version by the build.
      unzip -p "$jar" fabric.mod.json | grep -Eq "\"version\"[[:space:]]*:[[:space:]]*\"$version\"" \
        || problem "$name: fabric.mod.json does not say version $version" ;;
    *-neoforge.jar)
      unzip -p "$jar" META-INF/neoforge.mods.toml | grep -Eq "^version[[:space:]]*=[[:space:]]*\"$version\"" \
        || problem "$name: neoforge.mods.toml does not say version $version" ;;
  esac
done
exit $fail
