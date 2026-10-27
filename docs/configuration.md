# Configuration

`ConfigLoader` reads a JSON file and applies environment-variable overrides. The file lives at
`config/minecraft-mcp.json` for the in-game adapters (the adapter chooses the path).

## First run

If the file does not exist it is created with secure defaults and a freshly generated random
bearer token (256 bits, URL-safe base64). If the file exists but has no `token`, one is
generated and added without touching the other keys.

The file is restricted to its owner before the token is written (mode `0600` on POSIX; on Windows an ACL that
grants only the owner) and is re-restricted on every load. If the file system supports neither, or refuses, the
file keeps the access its folder gives it and the log gets one warning naming the file, so check that folder.

## Keys

| Key | Env override | Default | Notes |
|---|---|---|---|
| `host` | `MINECRAFT_MCP_HOST` | `127.0.0.1` | Must be loopback unless `allowRemote` is set; see `docs/remote-access.md` |
| `allowRemote` | `MINECRAFT_MCP_ALLOW_REMOTE` | `false` | `true` or `false`. Lets `host` be a non-loopback address. Without `tlsKeystore` that is plain HTTP and logs a warning |
| `allowedHosts` | `MINECRAFT_MCP_ALLOWED_HOSTS` | `[]` | Extra names or addresses clients may use to reach the server (the `Host` header is checked). Bare names, no scheme or port; comma-separated in the variable |
| `tlsKeystore` | `MINECRAFT_MCP_TLS_KEYSTORE` | empty | Path to a PKCS12 keystore (relative to the config file). When set, the endpoint serves HTTPS itself |
| `tlsKeystorePassword` | `MINECRAFT_MCP_TLS_KEYSTORE_PASSWORD` | empty | Its password. A secret: kept out of logs, `toString()` and the audit log. Required with `tlsKeystore`, and an error without it |
| `port` | `MINECRAFT_MCP_PORT` | `25599` | 1-65535 |
| `allowWrites` | `MINECRAFT_MCP_ALLOW_WRITES` | `false` | `true` or `false` |
| `allowAdmin` | `MINECRAFT_MCP_ALLOW_ADMIN` | `false` | `true` or `false` |
| `maxBlockVolume` | `MINECRAFT_MCP_MAX_BLOCK_VOLUME` | `32768` | 1-1000000 |
| `callTimeoutMillis` | `MINECRAFT_MCP_CALL_TIMEOUT_MS` | `8000` | Must be positive |
| `allowedCommands` | `MINECRAFT_MCP_ALLOWED_COMMANDS` | `[]` | Extra commands trusted as plain writes, e.g. `["mymod:status"]`. Comma-separated in the variable. See `docs/security.md` |
| `destructiveCommands` | `MINECRAFT_MCP_DESTRUCTIVE_COMMANDS` | `[]` | Extra commands that need admin plus confirmation. Wins over `allowedCommands` |
| `token` | `MINECRAFT_MCP_TOKEN` | generated | At least 16 characters |

Environment values win over the file and are never written back to it. Command names are single
words (no spaces or slashes), case-insensitive, and the `minecraft:` prefix is ignored. An empty
variable clears the list.

## Audit log retention

The audit log (`minecraft-mcp-audit.jsonl` next to the config) is rotated by size and has no configuration
keys: at 10 MiB it becomes `.1`, older files move up, and `.4` is deleted, so at most five files (about
50 MiB) are kept. See `docs/security.md`.

## Failure behavior

Configuration errors fail fast with a `ConfigException` that names the offending key or
variable. Unknown keys are rejected (a typo such as `allowWrite` must not silently fall back to a
default), as are wrong types, out-of-range values and tokens shorter than 16 characters.

## Token handling

The token is never logged and is redacted from `ConfigLoader.Loaded#toString()`. Do not commit
the config file.
