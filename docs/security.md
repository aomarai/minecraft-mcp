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
- Auth: generated bearer token in `config/minecraft-mcp.json`, file mode `0600`. See `docs/configuration.md`.
- Writes: disabled (`allowWrites=false`).
- Admin commands: disabled (`allowAdmin=false`).
- Destructive commands: require `confirm=true` in the tool call, even when admin is enabled.
- Audit: JSONL file with timestamp, tool, arguments, decision, result summary.

## Authentication and request filtering

`BearerAuthFilter` sits in front of the whole server. There is no unauthenticated mode: `McpCore`
cannot be built without a token of at least 16 characters.

1. **Host (403):** the `Host` header must be `localhost`, `127.0.0.1`, `[::1]` or the configured bind
   host, with the real listening port. This defeats DNS rebinding. Wildcard bind addresses
   (`0.0.0.0`, `::`) are never accepted as a Host.
2. **Origin (403):** if an `Origin` header is present it must be `http://` plus one of those
   authorities. Browsers send `Origin` on cross-site requests, so a web page cannot drive the
   server; MCP clients that are not browsers send none. Browser-based MCP clients served from
   another origin are therefore unsupported.
3. **Bearer token (401):** `Authorization: Bearer <token>` is the only accepted form, compared in
   constant time. A failure returns a bare `401` with `WWW-Authenticate: Bearer` and no detail.

Rejected requests never reach the MCP transport. They are logged with the remote address only,
never the header or token, and are not written to the audit file, so an anonymous caller cannot
fill it. There is no lockout; a 256-bit generated token is not guessable, and the server binds to
loopback by default. Rotating the token requires a restart of the transport.

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
- Rotate the token with `/mcp token rotate`. It writes a new token to the config file and restarts the
  endpoint, which invalidates the old token at once. The token is never printed or logged. See
  `docs/fabric.md`.

## Reporting

See [SECURITY.md](../SECURITY.md). Do not open a public issue for an exploitable problem.
