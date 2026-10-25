# Version and loader matrix

## Support policy

- **Primary:** the latest Minecraft release line plus the most-used modpack anchor.
- **Secondary:** older patch releases on a best-effort basis.
- **Not supported initially:** legacy Forge (1.20.1 and older), Bedrock Edition.

Each supported target gets its own artifact. There is no single jar that covers every loader or
every Minecraft version.

## Status

A target counts as supported only when its Docker smoke test runs in CI. Until then it is listed
here as planned. Current state:

| Target | State |
|---|---|
| Fabric 1.21.1 | Supported (node `adapters/fabric/versions/1.21.1`). CI starts a real headless server in Docker with the jar and checks loading, auth, reads, a write, the policy refusals, audit and shutdown (`scripts/smoke/run-in-docker.sh`) |
| NeoForge 1.21.1 | Scaffolded (`adapters/neoforge/versions/1.21.1`, [docs/neoforge.md](neoforge.md)): every tool works (the Fabric smoke suite passes against it by hand). Not supported until its Docker smoke test runs in CI (#24) |
| Everything else | Planned |

## Initial targets

| Loader | Minecraft | Java | Artifact |
|---|---|---|---|
| Fabric | 1.21.1 | 21 | `minecraft-mcp-<version>+1.21.1-fabric.jar` |
| Fabric | 26.2 | 25 | `minecraft-mcp-<version>+26.2-fabric.jar` |
| Fabric | 26.3 | 25 | `minecraft-mcp-<version>+26.3-fabric.jar` |
| NeoForge | 1.21.1 | 21 | `minecraft-mcp-<version>+1.21.1-neoforge.jar` |
| NeoForge | 26.2 | 25 | `minecraft-mcp-<version>+26.2-neoforge.jar` |
| NeoForge | 26.3 | 25 | `minecraft-mcp-<version>+26.3-neoforge.jar` |
| Universal | any | 21+ | `minecraft-mcp-<version>-universal.jar` |

Quilt: expected to load the Fabric artifact; not separately tested at first. Documented as
best-effort.

## Build tooling

- **Stonecutter** (0.9.x) builds every Minecraft version of a loader from one source tree, with
  `//? if` comments for the few places versions differ. One *controller* per loader
  (`adapters/<loader>/`: `stonecutter.gradle.kts`, the shared `src/` and `build.gradle.kts`) and one
  *node* per version (`adapters/<loader>/versions/<minecraft>/gradle.properties`).
- **First-party loader tooling per loader** (Fabric Loom, and ModDevGradle for NeoForge), not a wrapper
  plugin. The original plan named Modstitch, an abstraction over those two. It is still marked
  unstable and does not state 26.x support; the first-party tools are what every version of the game
  is supported through first, so a wrapper would be one more thing to wait for on each new Minecraft
  release. The shared code is already shared where it matters: `core/` has no loader code at all, and
  each adapter is a few small classes.
- Gradle toolchains: Java 21 for 1.21.x nodes, Java 25 for 26.x nodes.
- Mappings: Mojang official names on 26.x; Yarn or Mojmap on 1.21.x.

## Compatibility rules

- Declare Minecraft and loader version ranges in metadata; fail with a clear message when the
  range does not match.
- Keep the mixin surface tiny and version-gated. Prefer loader APIs and registry access.
- Do not hardcode vanilla block/item/entity ids. Resolve by registry id and enumerate dynamically.
- Test each target in CI with a headless server. A README claim is not a support guarantee.

## Adding a target

1. Add a node: one `version("<minecraft>")` line in `settings.gradle.kts` and one
   `adapters/<loader>/versions/<minecraft>/gradle.properties` with its Minecraft, loader, API and Java
   versions (copy the 1.21.1 file). The build script is shared, so nothing else is copied.
2. Add a Docker smoke test that starts a headless server and exercises one read and one write.
3. Add the artifact to the release matrix and document it.
4. Update this table and the README.
