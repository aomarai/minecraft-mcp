# Remote access

By default the server listens on `127.0.0.1` and only a client on the same machine can reach it. This page
is for when the AI client runs somewhere else. There is **one supported way**: keep the server on loopback and
put a **TLS reverse proxy** in front of it. Plain HTTP across a network is not supported, because the bearer
token and every command would cross it readable by anyone on the path.

> **Do not** forward the MCP port (`25599`) from your router, and do not publish it from a container. The
> only thing that should face the network is the proxy's HTTPS port.

## What the server enforces

- `host` must be a loopback address (`127.0.0.1`, `localhost`, `::1`) unless `allowRemote` is `true`. Setting
  `host` to `0.0.0.0` or a LAN address without it is a configuration error that names the key, on every start
  and `/mcp reload`. With `allowRemote` set, the server starts and logs a warning that it is reachable over
  plain HTTP.
- The bearer token is always required. There is no unauthenticated mode and no way to turn the token off.
- The `Host` and `Origin` checks stay on. They allow `localhost`, `127.0.0.1`, `[::1]` and the configured bind
  host on the real listening port, which is what defeats DNS rebinding. **They are why the recipes below
  rewrite the `Host` header**: a proxy normally forwards the public name, and the server rightly refuses it
  (`403`).
- No cryptography is implemented here. TLS, certificates and renewals are the proxy's job.

You normally do not need `allowRemote` at all: the proxy runs on the same machine and talks to
`127.0.0.1:25599`. Use it only if the proxy must reach the server over a private network (for example,
containers on a private bridge). Then bind to that private interface address, not `0.0.0.0`, firewall the port
so only the proxy can reach it, and set `"allowRemote": true` (or `MINECRAFT_MCP_ALLOW_REMOTE=true`). A server
bound to an interface address accepts requests whose `Host` is that address and port.

## Caddy

Caddy gets and renews a certificate for the name by itself. Replace `mcp.example.com` with your name (its DNS
must point at the machine, and ports 80 and 443 must reach Caddy):

```caddyfile
mcp.example.com {
	reverse_proxy 127.0.0.1:25599 {
		# The server only accepts its own address as Host; present it as the proxy's upstream.
		header_up Host {upstream_hostport}
		# Do not hold back streamed (SSE) responses.
		flush_interval -1
	}
}
```

Then point the client at `https://mcp.example.com/mcp` with the same bearer token. Tested: with the
`header_up Host` line a request with the token gets `200` and without it `401`; without that line the same
request gets `403`.

## Nginx

Get a certificate (for example with `certbot`) and use it in a server block:

```nginx
server {
    listen 443 ssl;
    server_name mcp.example.com;
    ssl_certificate     /etc/letsencrypt/live/mcp.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/mcp.example.com/privkey.pem;

    location /mcp {
        proxy_pass http://127.0.0.1:25599;
        proxy_http_version 1.1;
        # The server only accepts its own address as Host.
        proxy_set_header Host 127.0.0.1:25599;
        proxy_set_header Connection "";
        # Streamed (SSE) responses must not be buffered or cut off.
        proxy_buffering off;
        proxy_read_timeout 1h;
    }
}
```

Tested the same way: `200` with the token, `401` without.

## Keep the token from leaking

- The token travels in the `Authorization` header on every request. **Never log that header.** Caddy's access
  log redacts it by default; do not add it back. In Nginx do not put `$http_authorization` in any `log_format`.
- Do not add a second layer that terminates and re-sends requests while logging bodies or headers.
- Treat the token like a password for the whole game: with `allowWrites` and `allowAdmin` it can change and
  stop the server. Rotate it (`/mcp token rotate`) if it may have been exposed, and keep writes and admin off
  for any client you do not fully trust.
- Prefer limiting who can reach the HTTPS port as well (firewall, VPN, or an allow-list in the proxy). The
  token is the only login; there are no accounts and no lockout.
- Do not put the proxy's URL with the token in a screenshot or a repository.

## What is not supported

- Plain `http://` to a non-loopback address.
- MCP's OAuth 2.1 resource-server flow. It needs token validation against an authorization server, which
  would add a JWT library or hand-written crypto; the proxy-plus-bearer-token setup above is the supported path.
  If you need per-user sign-in, put an authenticating proxy in front (for example one that requires your
  identity provider's login), and keep the bearer token behind it.
- Browsers connecting directly from another origin (the `Origin` check refuses them).
