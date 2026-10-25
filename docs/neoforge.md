# NeoForge adapter

`adapters/neoforge` is the NeoForge counterpart of the [Fabric adapter](fabric.md). It is built from the
same `core` and the same `adapters/common` vanilla code, and shaded and relocated the same way, so every
tool behaves identically. It is **not yet a supported target**: a target counts as supported when its
Docker smoke test runs in CI ([version matrix](version-matrix.md)), which is issue #24.

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
./gradlew :adapters:neoforge:1.21.1:build
```

The loadable jar is `adapters/neoforge/versions/1.21.1/build/libs/minecraft-mcp-<version>+1.21.1-neoforge.jar`
(shaded; the `-dev` jar will not load). The build uses ModDevGradle with Mojang's official names, and
Stonecutter for the version node (see the version matrix, "Build tooling").

## Verified so far

The jar was started on a real NeoForge 21.1.265 dedicated server in a disposable container: the mod
loaded, the endpoint answered `initialize`, `tools/list` and `server_info`, and the server stopped cleanly.
That was a manual check, not yet a CI test. The complete Fabric smoke script (about 120 checks: auth,
every read tool, policy refusals, typed writes, audit, `/mcp` commands, shutdown) was then run against the
NeoForge jar with only the server setup swapped, and passed. Joins, chat and deaths on NeoForge have not
been exercised with a player yet.
