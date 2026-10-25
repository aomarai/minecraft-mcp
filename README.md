# minecraft-mcp

[![build-and-test](https://github.com/aomarai/minecraft-mcp/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/aomarai/minecraft-mcp/actions/workflows/build.yml)
[![license: MIT](https://img.shields.io/github/license/aomarai/minecraft-mcp)](LICENSE)
[![Java: 21+](https://img.shields.io/badge/Java-21%2B-orange)](docs/architecture.md)
[![Minecraft: 1.21.1 | 26.x](https://img.shields.io/badge/Minecraft-1.21.1%20%7C%2026.x-green)](docs/version-matrix.md)
[![status: pre-release](https://img.shields.io/badge/status-pre--release-yellow)](docs/roadmap.md)

An MCP (Model Context Protocol) server for Minecraft: Java Edition that is designed to be
**drop-in**, **mod-friendly**, and **as loader/version agnostic as possible**.

> Status: **pre-release**. Fabric and NeoForge mods (1.21.1, 26.2, 26.3) and the universal jar all run and
> are tested against real servers in CI; no release has been published yet.

**Install and connect a client: [docs/install.md](docs/install.md).**

## Why this exists

Existing Minecraft MCP servers each leave a gap:

- Some embed a bridge in a mod but require a separate Node/TypeScript MCP process.
- Some are pure Java but target a single Minecraft version and a single loader.
- Some are Paper/Spigot plugins (they belong in `plugins/`, not `mods/`).
- Some are external bots that can play but cannot administer a server.

This project's goal is the open niche: **one Java artifact you drop into `mods/`**, with no
sidecar, covering Fabric and NeoForge from one codebase, shipping a multi-version matrix, with
security-first defaults and a no-mod fallback for servers that cannot install a mod.

## Architecture at a glance

Two tiers, one core:

- **Tier A - bridge mod (deep):** a Fabric or NeoForge mod that embeds the MCP server in the
  game JVM. Full registry, block, entity, command, event, and client-UI access. One jar per
  loader and Minecraft version.
- **Tier B - universal bridge (wide):** a standalone Java process that speaks RCON plus the
  Query protocol and log tailing. Works on vanilla, Paper, Forge, Fabric, or anything else that
  can enable RCON. Reduced, command-centric tool set.
- **Shared core:** protocol handling, tool schemas, policy engine, audit log. Adapters implement
  the capability ports (`CommandRunner`, `PlayerReader`, `WorldReader`, `RuntimeInfo`); the core imports no Minecraft classes.

See [docs/architecture.md](docs/architecture.md) for the module boundaries, threading contract,
and adapter design. See [docs/security.md](docs/security.md) for the threat model.

## Target matrix

| Target | Minecraft | Java | Notes |
|---|---|---|---|
| Fabric | 1.21.1 | 21 | modpack anchor |
| Fabric | 26.2, 26.3 | 25 | current line, unobfuscated |
| NeoForge | 1.21.1 | 21 | modern Forge ecosystem |
| NeoForge | 26.2, 26.3 | 25 | current line |
| Universal (RCON) | any | 21+ | no mod install; reduced tools |
| Quilt | 1.21.x | 21 | Fabric jar expected to run; not separately tested at first |

The matrix is intentionally narrow. A target counts as supported only when its smoke test runs in CI;
see [docs/version-matrix.md](docs/version-matrix.md) for the state and the known limitations.

## Server and client tools

Tools are tied to a side. A dedicated server offers server tools (reading the world, running
commands, administration) and never client-only ones; a game client would offer only its own (client tools
are the post-v1 epic). See [docs/tools.md](docs/tools.md) and [docs/architecture.md](docs/architecture.md).

## Security defaults

- Bind to `127.0.0.1` only.
- Generated bearer token, stored `0600`, never printed to logs.
- Read-only tools enabled; write and admin tools require explicit config flags.
- Destructive commands require a confirmation argument from the MCP client.
- Every tool call is written to a JSONL audit log.
- In-game chat is treated as untrusted input: it can never authorize a command.

Do not expose the HTTP endpoint to the internet. To reach it from another machine, put a TLS reverse
proxy in front of it: [docs/remote-access.md](docs/remote-access.md). See also
[docs/security.md](docs/security.md).

## Repository layout

```text
core/                 loader-agnostic protocol, tools, policy, audit
adapters/fabric/      Fabric mod adapter (Stonecutter nodes under versions/)
adapters/neoforge/    NeoForge mod adapter (Stonecutter nodes under versions/)
adapters/common/      vanilla-only code both mod adapters compile in
adapters/rcon/        universal tier: standalone jar for servers without a mod (RCON, Query, log)
stdio-shim/           stdio-to-HTTP shim for stdio-only MCP hosts
docs/                 install, configuration, security, tools, version matrix, releasing, remote access
```

## Development

Requirements: JDK 21 and JDK 25 (25 builds the 26.x adapter nodes; Gradle runs on 21).

```bash
./gradlew build
./gradlew :core:test
```

The Gradle wrapper is committed. CI runs the same build on every push and pull request.

## Roadmap

Phases, sequencing, and label conventions live in [docs/roadmap.md](docs/roadmap.md). Live status is
tracked in GitHub Milestones and the epic issues.

## Documentation

| Need | Read |
|---|---|
| Install and connect a client | [docs/install.md](docs/install.md) |
| Config keys and the token | [docs/configuration.md](docs/configuration.md) |
| Tools | [docs/tools.md](docs/tools.md) |
| Reach it from another machine | [docs/remote-access.md](docs/remote-access.md) |
| No-mod servers | [docs/universal.md](docs/universal.md) |
| Threat model | [docs/security.md](docs/security.md) |
| Releasing | [docs/release.md](docs/release.md), [docs/release-checklist.md](docs/release-checklist.md) |

## Contributing

PR-only workflow. One issue per PR where practical. See [CONTRIBUTING.md](CONTRIBUTING.md) and
the issue backlog for the roadmap.

## License

MIT. See [LICENSE](LICENSE).
