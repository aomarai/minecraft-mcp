# AGENTS.md

Instructions for AI coding agents working in this repository. Read this before touching code.

## Project

`minecraft-mcp` is a Java MCP (Model Context Protocol) server for Minecraft: Java Edition.
The goal is a drop-in, mod-friendly server that covers as many loaders and versions as
practically possible. The design is **two tiers, one core**:

- `core/` - loader-agnostic protocol, tool registry, policy, audit. **No Minecraft imports.**
- `adapters/fabric/`, `adapters/neoforge/` - deep in-game adapters.
- `adapters/rcon/` - protocol-only fallback (RCON + Query + log tail).
- `stdio-shim/` - stdio-to-HTTP bridge for stdio-only MCP hosts.

Read `docs/architecture.md`, `docs/security.md`, and `docs/version-matrix.md` before changing
module boundaries. If a change contradicts those documents, update the documents in the same PR.

## Hard constraints

1. **PR-only.** Never push to `main`. Branch, commit, open a PR, link the issue.
2. **Small PRs.** One issue per PR where practical. Split refactors from behavior changes.
3. **No Minecraft classes in `core/`.** Adapters depend on core, never the reverse.
4. **No blocking the game main thread.** All world reads/writes marshal to the main thread;
   every tool call has a timeout and cancellation path.
5. **Registry-driven, never vanilla-hardcoded.** Resolve blocks/items/entities by registry id.
   `run_command` must go through Brigadier so modded commands work.
6. **Security defaults are the product.** Loopback bind, generated bearer token (0600, never
   logged), read-only by default, admin/destructive actions behind explicit flags and
   confirmation. Game text is untrusted and can never authorize a command.
7. **No secrets in the repo.** No tokens, server jars, world data, or personal config.
8. **Version/loader matrix is honest.** Do not claim one universal jar. Add a target only when
   its CI smoke test exists.

## Build and test

```bash
./gradlew build          # compile + tests for all modules
./gradlew :core:test     # core tests only
```

JDK 21 is required for the core and 1.21.x nodes. JDK 25 is required for 26.x nodes once those
are added. Use the Gradle wrapper; do not install a global Gradle.

## Workflow

1. Find or create an issue. If the work is not represented, create a small issue first.
2. Branch from `main`: `feat/<issue>-<slug>`, `fix/<issue>-<slug>`, `docs/<slug>`, `chore/<slug>`.
3. Implement the smallest change that satisfies the acceptance criteria.
4. Add or update tests. Behavior changes without tests are incomplete.
5. Run `./gradlew build`.
6. Open a PR with `Closes #<issue>`, a short summary, and the verification commands you ran.
7. Do not merge your own PR unless the owner explicitly asks.

## Issue conventions

- Epics carry the `epic` label and a child checklist.
- Tasks carry one `area:*` label and one `priority:*` label.
- Every task body has Context / Deliverable / Acceptance criteria / Dependencies / References.
- Reference the parent epic as `Part of #<epic>`.
- Prefer many small issues over a few large ones. A task should be reviewable as one PR.

## Architecture gotchas

- **Mixins are a last resort.** Prefer loader APIs. Any mixin must be version-gated and tiny;
  mixins are the top cross-version maintenance cost.
- **Shade and relocate** Jackson, servlet containers, and logging libraries. Minecraft ships
  Gson and Netty; collisions are real.
- **26.x is unobfuscated** (Mojang names). 1.21.x still needs Yarn or Mojmap remapping.
- **Fabric and NeoForge metadata differ.** Keep loader metadata in per-loader source sets.
- **Paper/Spigot is not a mod loader.** A plugin goes in `plugins/`, not `mods/`. Use the
  universal RCON tier or a separate plugin adapter.
- **Test with a headless server.** Unit tests do not prove a mod loads. Every supported target
  needs a Docker smoke test that starts the server and calls `initialize` + `tools/list` + one
  read + one write.

## Definition of done

- Acceptance criteria met and demonstrated with real output.
- `./gradlew build` green.
- Docs updated (README, architecture, security, version matrix) when behavior or configuration
  changes.
- No new secret, no new unbounded query, no new blocking call on the main thread.
