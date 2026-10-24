# Security model

## Threat model

The MCP server gives an AI agent operator-adjacent access to a live game. The realistic threats
are:

1. **Destructive action by mistake** - the model runs `kill`, `fill`, `ban`, or `stop`.
2. **Prompt injection through game text** - a player types instructions in chat and the model
   follows them.
3. **Token leakage** - the bearer token appears in logs, screenshots, or a shared config.
4. **Remote exposure** - the loopback port is forwarded or bound to `0.0.0.0`.
5. **Privilege inheritance** - the AI runs with the server operator's OP level by default.

## Defaults

- Bind host: `127.0.0.1`.
- Auth: generated bearer token in `config/minecraft-mcp.json`, file mode `0600`.
- Writes: disabled (`allowWrites=false`).
- Admin commands: disabled (`allowAdmin=false`).
- Destructive commands: require `confirm=true` in the tool call, even when admin is enabled.
- Audit: JSONL file with timestamp, tool, arguments, decision, result summary.

## Rules

- The AI never inherits the operator's OP. The policy engine is independent of Minecraft
  permissions and runs before any command reaches Brigadier.
- Chat, signs, books, and logs are untrusted. Text arriving from the game cannot authorize a
  command or change policy.
- Remote access requires TLS plus either the MCP OAuth 2.1 resource-server flow or an
  authenticated reverse proxy. Plain HTTP on a public interface is unsupported.
- Destructive bulk tools (`fill`, `clone`, `setblock` loops) must validate volume and support a
  dry-run before applying.
- Rotate the token with `/mcp rotate` and restart the MCP transport.

## Reporting

See [SECURITY.md](../SECURITY.md). Do not open a public issue for an exploitable problem.
