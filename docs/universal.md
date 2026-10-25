# Universal tier (RCON)

The universal tier is for servers that cannot or will not load a mod: a standalone Java process
that talks to the game server over RCON, the Query protocol and its log file. It has no Minecraft
dependency and runs on Java 21 or later. It offers a reduced tool set (commands, players, log and
events) with the same policy gate and audit log as the mods.

This is under construction ([epic #5](https://github.com/aomarai/minecraft-mcp/issues/5)).
Today only the RCON client exists; the Query client, the log tailer, the tool profile and the
standalone jar follow in #28, #29 and #30.

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

In `server.properties` (the exact flags for Query follow with #28):

```properties
enable-rcon=true
rcon.port=25575
rcon.password=<a long random password>
```

Keep the RCON port closed to the network (firewall it, or bind the game server's RCON to loopback) and
run this process on the same machine.
