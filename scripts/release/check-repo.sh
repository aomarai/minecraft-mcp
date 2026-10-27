#!/usr/bin/env bash
# Release hygiene for the repository itself: the MIT license is present and unchanged in kind, and nothing
# that must not be redistributed (server or game jars, world data) is committed. See docs/release-checklist.md.
set -u
cd "$(dirname "$0")/../.."
fail=0
problem() { echo "release check: $*" >&2; fail=1; }

head -1 LICENSE 2>/dev/null | grep -qx 'MIT License' || problem "LICENSE is missing or is not the MIT License"
grep -q 'Permission is hereby granted, free of charge' LICENSE 2>/dev/null || problem "LICENSE does not carry the MIT grant"

# The Gradle wrapper jar is the only binary jar the repository may hold.
jars=$(git ls-files | grep -Ei '\.(jar|war)$' | grep -vx 'gradle/wrapper/gradle-wrapper.jar')
[ -z "$jars" ] || problem "jars are committed (Mojang and server code must not be redistributed): $jars"

world=$(git ls-files | grep -Ei '(^|/)(level\.dat(_old)?|eula\.txt|server\.properties|usercache\.json|session\.lock)$|\.(mca|mcr)$|(^|/)(world|world_nether|world_the_end|playerdata)/' )
[ -z "$world" ] || problem "server or world data is committed: $world"

# The jars write their config (with the generated token) and audit log into the folder they run from.
configs=$(git ls-files | grep -E '(^|/)minecraft-mcp[^/]*\.json$' | grep -Ev '/src/test/resources/')
[ -z "$configs" ] || problem "a generated config file (it holds a bearer token) is committed: $configs"

exit $fail
