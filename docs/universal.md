# Universal tier (RCON)

The universal tier is for servers that cannot or will not load a mod: a standalone Java process
that talks to the game server over RCON, the Query protocol and its log file. It has no Minecraft
dependency and runs on Java 21 or later. It offers a reduced tool set (commands, players, log and
events) with the same policy gate and audit log as the mods.

This is under construction ([epic #5](https://github.com/aomarai/minecraft-mcp/issues/5)).
Today the RCON client, the Query client, the log tailer and the tool profile exist; the standalone jar and
the tests against real servers follow in #30.

## RCON client (`adapters/rcon`)

`RconClient` implements the `CommandRunner` port, so a command reaches the game exactly like a
command typed at its console, and modded commands work.

- **One command at a time**, on a private daemon thread, so callers never block on a socket. The
  connection is made on first use and reused.
- **Timeouts and failures** are results, never exceptions: a timeout, a dropped connection or a wrong
  password is a failed `CommandResult` with a clear message. After any of them the connection is
  closed and the next command starts a fresh one, so a late reply cannot be mistaken for the next
  command's answer. A timeout says the command "may still have taken effect".
- **Size limit:** the game accepts at most 1446 bytes of command in one packet (1460 bytes including the
  framing). A longer command is refused up front with an error that names the size; it is never split,
  because half a command is not a command.
- **Long replies:** the game splits replies over 4096 bytes into several packets. The client sends an
  empty packet of an unknown type after each command; the game answers it right after the real reply,
  which marks the end.

## Query client (`QueryClient`)

Asks the server who is online over the Query protocol, a small UDP exchange (a handshake that returns a
challenge number, then a "full stat" request carrying it). It returns the server's own description, its
version, the map name, the player count and maximum, and the names of the players. It sends no password and
changes nothing. Everything the server reports is untrusted text. A server that is silent (Query is off, or
the port is wrong) is an error that says so, and every timeout is bounded.

## Log tailer (`LogTailer`)

A server with no mod cannot announce what players do, so the tailer follows `logs/latest.log` and turns
chat, joins and leaves into events for the same event feed the mods use.

- It starts at the end of the file, so old history is not replayed, and reads only whole lines.
- When the game restarts, the log is replaced by a new one. The tailer notices (the file is shorter, or has a
  different identity on file systems that have one) and reads the new log from its start.
- It understands the vanilla, Paper and modded line formats (extra bracketed logger names, Paper's chat thread,
  `[Not Secure]`). Names are matched as real player names (letters, digits, underscore, at most 16), and the
  event is built from the player's name and what they said only: the address in a login line is never read.
- A line that only looks like an event (a player typing `Steve joined the game` into chat) is chat. Chat is
  untrusted and marked so by the event buffer, which also cleans and shortens it.
- One event the receiver cannot take, or one bad read, never stops the tailer or costs the lines after it. Each
  poll reads at most 1 MiB, so a log that suddenly grows cannot stall the server or exhaust memory, and a
  single unfinished "line" that long is skipped rather than waited for.
- Death messages are not turned into events: they are free text that cannot be told apart from other server
  messages reliably.

## Tool profile (`UniversalServices`)

The tier offers exactly the tools its ports can serve, and no others: the core's catalog advertises a tool
only when its capability is present, so an unsupported tool is absent from `tools/list`, not stubbed to fail.
What is offered depends on what the process was told about the server (`UniversalConfig`):

| Given | Tools |
|---|---|
| RCON only | `server_info`, `run_command`, `run_admin_command`, `fill_blocks`, `teleport`, `give_item`, `send_message`, `kick_player`, `ban_player`, `save_world`, `stop_server` |
| plus the server folder | adds `read_log` and `read_events` (chat, joins, leaves) |
| plus the server folder and the Query port | adds `list_players` |

`list_players` needs both on purpose. Query gives the names; where each player is and which dimension they
are in come from the vanilla `data get entity` command (so modded dimensions work); and whether they are an
operator comes from the server's own `ops.json`. Without the folder the tier cannot know who is an operator,
so it does not offer the tool rather than report a guess. A name from the network is untrusted and goes into a
command, so only a real player name (letters, digits, underscore, at most 16) is ever used, and at most 100
players are listed.

Not offered, because RCON can run commands but cannot read the world: `query_blocks`, `list_registry`,
`list_entities`, `get_world_state`, `list_permissions`, `get_inventory` (and `take_screenshot`, which is
client-only). Every tool the universal tier offers is also a tool the mods offer, with the same name and
arguments. There is no TPS tool; `run_command` with `tps` works on servers that have the command.

`server_info` reports `universal/RCON / Minecraft <version> / server`. The version comes from Query when it is
available (and only if it looks like a version), otherwise it says `unknown`.

## Security

- **The password** is `rcon.password` from `server.properties`. It is a secret: it is never part of
  `toString()`, a log line or an error message (tests check each).
- **RCON is plain text.** The password and every command cross the network unencrypted, so the host
  must be this machine (loopback) unless `allowRemote` is set on purpose. A remote host without it is
  refused when the configuration is built.
- **RCON is the console.** The game itself will not refuse any command sent this way, unlike the
  mods, whose commands run at a permission level that stops admin commands unless admin access is on.
  In this tier the core's policy gate is the only barrier. It runs before anything reaches the client:
  read-only by default, writes and admin behind their flags, and admin commands behind confirmation.

## Enabling RCON on the game server

In `server.properties`:

```properties
enable-rcon=true
rcon.port=25575
rcon.password=<a long random password>

# optional: player names, version and map over Query
enable-query=true
query.port=25565
```

Query listens on UDP, on the same port number as the game unless `query.port` says otherwise.

Keep the RCON port closed to the network (firewall it, or bind the game server's RCON to loopback) and
run this process on the same machine. The log tailer needs to read the server's `logs/latest.log`, so it only
works on the same machine too.
