# Roadmap

The roadmap lives in GitHub Issues and Milestones. This document is the map; the issues are the
source of truth for status.

## Phases

| Milestone | Epics | Outcome |
|---|---|---|
| M0 — Scaffold | — | Repo layout, core interfaces, docs, CI |
| M1 — Protocol core | [#2](https://github.com/aomarai/minecraft-mcp/issues/2) | Runnable MCP core over Streamable HTTP with tool catalog, policy, audit |
| M2 — Fabric adapter | [#3](https://github.com/aomarai/minecraft-mcp/issues/3) | Deep Fabric 1.21.1 integration with a CI smoke test |
| M3 — NeoForge + multi-version | [#4](https://github.com/aomarai/minecraft-mcp/issues/4) | One codebase, Fabric + NeoForge, 1.21.1 and 26.x artifacts |
| M4 — Universal fallback | [#5](https://github.com/aomarai/minecraft-mcp/issues/5) | No-mod RCON/Query tier for vanilla/Paper/modded servers |
| M5 — Security, docs, release | [#6](https://github.com/aomarai/minecraft-mcp/issues/6) | Threat model validated, install docs, release checklist |
| M6 — Client extras | [#7](https://github.com/aomarai/minecraft-mcp/issues/7) | Screen DOM, own-player control, screenshots (post-v1) |

## Sequencing

1. **E1 first.** Every adapter depends on the `GamePort` capability ports, tool schemas, and policy engine.
2. **Fabric before NeoForge.** Smaller loader surface and faster CI loop; the Fabric adapter
   proves the threading and registry model.
3. **Build wiring before more adapters.** Stonecutter should land before the version
   matrix grows, or every later change doubles.
4. **Universal tier in parallel.** It shares the core and gives servers without mod support a
   usable path.
5. **Security and docs continuously.** The threat model must be settled before the first public
   release; install docs grow with each adapter.
6. **Client extras last.** They expand the version matrix and are explicitly not part of the
   server-side v1 promise.

## Labels

- `epic` — umbrella issue.
- `area:core`, `area:fabric`, `area:neoforge`, `area:universal`, `area:security`, `area:docs`,
  `area:ci` — workstream.
- `priority:p0` (blocks first usable release), `priority:p1` (credible v1), `priority:p2`
  (post-v1).
- `version:1.21.1`, `version:26.x` — target line where relevant.

## Working the backlog

Pick a task in the current milestone that is not blocked. Branch from `main`, keep the PR small,
run `./gradlew build`, and close the issue with the PR. See `AGENTS.md` and `CONTRIBUTING.md`.

## Out of scope (for now)

- Legacy Forge (1.20.1 and older).
- Bedrock Edition.
- A single universal jar that covers every loader/version.
- Controlling other players or bypassing anti-cheat.
