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
- A 404 on a request that carried a session id means the session ended, because the server restarted or was
  reloaded (`/mcp reload`, `/mcp token rotate`). The shim forgets the id and answers that error, so the host
  must reconnect and send `initialize` again; the next request goes out without the old id. A 404 on a request
  without a session id still says the URL is wrong.
- A 404 on a request that carried a session id means the session ended, because the server restarted or was
  reloaded (`/mcp reload`, `/mcp token rotate`). The shim forgets the id and answers that error, so the host
  must reconnect and send `initialize` again; the next request goes out without the old id. A 404 on a request
  without a session id still says the URL is wrong.
- The token is never logged and never put in an error message.

## The jar

Releases include `minecraft-mcp-<version>-stdio-shim.jar`, a standalone jar that runs on Java 21 or later.
A host launches it as a stdio server:

```bash
MINECRAFT_MCP_TOKEN='<the token>' java -jar minecraft-mcp-<version>-stdio-shim.jar
```

CI starts the universal jar and talks to it through this jar the way a stdio host does
(`scripts/smoke/shim-smoke.sh`). The SDK has no logging backend here, so the first line on stderr is an
SLF4J "no providers" notice; stdout carries only MCP messages. Host-specific snippets are in
[install.md](install.md).

## Messages the server sends on its own

After `initialize` succeeds the shim opens the server's optional message stream (`GET` with the session id and
`Accept: text/event-stream`) on a background thread and writes each message to stdout as one line, like a reply.
This is how `notifications/resources/updated` for the subscribed `minecraft://events` resource reaches a stdio
host. Writes to stdout are serialized, so a notification never shares a line with a reply. When the stream ends
or fails, the shim opens it again after 1 s, doubling up to 30 s (a working stream starts the delay over). It
stops quietly when the server answers `405` (it offers no stream) or `404` (the session ended). Failures go to
stderr, never to the host.

## Limits

- Server-initiated *requests* (not notifications) are relayed to the host, but the host's answer to one is a
  message the shim posts like any other; that path is not smoke tested.
