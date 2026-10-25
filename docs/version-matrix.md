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
| Fabric 1.21.1 | Supported. CI starts a real headless server in Docker with the jar and checks loading, auth, reads, a write, the policy refusals, audit and shutdown (`scripts/smoke/run-in-docker.sh`) |
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

- **Stonecutter** for multi-version source conditionals.
- **Modstitch** for one-codebase Fabric + NeoForge builds with per-loader source sets.
- Gradle toolchains: Java 21 for 1.21.x nodes, Java 25 for 26.x nodes.
- Mappings: Mojang official names on 26.x; Yarn or Mojmap on 1.21.x.

## Compatibility rules

- Declare Minecraft and loader version ranges in metadata; fail with a clear message when the
  range does not match.
- Keep the mixin surface tiny and version-gated. Prefer loader APIs and registry access.
- Do not hardcode vanilla block/item/entity ids. Resolve by registry id and enumerate dynamically.
- Test each target in CI with a headless server. A README claim is not a support guarantee.

## Adding a target

1. Add a Stonecutter/Modstitch node with its Minecraft, loader, and Java versions.
2. Add a Docker smoke test that starts a headless server and exercises one read and one write.
3. Add the artifact to the release matrix and document it.
4. Update this table and the README.
