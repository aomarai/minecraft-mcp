# Fabric adapter

`adapters/fabric/` is a Fabric mod that runs the MCP server inside a dedicated Minecraft server (and, in a game
client, the integrated server and the client's own endpoint; see below).

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

## In a game client

The same jar also loads in a Fabric game client (`"environment": "*"`). In single-player, the integrated server
serves the tools below on port 25599 exactly as a dedicated server does. The client itself runs a **second,
separate endpoint** for the tools only a client can serve, so the two never share a port, token or audit log:

| | Integrated server | Client |
|---|---|---|
| Tools | everything in this document | `take_screenshot` |
| Config | `config/minecraft-mcp.json` | `config/minecraft-mcp-client.json` |
| Audit log | `config/minecraft-mcp-audit.jsonl` | `config/minecraft-mcp-client-audit.jsonl` |
| Default port | 25599 | 25598 |
| Environment variables | `MINECRAFT_MCP_*` | `MINECRAFT_MCP_CLIENT_*` |

The client endpoint has the same secure defaults (loopback, generated token, owner-only file). Its log line is
`MCP client server listening on port 25598`; it starts when the game has finished starting and stops with it.
`take_screenshot` returns the game window as one PNG, scaled down to fit 1280 x 720 and 2 MiB, held in memory
only. The frame is read on the render thread and encoded on another, so the game does not wait for the encoder.

**State:** captured from a real client on Minecraft 1.21.1 and 26.2 (a virtual display in Docker). There is no CI
test for the client yet (issue #39), so by the project's own rule the client is not listed as a supported target.

## What it provides

All five tools in `docs/tools.md` are served.

| Tool | How |
|---|---|
| `server_info` | `Fabric <loader> / Minecraft <version> / server` |
| `list_players` | Name, UUID, dimension id, position and operator status of connected players |
| `query_blocks` | Blocks in a bounded box of one dimension, as registry ids (`minecraft:stone`, `create:cogwheel`). Air, positions outside the world and unloaded chunks are skipped, and chunks are never loaded or generated. The box is capped by `maxBlockVolume` and the result by `limit` (`truncated` says when more exist) |
| `list_registry` | Any registry by id: the built-in ones (blocks, items, entity types, ...) and the server's dynamic ones (biomes, dimension types, ...). Sorted, searchable, paged (at most 200 per page). Whatever a mod registers appears with no code naming it. An unknown registry is reported with the available ones |
| `list_entities` | Entities within 1-64 blocks of a point in a dimension, nearest first, with their type as a registry id. Loaded chunks only; nothing is loaded or generated. A freshly spawned entity can take a moment to appear while its chunk finishes loading |
| `get_world_state` | A dimension's game time, day time, weather, difficulty and every game rule, enumerated from the game so mod rules are included |
| `list_permissions` | The operator list (name, uuid, level) and whitelist from the server's own lists. Names come from the profile cache; a uuid the server has never seen is `unknown` |
| `get_inventory` | An online player's items by slot, as registry ids and counts (modded items included). Not-online players are an error naming them |
| `read_log` | The tail of `logs/latest.log`, read without loading the file (at most the last 256 KB). Written by the loader-agnostic `FileLogReader` in `core`; the Fabric module only supplies the path |
| `read_events` | The newest chat, join, leave and player-death events, fed from Fabric API callbacks (`ServerMessageEvents`, `ServerPlayConnectionEvents`, `ServerLivingEntityEvents`) on the game thread into a bounded buffer owned by the mod, so events survive `/mcp reload` and token rotation. The same feed is the subscribable `minecraft://events` resource |
| `fill_blocks`, `teleport`, `give_item`, `send_message`, `kick_player`, `ban_player`, `save_world`, `stop_server` | Typed write and admin tools. Each builds one fixed command from validated arguments and runs it through the same Brigadier dispatcher (`docs/tools.md`, "Typed tools"). `fill_blocks` and `teleport` target a dimension with `execute in <dimension> run ...` |
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

## Operator commands

Three subcommands for operators. They need vanilla permission level 4 (the server console always has
it); anyone else gets "You do not have permission to use /mcp (operator level 4 is required)".

| Command | What it does |
|---|---|
| `/mcp status` | Host, port, access (`read-only`, `writes enabled`, `writes and admin enabled`) and the number of tools. Never the token |
| `/mcp reload` | Re-reads `config/minecraft-mcp.json` and restarts the endpoint with it, so every setting applies, including the policy. The new configuration is checked first: an invalid one is refused with the reason and the running server is left alone. Current MCP sessions end and clients reconnect; the token is unchanged |
| `/mcp token rotate` | Writes a fresh random token to the config file and restarts the endpoint. **The old token stops working immediately**, so clients must be updated. The new token is never printed or logged: read it from `config/minecraft-mcp.json` (owner-only) |

If the token comes from `MINECRAFT_MCP_TOKEN`, the environment supplies it and rotation is refused
with that explanation: change the variable instead. The work runs off the game thread, so a restart
cannot stall the server.

## Build

```bash
./gradlew :adapters:fabric:1.21.1:build
```

The loadable jar is `adapters/fabric/versions/1.21.1/build/libs/minecraft-mcp-<version>+1.21.1-fabric.jar`. It is
the remapped, shaded jar; the other jars in that folder are intermediate and will not load.

- **Shading:** the core and its libraries (MCP SDK, Jackson, Jetty, Reactor, ...) are packed into
  the mod and relocated under `dev.aomarai.minecraftmcp.shaded`, so they cannot clash with other
  mods or the game. SLF4J is left out because the game provides it.
- **Clean JVM exit:** the MCP SDK starts its session sweeper on a non-daemon Reactor thread and does
  not stop it on close, which kept the JVM alive after the game server had stopped. `McpCore` makes
  Reactor's threads daemons (`DaemonSchedulers`; it affects only this mod's relocated copy). The
  smoke test checks that the server process exits after `stop`, within seconds, like an unmodded server.
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
scripts/smoke/smoke.sh fabric adapters/fabric/versions/1.21.1/build/libs/minecraft-mcp-<version>+1.21.1-fabric.jar
```

It needs bash, curl, Java 21 and network access, works in a throwaway directory, and checks:
loading, the token, `initialize`/`tools/list`, reads, a write that takes effect, the policy
refusals, the confirm flow, audit and file permissions, and a clean shutdown driven through MCP.
Running it sets `eula=true` in that disposable server, which accepts the Minecraft EULA for the
test only.

CI runs the same script in a clean container so the host needs nothing but Docker; the server,
world and EULA acceptance disappear with the container:

```bash
scripts/smoke/run-in-docker.sh fabric adapters/fabric/versions/1.21.1/build/libs/minecraft-mcp-<version>+1.21.1-fabric.jar
```

Fake players cannot join a headless server from outside, so they are tested in-process with Fabric's
game test framework (`adapters/fabric/src/gametest`):

```bash
./gradlew :adapters:fabric:1.21.1:runGameTest
```

It starts a real dedicated server with the mod loaded and places a fake player through the player list
over an in-memory channel, exactly as a connecting client is placed. The test checks that joining,
chatting, dying and leaving are recorded as events, in order, and that the player and inventory readers
see the player. `eula = true` in `build.gradle.kts` accepts the Minecraft EULA for that disposable
development server only. CI runs it before the Docker smoke test.

The smoke test takes about two minutes. It fails if the mod does not load (it gives up 30 seconds after
the game reports it is ready) or if any check fails, and prints the server log. The server versions
come from the node's `adapters/fabric/versions/1.21.1/gradle.properties`, which the script reads.
