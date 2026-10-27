# Architecture

## Goals

1. **Drop-in:** a single jar in `mods/` (Fabric/NeoForge) or a single runnable jar (universal).
2. **Mod-friendly:** never hardcode vanilla content; resolve everything by registry id and
   inherit mod commands through Brigadier.
3. **Loader/version agnostic:** one codebase, per-loader and per-version artifacts produced by a
   build matrix. No single universal binary claim.
4. **Safe by default:** read-only unless the operator opts in; no AI inherits OP.

## Layers

```text
MCP host (Claude, Codex, Cursor, ...)
        |  Streamable HTTP (JSON-RPC 2.0)
  protocol core (core/)
        |  tool registry, policy, audit, transport
  GamePort capability ports (core/)
        |
  +-----+----------------+-----------------+
  |                      |                 |
Fabric adapter      NeoForge adapter    RCON adapter
(game JVM)          (game JVM)          (standalone process)
```

Dependencies point inward. `core` must not import Fabric, NeoForge, Bukkit, or Minecraft classes.
Adapters depend on `core`, never the reverse.

The Fabric and NeoForge adapters share one more layer. Minecraft's own classes are the same on both
loaders (Mojang names), so everything that needs only vanilla classes lives once in `adapters/common`
and is compiled into each loader jar: the readers and the command runner behind the ports, the `/mcp`
command, and `McpHost`, which owns the server lifecycle, the event buffer and the choice of ports. A
loader adapter is then a few classes: its entry point, its `RuntimeInfo`, and the callbacks that
publish events. Anything that needs a loader API stays in that loader's module; `adapters/common` must
never import `net.fabricmc` or `net.neoforged`.

## Core responsibilities

- MCP server lifecycle and Streamable HTTP transport.
- Tool catalog and JSON schemas (see `docs/tools.md`), including annotations (`readOnlyHint`, `destructiveHint`,
  `idempotentHint`).
- Policy decisions: scopes (`OBSERVE`, `ACT`, `ADMIN`), command allow/deny lists, confirmation.
- Audit logging (JSONL) and rate limiting.
- Configuration parsing and token generation.
- Capability advertisement per side (server vs client).

## Server and client sides

Every tool runs on one side, `SERVER` (a dedicated or integrated server) or `CLIENT` (a game client),
and needs one capability. `ToolCatalog.availableFor` offers a tool only when it is on the adapter's
side *and* the adapter provides its capability, so a dedicated server never advertises screenshot or
UI tools and a client never advertises server administration. The Fabric mod asks the loader which
environment it is in and loads on both (`fabric.mod.json`: `"environment": "*"`): a dedicated or
integrated server serves the `SERVER` tools, and the game client runs its own endpoint for the `CLIENT` tools
(screenshot, screens, the local player; `docs/fabric.md`, "In a game client").

## Adapter responsibilities

- Implement the capability ports (`CommandRunner`, `PlayerReader`, `WorldReader`,
  `RuntimeInfo`; more are added per tool group) that the platform can serve, and marshal all
  game access onto the main thread. An adapter never has to stub a capability it lacks.
- Register loader entrypoints and metadata (`fabric.mod.json`, `neoforge.mods.toml`).
- Expose loader-specific lifecycle events (server started, tick, chat, player join/leave).
- Provide registry access (blocks, items, entities, dimensions) without assuming vanilla.
- Supply optional client-side tools only when running on a client.

## Threading contract

The HTTP transport runs on its own threads. The game does not.

- Server adapters: dispatch with `MinecraftServer.execute(...)`.
- Client adapters: dispatch with `Minecraft.getInstance().execute(...)`.
- Every tool call has a timeout (`callTimeout`, default 8 s). On expiry the core cancels the
  future and returns an MCP error result. Adapters must check `isCancelled()` before starting
  queued work and must not apply partial writes. A write that was already running cannot be
  stopped, so a timed-out write reports "may still have taken effect" and that message is
  audited; the effect is never silent.
- Every call is timed: how long it held the main thread and how long it took in all (see the audit log in
  `docs/security.md`). More than one tick (50 ms) of main-thread work in one call is flagged as an overrun.
- **What a timeout does and does not do.** The timeout bounds how long the *caller waits*, not how long the work
  takes. Work the game thread has not started is cancelled and never runs. Work it has already started cannot be
  stopped and runs to the end, so the caller can be told "timed out" while the server is still busy for a moment
  longer. The message says which of the two it was ("it had not started and will not run", or "it was already running
  on the game thread" and, for a call that can change the world, "so it may still have taken effect"), and the
  audit log records the same text. Work that is split into steps between ticks cancels between steps; that is the only
  place a timeout genuinely stops work.
- Read tools may snapshot on the main thread and serialize on the HTTP thread.
- No adapter may block the main thread waiting for an MCP client.

Bulk operations must be bounded (block volume cap, entity count cap) and should support a
dry-run mode that validates before applying.

## Version and loader strategy

- **Stonecutter** builds each loader's adapter for every Minecraft version from one source tree: a
  controller per loader and a node per version (`docs/version-matrix.md`). Fabric uses Loom and
  NeoForge uses ModDevGradle, directly; one artifact per loader and version.
- Start with 1.21.1 and 26.2/26.3; widen deliberately.
- Mixins are a last resort. Prefer loader APIs and registry access. Any mixin must be
  version-gated and kept small.
- 26.x is unobfuscated; 1.21.x still needs Yarn or Mojmap remapping.

## Failure modes to design against

- Dependency conflicts: shade and relocate Jackson, servlet container, and logging libraries.
- Classloader surprises in Fabric Knot / NeoForge module layers.
- Off-thread world access causing corruption.
- Tool calls that exceed a tick budget and freeze the server.
- Assuming vanilla registry names and breaking on modpacks.
