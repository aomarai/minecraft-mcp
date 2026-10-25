# NeoForge adapter

`adapters/neoforge` is the NeoForge counterpart of the [Fabric adapter](fabric.md). It is built from the
same `core` and the same `adapters/common` vanilla code, and shaded and relocated the same way, so every
tool behaves identically. A target counts as supported when its
Docker smoke test runs in CI ([version matrix](version-matrix.md)); it does for NeoForge 1.21.1, 26.2 and 26.3.

## What exists

- **Entry point:** `McpNeoForgeMod` (`@Mod("minecraft_mcp")`, dedicated server only) starts the
  `McpController` on `ServerStartedEvent` and stops it on `ServerStoppingEvent`. A configuration or
  start-up problem is logged and never crashes the game. Config and audit files live in NeoForge's
  `config/` directory, with the same file format, defaults and security rules as on Fabric
  ([configuration](configuration.md)).
- **Ports:** `NeoForgeRuntimeInfo` (`server_info` reports `NeoForge <version> / Minecraft <version> / server`).
  Everything else (players, blocks, registries, entities, world state, permissions, inventories, the
  log, commands, events) is the shared code in `adapters/common`, the same classes Fabric uses.
- **Events:** `NeoForgeEventPublisher` feeds chat, joins, leaves and player deaths from NeoForge's event
  bus into the shared event buffer.
- **Command:** `/mcp status|reload|token rotate` is registered through `RegisterCommandsEvent`.
- **Metadata:** `META-INF/neoforge.mods.toml`, with the NeoForge and Minecraft version ranges taken from
  the node's `gradle.properties`.

## Build

```bash
./gradlew :adapters:neoforge:1.21.1:build   # or :26.2: / :26.3: (those need JDK 25)
```

The loadable jar is `adapters/neoforge/versions/1.21.1/build/libs/minecraft-mcp-<version>+1.21.1-neoforge.jar`
(shaded; the `-dev` jar will not load). The build uses ModDevGradle with Mojang's official names, and
Stonecutter for the version node (see the version matrix, "Build tooling").

## Testing

```bash
scripts/smoke/run-in-docker.sh neoforge adapters/neoforge/versions/1.21.1/build/libs/minecraft-mcp-<version>+1.21.1-neoforge.jar
```

The script is the one Fabric uses (`scripts/smoke/smoke.sh <fabric|neoforge>`); only the server setup and
launch differ. It installs the NeoForge server named in the node's `gradle.properties` in a throwaway
container, starts it with the jar, and runs about 120 checks: auth, `initialize`/`tools/list`, every read
tool, the policy refusals, typed writes, audit and file permissions, the `/mcp` commands, and a clean
shutdown through MCP. It fails if the mod does not load (checked by running it with a jar that is not the
mod) or if any check fails. Running it sets `eula=true` in that disposable server, which accepts the
Minecraft EULA for the test only.

Joins, chat and deaths on NeoForge have not been exercised with a player yet (Fabric has a game test for
that; NeoForge has no equivalent test yet).
