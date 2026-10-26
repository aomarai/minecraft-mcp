# Releasing

A release is a Git tag. The `release` workflow (`.github/workflows/release.yml`) builds and tests every
version node, then attaches the jars and their checksums to a GitHub release. The Fabric and NeoForge
jars are also uploaded to Modrinth and CurseForge once their account settings are in place (below).

## Cutting a release

| You do | Result |
|---|---|
| Push a tag `v1.2.3` | Builds everything as version `1.2.3`, then creates the GitHub release `v1.2.3` with the jars, `SHA256SUMS` and generated notes |
| Push a tag `v1.2.3-dryrun` | The same build as `1.2.3`, but **publishes nothing**; the files are kept as a workflow artifact (`release-files`, 7 days) |
| Run the workflow by hand with a version | Always a dry run |

A tag may carry a suffix such as `v1.2.3-rc1`; the version must look like `1.2.3` optionally followed by
`-rc1`, `.1` and the like. The workflow refuses anything else, because the version ends up in file names and
in each jar's mod metadata. Tags matching `v*` are protected by the repository ruleset (no deletion, no
moving), so use a dry run, not a real tag, to try the pipeline.

## What the workflow does

1. **plan**: turns the tag into a version and decides whether this is a dry run.
2. **build**: runs the whole `build-and-test` pipeline (`build.yml`) with that version: unit tests, the 100%
   coverage gate, mutation testing, the Fabric game test, and a Docker smoke test of **every** jar. A failure
   anywhere stops the release.
3. **package**: checks that there is exactly one jar for each loader and version node in the repository,
   named `minecraft-mcp-<version>+<minecraft>-<loader>.jar`, plus the universal jar,
   `minecraft-mcp-<version>-universal.jar`, and the stdio shim, `minecraft-mcp-<version>-stdio-shim.jar`. A
   missing or extra jar fails the release (it fails closed).
   It also runs the release hygiene checks (`scripts/release/`): MIT license, no committed jars or world data,
   each jar's mod metadata says the release version, and no jar contains Minecraft classes. Then it writes
   `SHA256SUMS`. The release notes are GitHub's generated changes plus the supported matrix and known
   limitations from [version-matrix.md](version-matrix.md). The checklist is [release-checklist.md](release-checklist.md).
4. **publish**: only for a real tag. Creates the GitHub release from the tag.
5. **publish-mods**: only for a real tag, after the GitHub release. Runs `scripts/release/publish-mods.py`, which
   uploads each Fabric and NeoForge jar (not the universal or stdio-shim jars, which are standalone programs) to
   Modrinth and CurseForge with the release notes as the changelog. A tag like `v1.2.3` is a release; one with a
   suffix such as `-rc1` is uploaded as a beta. A platform without its settings is skipped with a message.

Because the node list comes from `adapters/*/versions/*`, adding a node (see
[version-matrix.md](version-matrix.md)) adds it to the release with no change here.

## Checking a download

```bash
sha256sum -c SHA256SUMS
```

## Modrinth and CurseForge setup

Create the projects by hand once, then add these to the repository (Settings, Secrets and variables, Actions):

| Platform | Secret | Variable |
|---|---|---|
| Modrinth | `MODRINTH_TOKEN` (a personal access token with the "Create versions" scope) | `MODRINTH_PROJECT_ID` |
| CurseForge | `CURSEFORGE_TOKEN` (an API token from the author settings) | `CURSEFORGE_PROJECT_ID` (the numeric id) |

Preview what would be sent, without tokens: `scripts/release/publish-mods.py <dist> <version> <notes.md> --dry-run`.
The upload code has not yet been run against the live APIs, so check the first release's pages on both sites.

## Not done yet

- A changelog beyond GitHub's generated notes.
