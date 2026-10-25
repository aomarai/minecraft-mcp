# stdio shim

Some MCP hosts can only launch stdio servers. The mod speaks Streamable HTTP, so `stdio-shim/`
provides a small bridge: the host launches the shim, the shim forwards each JSON-RPC message to the
mod's `/mcp` endpoint with the bearer token.

## Configuration

Everything comes from the environment, never from arguments, so the token does not show up in
process listings.

| Variable | Default | Notes |
|---|---|---|
| `MINECRAFT_MCP_TOKEN` | required | The token from the mod's config file |
| `MINECRAFT_MCP_URL` | `http://127.0.0.1:25599/mcp` | `http` is only accepted for this machine; anything else must be `https` |
| `MINECRAFT_MCP_TIMEOUT_MS` | `30000` | Connect and per-request timeout |

Configuration errors stop the shim at start-up with a message naming the variable.

## Behavior

- One JSON-RPC message per line on stdin; one compact JSON message per line on stdout. Batches are
  not supported (the MCP spec dropped them).
- Messages are handled in order, so `initialize` completes before the notification that follows it.
  The `Mcp-Session-Id` the server returns is sent on later requests.
- JSON and SSE responses are both relayed, one line per message.
- Anything that goes wrong becomes a JSON-RPC error response for that request's id: server
  unreachable, timeout, HTTP 401/403/404/other, or a response that is not JSON. The shim never
  hangs the host. Diagnostics go to stderr.
- The token is never logged and never put in an error message.

## Limits

- The shim does not open the optional server-to-client SSE stream, so server-initiated requests are
  not relayed yet.
- Packaging as a standalone jar and host-specific configuration snippets are tracked in the install
  documentation issue (#34).
