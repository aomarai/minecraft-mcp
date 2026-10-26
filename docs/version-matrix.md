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
| Fabric 1.21.1, 26.2, 26.3 | Supported (nodes `adapters/fabric/versions/<minecraft>`). CI starts a real headless server in Docker with each jar and runs the same ~115 checks: loading, auth, reads, writes, the policy refusals, audit and shutdown (`scripts/smoke/run-in-docker.sh`). Fake-player game tests (joins, chat, deaths) run on 1.21.1 only |
| NeoForge 1.21.1, 26.2, 26.3 | Supported (nodes `adapters/neoforge/versions/<minecraft>`, [docs/neoforge.md](neoforge.md)), with the same Docker smoke test per jar |
| Universal (vanilla, Paper and Fabric 1.21.1) | Supported ([universal.md](universal.md)). CI starts a real vanilla server, a real Paper server and a real Fabric server (a modded one) without this project's mod in Docker, runs the standalone jar next to each, and checks protocol, tools, reads, writes, policy refusals, admin and typed tools, the audit log and shutdown (`scripts/smoke/run-in-docker.sh vanilla|paper`). Other servers that speak RCON (Forge, NeoForge, other forks) are expected to work but are not tested |

## Targets

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

## Known limitations

- Fake-player game tests (joins, chat, deaths) run on Fabric 1.21.1 only. Player events on 26.x and on NeoForge are covered by the smoke tests but not yet with a real player.
- The universal jar was tested against vanilla, Paper and a Fabric server. Other servers that speak RCON (Forge, NeoForge, other forks) are expected to work but are not tested.
- The universal tier lists players only when a server directory is configured, and reports no TPS.
- Quilt is expected to load the Fabric jar but is not tested.
- The endpoint speaks plain HTTP on loopback. Remote access is by serving HTTPS from a keystore, an SSH tunnel or a TLS reverse proxy ([remote-access.md](remote-access.md)); the HTTPS path is tested with a self-made keystore but not yet on a second machine or with a public certificate. The OAuth 2.1 flow is not implemented.
- The Fabric client endpoint (the client-only tools: screenshot, screens, local player) is smoke-tested in CI on 1.21.1, 26.2 and 26.3 (`scripts/smoke/client-smoke.sh`, a real client on a virtual display; 26.3 uses a software Vulkan device).
- Publishing to Modrinth and CurseForge is wired into the release workflow but needs the owner's tokens and project ids ([release.md](release.md)); it has not run against the live sites.

## Build tooling

- **Stonecutter** (0.9.x) builds every Minecraft version of a loader from one source tree. One
  *controller* per loader (`adapters/<loader>/`: `stonecutter.gradle.kts`, the shared `src/` and
  `build.gradle.kts`) and one *node* per version (`adapters/<loader>/versions/<minecraft>/gradle.properties`).
- **Version differences live behind one seam, not in `//? if` comments.** Whatever Minecraft renamed or
  reshaped between 1.21.x and 26.x (`ResourceLocation` became `Identifier`, game rules, permissions,
  command sources, ...) is in `GameCompat`, with one implementation per generation:
  `adapters/common/src/compat/legacy` (1.21.x) and `.../modern` (26.x). A node's `compat` property picks
  which one is compiled in, so the readers in `adapters/common/src/main` never name a type that differs
  and read the same on every version. A change in a later 26.x release goes in the `modern` side (or
  becomes a third side) and the smoke test shows whether it worked. The Fabric build also chooses, per
  node, Loom's remapping plugin (`remap=true`, 1.21.x) or its plain plugin (26.x is unobfuscated).
- **First-party loader tooling per loader** (Fabric Loom, and ModDevGradle for NeoForge), not a wrapper
  plugin. The original plan named Modstitch, an abstraction over those two. It is still marked
  unstable and does not state 26.x support; the first-party tools are what every version of the game
  is supported through first, so a wrapper would be one more thing to wait for on each new Minecraft
  release. The shared code is already shared where it matters: `core/` has no loader code at all, and
  each adapter is a few small classes.
- Gradle toolchains: Java 21 for 1.21.x nodes, Java 25 for 26.x nodes. Gradle itself runs on 21, so
  building everything needs both JDKs installed (CI installs both; locally, Gradle finds them in the usual
  places). Without a JDK 25 you can still build the 1.21.1 nodes by task, for example
  `./gradlew :core:build :adapters:fabric:1.21.1:build`, but a plain `./gradlew build` includes the 26.x nodes.
- Mappings: Mojang official names everywhere (1.21.x through Loom's `officialMojangMappings()`, ModDevGradle
  for NeoForge, and nothing to map on 26.x).

## Compatibility rules

- Declare Minecraft and loader version ranges in metadata; fail with a clear message when the
  range does not match.
- Keep the mixin surface tiny and version-gated. Prefer loader APIs and registry access.
- Do not hardcode vanilla block/item/entity ids. Resolve by registry id and enumerate dynamically.
- Test each target in CI with a headless server. A README claim is not a support guarantee.

## Adding a target

1. Add a node: one `version("<minecraft>")` line in `settings.gradle.kts` and one
   `adapters/<loader>/versions/<minecraft>/gradle.properties` with its Minecraft, loader, API, Java and
   `compat` values (copy the closest node's file; `smoke_*` entries name the commands and game rules the
   smoke test uses where Minecraft renamed them). The build script is shared, so nothing else is copied.
2. Build it. If a Minecraft API changed, fix it in the matching `GameCompat` (or add a new compat side for
   a new generation); the smoke test says whether it worked.
3. Add the node to the `smoke` matrix in `.github/workflows/build.yml`. A target counts as supported only
   when that job passes.
4. Document it here and in the README. The release workflow picks the node up by itself ([release.md](release.md)).
