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

## Untrusted text in tool results

Some read tools return text that players control: entity names (`list_entities`), and the whole
server log (`read_log`). The core never lets such text influence a decision: the policy looks only at
the tool, its arguments and the configuration. The tools mark it anyway, so a client can treat it as
data: the `read_log` result carries `"untrusted": true`, and the descriptions of `list_entities` and
`read_log` say so. `get_inventory` leaves item names out entirely. `read_log` also masks IPv4
addresses and cuts very long lines, so it does not leak players' addresses or flood the client.

## Policy gate and audit

Every tool call goes through `ToolDispatcher`:

1. The policy decides from the tool's scope and, for command tools, the command text. A command is
   one of three kinds, by its first word (case-insensitive, `minecraft:` ignored, so `minecraft:kill`
   is `kill`):
   - **Known safe**: a small built-in list of everyday vanilla commands (`say`, `tell`, `msg`, `w`,
     `me`, `tellraw`, `title`, `list`, `seed`, `help`, `time`, `weather`, `give`, `xp`,
     `experience`, `effect`, `playsound`, `particle`) plus the operator's `allowedCommands`. Needs
     `allowWrites`.
   - **Destructive**: the built-in list (`kill`, `op`, `stop`, `fill`, `setblock`, `tp`, `ban`, ...)
     plus the operator's `destructiveCommands`. Needs admin plus `confirm=true`.
   - **Anything else** (a modded command such as `mymod:wipe_world`, or `execute`, `function`,
     `data`, which can run other commands): treated like a destructive command. It is not known to
     be harmless, so it is never a plain write. The operator lists it in `allowedCommands` to trust it.

   `destructiveCommands` wins over `allowedCommands`, and the built-in destructive list cannot be
   loosened. Through `run_command` a command that needs admin is refused outright; it must go through
   `run_admin_command` with `confirm=true`. Only the configuration can change any of this: game text
   and mod-provided metadata cannot.
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
