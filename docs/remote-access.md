# Remote access

By default the server listens on `127.0.0.1`, so only a client on the same machine can reach it. When the AI runs
somewhere else (the usual case for a hosted or home server), pick one of the four ways below. The bearer token is
required in every one of them: there is no unauthenticated mode.

| Way | Good for | Needs |
|---|---|---|
| [1. HTTPS from the server itself](#1-https-from-the-server-itself) | A server you reach by name or address, no proxy | A certificate in a keystore file |
| [2. An SSH tunnel](#2-an-ssh-tunnel) | You can already `ssh` to the server | Nothing on the server |
| [3. A TLS reverse proxy](#3-a-tls-reverse-proxy) | Already running Caddy or Nginx | The proxy |
| [4. Plain HTTP](#4-plain-http-on-a-trusted-network) | A home LAN or a VPN you trust | Nothing, and the port kept private |

**Why not always plain HTTP:** the token and every command cross the network readable by anyone on the path, and with
`allowWrites` and `allowAdmin` the token can change and stop your server. Over the internet, use 1, 2 or 3.

**Do not** forward the MCP port (`25599`) from your router, and do not publish it from a container, unless it is
HTTPS (1) behind a firewall rule that lets only the AI's address in.

## What the server enforces

- `host` must be a loopback address (`127.0.0.1`, `localhost`, `::1`) unless `allowRemote` is `true`. A non-loopback
  `host` without it is a configuration error that names the key.
- The bearer token is always required.
- The `Host` and `Origin` headers are checked, which is what defeats DNS rebinding (a web page that points its own
  name at your machine). The names accepted are the loopback names, the `host` you bind, and any `allowedHosts` you
  list. **If the AI connects by a name or address, that name or address must be in `allowedHosts`**, unless it is
  exactly your `host`. A request with another name gets `403`.
- TLS is the standard JDK and Jetty implementation: no cryptography is written here. Without `tlsKeystore` the
  endpoint speaks plain HTTP and logs a warning when it listens on the network.

## 1. HTTPS from the server itself

The server reads a **PKCS12 keystore** (a `.p12` file) with its certificate and key, and serves HTTPS.

Make one with the `keytool` that ships with Java. For a name you own, or an address on your network (a self-signed
certificate; see the note on trust below):

```bash
keytool -genkeypair -alias mcp -keyalg RSA -keysize 2048 -validity 825 -storetype PKCS12 \
  -keystore config/minecraft-mcp.p12 -dname "CN=mc.example.com" \
  -ext "san=dns:mc.example.com,ip:192.168.1.5"
```

`keytool` asks for a password (use the same for the key). If you have a certificate from a certificate authority
(for example Let's Encrypt), convert it instead:

```bash
openssl pkcs12 -export -in fullchain.pem -inkey privkey.pem -name mcp -out config/minecraft-mcp.p12
```

Then in `config/minecraft-mcp.json`:

```json
{
  "host": "0.0.0.0",
  "allowRemote": true,
  "allowedHosts": ["mc.example.com", "192.168.1.5"],
  "tlsKeystore": "minecraft-mcp.p12",
  "tlsKeystorePassword": "the password you chose"
}
```

- `tlsKeystore` is found next to the config file when it is a relative path. The file must exist and the password must
  be set, or the server refuses to start and names the key; a password without a keystore is also an error, so a
  typo can never leave you on plain HTTP while you think it is encrypted.
- The password is a secret like the token: the config file is owner-only, the audit log redacts it, and it is never
  printed. You can supply both from the environment (`MINECRAFT_MCP_TLS_KEYSTORE`,
  `MINECRAFT_MCP_TLS_KEYSTORE_PASSWORD`), which keeps them out of the file.
- Apply the change with `/mcp reload` (also after renewing a certificate).
- The client connects to `https://mc.example.com:25599/mcp` with the bearer token.

**Trust.** A self-signed certificate protects against eavesdropping only if the client trusts exactly that
certificate. Many MCP hosts give no way to do that, in which case use a certificate from a certificate authority (the
`openssl` route above), or way 2. The server cannot make an untrusted certificate trusted.

## 2. An SSH tunnel

Leave the server on loopback with the defaults. On the machine where the AI runs, forward the port over SSH:

```bash
ssh -N -L 25599:127.0.0.1:25599 you@mc.example.com
```

and point the client at `http://127.0.0.1:25599/mcp` with the bearer token. The request carries the local address, so
nothing needs `allowRemote` or `allowedHosts`, and the traffic is encrypted by SSH. Nothing is exposed except SSH,
which you are probably already running.

## 3. A TLS reverse proxy

Keep the server on loopback and let a proxy terminate TLS in front of it. The proxy must present the server's own
address as the `Host` header (the server only accepts its own names), and must not buffer streamed responses.

### Caddy

Caddy gets and renews a certificate for the name by itself. Replace `mcp.example.com` with your name (its DNS must
point at the machine, and ports 80 and 443 must reach Caddy):

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
`header_up Host` line a request with the token gets `200` and without it `401`; without that line the same request
gets `403`.

### Nginx

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

## 4. Plain HTTP on a trusted network

On a home LAN or inside a VPN you trust, you can skip TLS: set `host` to the server's address (or `0.0.0.0`),
`"allowRemote": true`, and list the name or address the AI uses in `allowedHosts`. The server starts and logs a
warning that it is reachable over plain HTTP. Keep the port firewalled from everything else. Anyone who can see the
traffic can read the token, so do not do this across the internet.

## Keep the token from leaking

- The token travels in the `Authorization` header on every request. **Never log that header.** Caddy's access log
  redacts it by default; do not add it back. In Nginx do not put `$http_authorization` in any `log_format`.
- Treat the token like a password for the whole game: with `allowWrites` and `allowAdmin` it can change and stop the
  server. Rotate it (`/mcp token rotate`) if it may have been exposed, and keep writes and admin off for any client
  you do not fully trust.
- Prefer limiting who can reach the port as well (firewall, VPN, or an allow-list in the proxy). The token is the only
  login; there are no accounts and no lockout.
- Do not put the endpoint URL together with the token in a screenshot or a repository.

## What is not supported

- MCP's OAuth 2.1 resource-server flow. It needs token validation against an authorization server, which would add a
  JWT library or hand-written crypto. If you need per-user sign-in, put an authenticating proxy in front and keep the
  bearer token behind it.
- Browsers connecting directly from another origin (the `Origin` check refuses them).
- Generating a certificate for you. The JDK has no public way to make one, so use `keytool` as above.

## What has been checked

HTTPS is covered by tests that start the endpoint on a keystore made with `keytool`, connect with a client that
trusts that certificate, and check that plain HTTP and an untrusting client are refused. The Caddy and Nginx recipes
were run in Docker in front of the universal jar. Not checked on a real second machine, an SSH tunnel, or a
certificate from a public certificate authority.
