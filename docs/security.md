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

## Policy gate and audit

Every tool call goes through `ToolDispatcher`:

1. The policy decides from the tool's scope and, for command tools, the command text.
   Disabled writes or admin deny the call. Destructive commands need admin plus `confirm=true`,
   and through `run_command` they are refused outright (use `run_admin_command`).
2. The decision is appended to the audit log **before** anything runs. If the entry cannot be
   written the call is refused.
3. Only an allowed, confirmed call reaches a game port. Denied and unconfirmed calls never do.
4. The outcome of an allowed call is appended as a second entry.

The audit log is JSONL, one object per line: `ts`, `phase` (`decision` or `result`), `tool`,
`arguments`, `decision` (`allow`, `deny`, `confirm`) and `detail`. The bearer token is replaced
by `[redacted]` in every string, and strings over 256 characters are truncated.

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
