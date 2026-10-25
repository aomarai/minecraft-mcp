# NeoForge adapter

`adapters/neoforge` is the NeoForge counterpart of the [Fabric adapter](fabric.md). It is built from the
same `core` and shaded and relocated the same way, but is **not yet a supported target**: it only
starts the server and answers `server_info`. A target counts as supported when its Docker smoke test
runs in CI ([version matrix](version-matrix.md)); that test is issue #24, and the tools come with #23.

## What exists

- **Entry point:** `McpNeoForgeMod` (`@Mod("minecraft_mcp")`, dedicated server only) starts the
  `McpController` on `ServerStartedEvent` and stops it on `ServerStoppingEvent`. A configuration or
  start-up problem is logged and never crashes the game. Config and audit files live in NeoForge's
  `config/` directory, with the same file format, defaults and security rules as on Fabric
  ([configuration](configuration.md)).
- **Ports:** `NeoForgeRuntimeInfo` (`server_info` reports `NeoForge <version> / Minecraft <version> / server`).
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
That was a manual check, not yet a CI test.
