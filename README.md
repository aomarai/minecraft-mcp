# minecraft-mcp

An MCP (Model Context Protocol) server for Minecraft: Java Edition that is designed to be
**drop-in**, **mod-friendly**, and **as loader/version agnostic as possible**.

> Status: **scaffold / pre-alpha**. The repo currently contains the protocol-independent core
> interfaces, the build skeleton, and the roadmap. No runnable server is published yet.

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
  the `GameAccess` port; the core imports no Minecraft classes.

See [docs/architecture.md](docs/architecture.md) for the module boundaries, threading contract,
and adapter design. See [docs/security.md](docs/security.md) for the threat model.

## Target matrix (initial)

| Target | Minecraft | Java | Notes |
|---|---|---|---|
| Fabric | 1.21.1 | 21 | modpack anchor |
| Fabric | 26.2, 26.3 | 25 | current line, unobfuscated |
| NeoForge | 1.21.1 | 21 | modern Forge ecosystem |
| NeoForge | 26.2, 26.3 | 25 | current line |
| Universal (RCON) | any | 21+ | no mod install; reduced tools |
| Quilt | 1.21.x | 21 | Fabric jar expected to run; not separately tested at first |

The matrix is intentionally narrow at the start. Widening to every 1.21.x patch is tracked as
an issue, not promised here.

## Security defaults

- Bind to `127.0.0.1` only.
- Generated bearer token, stored `0600`, never printed to logs.
- Read-only tools enabled; write and admin tools require explicit config flags.
- Destructive commands require a confirmation argument from the MCP client.
- Every tool call is written to a JSONL audit log.
- In-game chat is treated as untrusted input: it can never authorize a command.

Do not expose the HTTP endpoint to the internet without TLS and an authenticated reverse proxy
(or the OAuth 2.1 flow). See [docs/security.md](docs/security.md).

## Repository layout

```text
core/                 loader-agnostic protocol, tools, policy, audit
adapters/fabric/      Fabric mod adapter (placeholder)
adapters/neoforge/    NeoForge mod adapter (placeholder)
adapters/rcon/        protocol-only fallback (placeholder)
stdio-shim/           stdio-to-HTTP shim for stdio-only MCP hosts (placeholder)
docs/                 architecture, security, version matrix
```

## Development

Requirements: JDK 21+ (JDK 25 for 26.x adapter nodes, added later).

```bash
./gradlew build
./gradlew :core:test
```

The Gradle wrapper is committed. CI runs the same build on every push and pull request.

## Roadmap

Phases, sequencing, and label conventions live in [docs/roadmap.md](docs/roadmap.md). Live status is
tracked in GitHub Milestones and the epic issues.

## Contributing

PR-only workflow. One issue per PR where practical. See [CONTRIBUTING.md](CONTRIBUTING.md) and
the issue backlog for the roadmap.

## License

MIT. See [LICENSE](LICENSE).
