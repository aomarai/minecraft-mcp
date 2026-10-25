# Configuration

`ConfigLoader` reads a JSON file and applies environment-variable overrides. The file lives at
`config/minecraft-mcp.json` for the in-game adapters (the adapter chooses the path).

## First run

If the file does not exist it is created with secure defaults and a freshly generated random
bearer token (256 bits, URL-safe base64). If the file exists but has no `token`, one is
generated and added without touching the other keys.

The file is restricted to its owner before the token is written (mode `0600` on POSIX) and is
re-restricted on every load. On file systems without POSIX modes this is best effort.

## Keys

| Key | Env override | Default | Notes |
|---|---|---|---|
| `host` | `MINECRAFT_MCP_HOST` | `127.0.0.1` | Loopback by default; see `docs/security.md` before changing |
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

## Failure behavior

Configuration errors fail fast with a `ConfigException` that names the offending key or
variable. Unknown keys are rejected (a typo such as `allowWrite` must not silently fall back to a
default), as are wrong types, out-of-range values and tokens shorter than 16 characters.

## Token handling

The token is never logged and is redacted from `ConfigLoader.Loaded#toString()`. Do not commit
the config file.
