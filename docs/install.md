# Install and connect

This page takes a server operator from nothing to an AI client talking to a Minecraft server. Pick the
install for your server, then connect a client. The server side is exercised against real servers in the
project's tests (the log lines and messages below are the real ones); the client snippets follow each host's
documented format and are not run in CI.

**Which one do I need?**

| Your server | Use | Needs |
|---|---|---|
| Fabric (1.21.1, 26.2, 26.3) | the Fabric mod | Fabric Loader and Fabric API |
| NeoForge (1.21.1, 26.2, 26.3) | the NeoForge mod | NeoForge |
| Vanilla, Paper, or anything else you cannot add a mod to | the universal jar | RCON turned on, Java 21+, same machine |

Java: 21 for Minecraft 1.21.1, 25 for 26.x. The universal jar needs Java 21 or later. Pick the file for
your exact loader and Minecraft version from the release; the file name says which
(`minecraft-mcp-<version>+<minecraft>-<fabric|neoforge>.jar`). Check it with `sha256sum -c SHA256SUMS`.

Out of the box the server is **read-only**, listens on **this machine only**, and every request needs a
**generated token**. Nothing below changes that unless it says so.

## Install the Fabric mod

1. Set up a Fabric server for your Minecraft version (the [Fabric installer](https://fabricmc.net/use/server/)
   makes `fabric-server-launch.jar`). Accept the Minecraft EULA yourself in `eula.txt`.
2. Put **Fabric API** (any version for your Minecraft version) and
   `minecraft-mcp-<version>+<minecraft>-fabric.jar` in the server's `mods/` folder.
3. Start the server. In the log, among the usual lines, you should see:

   ```text
   [Server thread/INFO] (minecraft-mcp) MCP server listening on port 25599
   ```

4. The mod has created `config/minecraft-mcp.json` (see [First run](#first-run)).

More detail, and what each tool does on Fabric: [fabric.md](fabric.md).

## Install the NeoForge mod

1. Set up a NeoForge server for your Minecraft version (run the NeoForge installer with `--installServer`)
   and accept the EULA yourself.
2. Put `minecraft-mcp-<version>+<minecraft>-neoforge.jar` in the server's `mods/` folder and start the server.
3. The log shows the same `MCP server listening on port 25599` line, and `config/minecraft-mcp.json` appears.

More detail: [neoforge.md](neoforge.md).

## In single-player

The same jars serve a game client's own world (the integrated server), so you can use the mod without a dedicated
server. Both loaders are smoke-tested this way on 1.21.1, 26.2 and 26.3 ([version-matrix.md](version-matrix.md)).

1. Put the jar for your loader and Minecraft version in the game folder's `mods/` folder (`.minecraft/mods/` for the
   default launcher). On Fabric also put **Fabric API** there; NeoForge needs nothing else.
2. Start the game and open or create a world. The log shows `MCP server listening on port 25599`.
3. The config and token are in the game folder: `.minecraft/config/minecraft-mcp.json`, with the audit log beside
   it. It has the same defaults as on a server (read-only, this machine only, a generated token), and the endpoint
   is `http://127.0.0.1:25599/mcp`. The server stops when you leave the world.
4. **Fabric only:** the game client also runs its own endpoint on port 25598 for the tools only a client can serve
   (screenshots, screens, the local player), with `config/minecraft-mcp-client.json` and its own token. Use
   `/mcpclient status`, `/mcpclient reload` and `/mcpclient token rotate` for it. NeoForge has no client endpoint.
   Details: [fabric.md](fabric.md#in-a-game-client), [neoforge.md](neoforge.md).

## Install the universal jar (no mod)

For vanilla, Paper and the like. It runs as a separate process next to the game server, on the same machine,
and talks to it through RCON, Query and the server's log.

1. In the server's `server.properties`, turn on RCON (and Query if you want the player list), then restart the
   game server:

   ```properties
   enable-rcon=true
   rcon.port=25575
   rcon.password=<a long random password>
   enable-query=true
   query.port=25565
   ```

2. Start the jar with the password, and tell it where the server lives (this is what switches on the log, the
   events and the player list):

   ```bash
   MINECRAFT_MCP_RCON_PASSWORD='<the same password>' \
   MINECRAFT_MCP_QUERY_PORT=25565 \
   MINECRAFT_MCP_SERVER_DIRECTORY=/path/to/the/game/server \
   java -jar minecraft-mcp-<version>-universal.jar /path/to/mcp-config
   ```

   ```text
   Minecraft MCP (universal) listening on 127.0.0.1:25599, read-only, 14 tools
   Configuration: /path/to/mcp-config/minecraft-mcp.json (created; it holds the token, which is not shown here)
   ```

   Keep both ports (RCON 25575 and the game's) closed to the network. Full details, the tool list this mode
   offers, and the security notes: [universal.md](universal.md).

## First run

On the first start the config file is created next to the server:

| | Fabric / NeoForge | Universal |
|---|---|---|
| Config and token | `config/minecraft-mcp.json` in the server folder | `minecraft-mcp.json` in the folder you gave the jar |
| Audit log | `config/minecraft-mcp-audit.jsonl` | `minecraft-mcp-audit.jsonl` next to the config |
| Endpoint | `http://127.0.0.1:25599/mcp` | `http://127.0.0.1:25599/mcp` |

The file is readable only by its owner and holds a random `token`. **The token is never printed or logged.**
Open the file to copy it:

```json
{
  "host": "127.0.0.1",
  "port": 25599,
  "allowWrites": false,
  "allowAdmin": false,
  "token": "<a long random string: copy this into your client, and keep it secret>"
}
```

In the game or at the server console, `/mcp status` says what is running (it never shows the token):

```text
MCP server is listening on 127.0.0.1:25599, read-only, 20 tools
```

- **Let the AI change things:** set `"allowWrites": true` (commands like `say`, `give`, `teleport`, filling a
  region). Set `"allowAdmin": true` as well only if you want it to be able to ban, kick or stop the server,
  and even then every such call needs an explicit confirmation from the client. Then run `/mcp reload`
  (Fabric/NeoForge) or restart the jar.
- **New token:** `/mcp token rotate` writes a fresh one and the old one stops working at once. Update your
  clients afterwards. In a game client, the client endpoint has its own: `/mcpclient reload` and
  `/mcpclient token rotate`.

All the settings: [configuration.md](configuration.md). What is protected and how: [security.md](security.md).

## Connect a client

Every client needs two things: the URL `http://127.0.0.1:25599/mcp` (the client must run on the same machine; plain
HTTP to another machine is deliberately unsupported; for a client on another machine see [remote-access.md](remote-access.md): the server can serve HTTPS itself, or use an SSH tunnel or a TLS proxy) and the token as a bearer token. Below, `<TOKEN>` is the value
from `minecraft-mcp.json`. Do not paste a real token into a screenshot, a chat or a repository.

### Claude Code

```bash
claude mcp add --transport http minecraft http://127.0.0.1:25599/mcp \
  --header "Authorization: Bearer <TOKEN>"
```

Then `/mcp` inside Claude Code shows `minecraft` as connected.

### VS Code (GitHub Copilot) and Cursor

VS Code, in `.vscode/mcp.json` (or your user `mcp.json`):

```json
{
  "servers": {
    "minecraft": {
      "type": "http",
      "url": "http://127.0.0.1:25599/mcp",
      "headers": { "Authorization": "Bearer <TOKEN>" }
    }
  }
}
```

Cursor, in `.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "minecraft": {
      "url": "http://127.0.0.1:25599/mcp",
      "headers": { "Authorization": "Bearer <TOKEN>" }
    }
  }
}
```

### Claude Desktop and other stdio-only hosts

These can only start a program, so they use the stdio shim jar (`minecraft-mcp-<version>-stdio-shim.jar`,
Java 21+), which forwards to the endpoint. In `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "minecraft": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/minecraft-mcp-<version>-stdio-shim.jar"],
      "env": { "MINECRAFT_MCP_TOKEN": "<TOKEN>" }
    }
  }
}
```

The token goes in `env`, never in `args`, so it does not show up in process lists. More: [stdio-shim.md](stdio-shim.md).

Host configuration formats change; if a snippet is rejected, check the host's own MCP documentation for the
current shape. The URL and the `Authorization: Bearer` header are what matter.

### Check it works

Ask the AI something read-only, such as "what is the server info?" or "who is online?". With a plain terminal:

```bash
curl -s -X POST http://127.0.0.1:25599/mcp \
  -H "Authorization: Bearer <TOKEN>" -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
```

A JSON reply naming `minecraft-mcp` means it works. `401` means the token is wrong.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| The server stops at start-up with `Install fabric-api, any version.` | Fabric API is missing. Put it in `mods/`. |
| The mod is rejected with "requires version ... of Minecraft" (or Java) | The jar is for another Minecraft version. Use the file whose name has your version; 26.x also needs Java 25. |
| No `MCP server listening` line, and the log says `MCP server failed to start` with "Address already in use" | Something else uses port 25599. Set a different `port` in `minecraft-mcp.json` (or `MINECRAFT_MCP_PORT`) and restart or `/mcp reload`. The game keeps running without MCP meanwhile. |
| `MCP server not started: ...` naming a key | The config file is invalid. The message names the key (an unknown or misspelled key is rejected on purpose). Fix it; on Fabric/NeoForge `/mcp reload` checks the new file before stopping anything. |
| Client gets `401` | The token is wrong or was rotated. Copy it again from `minecraft-mcp.json`; check there is no extra space or line break. If `MINECRAFT_MCP_TOKEN` is set in the server's environment, that value is the token, not the file. |
| Client gets `403` | The request's `Host` or `Origin` is not this machine. Connect to `127.0.0.1` or `localhost` on the real port; a browser page cannot connect, by design. |
| A tool says it needs "write access" or "admin access" | The server is read-only by default. See [First run](#first-run) to enable it on purpose. |
| A tool says "requires confirmation" | An admin action needs the client to send `confirm=true`. The AI should ask you first. |
| Universal: `RCON authentication failed` | `MINECRAFT_MCP_RCON_PASSWORD` differs from `rcon.password`. Both must match exactly, and the game server must have been restarted after enabling RCON. |
| Universal: `RCON failed: java.net.ConnectException` | RCON is off or on another port. Check `enable-rcon`, `rcon.port`. |
| Universal: `list_players` or the log tools are missing | They need `MINECRAFT_MCP_SERVER_DIRECTORY` (and, for players, `MINECRAFT_MCP_QUERY_PORT` with `enable-query=true`). The tools that cannot work are left out of the list on purpose. |
| Universal: "is enable-query on?" | Query is off or the port is wrong. Check `enable-query` and `query.port` (UDP). |
| The shim prints `SLF4J: No SLF4J providers were found` on stderr | Harmless. Messages for the host go to stdout only. |
