# Fabric adapter

`adapters/fabric/` is a server-side Fabric mod that runs the MCP server inside a dedicated
Minecraft server.

## Requirements

- Minecraft 1.21.1 dedicated server, Java 21.
- Fabric Loader 0.16.0 or newer, and Fabric API (any version). Without Fabric API the loader stops
  with `Install fabric-api, any version.`

## Install

Put `minecraft-mcp-<version>+1.21.1-fabric.jar` and Fabric API in the server's `mods/` folder and
start the server. On the first start the mod creates `config/minecraft-mcp.json` (loopback,
read-only, with a generated bearer token, owner-only) and `config/minecraft-mcp-audit.jsonl`. See
`docs/configuration.md`. The log shows `MCP server listening on port 25599`.

A bad configuration or a port in use is logged and leaves the game server running without MCP; it
never crashes the game.

## What it provides

All five tools in `docs/tools.md` are served.

| Tool | How |
|---|---|
| `server_info` | `Fabric <loader> / Minecraft <version> / server` |
| `list_players` | Name, UUID, dimension id, position and operator status of connected players |
| `query_blocks` | Blocks in a bounded box of one dimension, as registry ids (`minecraft:stone`, `create:cogwheel`). Air, positions outside the world and unloaded chunks are skipped, and chunks are never loaded or generated. The box is capped by `maxBlockVolume` and the result by `limit` (`truncated` says when more exist) |
| `run_command`, `run_admin_command` | Through the game's Brigadier dispatcher, so vanilla and modded commands both work |

- **Main thread:** every read and write is queued with `MinecraftServer.execute` (see `MainThread`
  in `core`). The HTTP threads never touch world state. A call that times out before the game
  thread reaches it is skipped.
- **Registry-driven:** dimensions and blocks are resolved by registry id, never by a hardcoded
  vanilla list. An unknown dimension is reported together with the available ones.
- **Command permission level:** commands run from a dedicated "MCP" source (not the console, not a
  player) at vanilla permission level 2, or level 4 only when `allowAdmin` is on. The core's policy
  decides first; this is a second line of defense, so the game itself refuses admin commands unless
  admin access is enabled.
- **Not covered by a test mod:** that a block from another mod resolves is true by construction
  (the block registry is queried by id), but nothing in CI loads a third-party block yet.

## Build

```bash
./gradlew :adapters:fabric:build
```

The loadable jar is `adapters/fabric/build/libs/minecraft-mcp-<version>+1.21.1-fabric.jar`. It is
the remapped, shaded jar; the other jars in that folder are intermediate and will not load.

- **Shading:** the core and its libraries (MCP SDK, Jackson, Jetty, Reactor, ...) are packed into
  the mod and relocated under `dev.aomarai.minecraftmcp.shaded`, so they cannot clash with other
  mods or the game. SLF4J is left out because the game provides it.
- **Loom version:** Loom 1.17.x is the newest line that runs on JDK 21. Newer Loom needs JDK 25 and
  arrives with the 26.x nodes.
- **Dependency verification:** Loom builds three things on each machine from verified inputs:
  Fabric API remapped to Mojang names (`remapped.*`), the layered Mojang mappings
  (`loom:mappings`) and the merged Minecraft jar (`net.minecraft:minecraft-merged-*`). Their bytes
  differ per machine, so `gradle/verification-metadata.xml` lists exactly those as
  `trusted-artifacts`. Everything that is downloaded (Fabric API, Fabric Loader, Mojang's libraries,
  every Maven dependency) stays pinned by checksum. Verify metadata changes on Linux with a fresh
  Gradle home and `--refresh-dependencies`, which is what CI does.

## Testing

Loader glue cannot be unit tested without a running game, so the line/branch coverage gate is off
for this module. The classes are kept tiny, the logic lives in `core` (where the gate applies:
`MainThread`, `BlockScanner`, `McpService`), and a smoke test starts a real Fabric server with the
jar:

```bash
scripts/smoke/fabric-smoke.sh adapters/fabric/build/libs/minecraft-mcp-<version>+1.21.1-fabric.jar
```

It needs bash, curl, Java 21 and network access, works in a throwaway directory, and checks:
loading, the token, `initialize`/`tools/list`, reads, a write that takes effect, the policy
refusals, the confirm flow, audit and file permissions, and a clean shutdown driven through MCP.
Running it sets `eula=true` in that disposable server, which accepts the Minecraft EULA for the
test only. Issue #20 wires it into CI in Docker.
