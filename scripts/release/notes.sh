#!/usr/bin/env bash
# Prints the part of the release notes that is not generated from commits: the supported matrix and the known
# limitations, taken from docs/version-matrix.md so the notes cannot drift from the documented truth.
set -eu
doc="$(dirname "$0")/../../docs/version-matrix.md"
# Links in the document are relative to docs/; in a release page they must point at the repository.
repo="${GITHUB_REPOSITORY:-aomarai/minecraft-mcp}"
section() {
  awk -v h="## $1" '$0 == h {on = 1; next} /^## / {on = 0} on' "$doc" |
    sed "s#](\([a-z-]*\.md\))#](https://github.com/$repo/blob/main/docs/\1)#g"
}

echo "## Supported targets"
section Status
section Targets
echo "## Known limitations"
section "Known limitations"
