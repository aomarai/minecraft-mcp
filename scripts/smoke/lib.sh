#!/usr/bin/env bash
# Helpers shared by the smoke tests (smoke.sh for the mods, universal-smoke.sh for the universal tier). Source it.
#
# The caller sets PORT (the MCP endpoint's port) and CONFIG_FILE (the MCP config, where the generated token
# is), and keeps TOKEN and SESSION up to date through start_session. FAILURES counts failed checks.

FAILURES=0
TOKEN=""
SESSION=""

fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

# Downloads. The jars they fetch run on CI runners, so every one is fetched with -f (an error page is never saved
# as a jar), named in the message when it cannot be had, and checked against the checksum its publisher gives.
# They exit the script with status 2 (a setup problem, not a failed check).

# download <what> <url> <file>
download() {
  curl -fsSLo "$3" "$2" || { echo "FAIL: cannot download $1 ($2)"; exit 2; }
}

# check_checksum <what> <file> <sha1|sha256> <expected-hex>
check_checksum() {
  local actual
  actual="$("$3sum" "$2" | cut -d' ' -f1)"
  [ -n "$4" ] && [ "$actual" = "$(printf '%s' "$4" | tr 'A-F' 'a-f')" ] \
    || { echo "FAIL: $1 does not match its published $3 checksum (expected '$4', got '$actual')"; exit 2; }
}

# download_maven <what> <url> <file>: a Maven artifact, checked against the .sha256 file next to it.
download_maven() {
  local expected
  download "$1" "$2" "$3"
  expected="$(curl -fsSL "$2.sha256")" || { echo "FAIL: cannot download the checksum of $1 ($2.sha256)"; exit 2; }
  check_checksum "$1" "$3" sha256 "${expected%% *}"
}

# download_minecraft_server <minecraft-version> <file>: Mojang publishes a manifest of versions, each naming its
# server jar and the jar's SHA-1.
download_minecraft_server() {
  local manifest version_url version_json server_url server_sha1
  manifest="$(curl -fsSL https://piston-meta.mojang.com/mc/game/version_manifest_v2.json | tr -d '\n')" \
    || { echo "FAIL: cannot download the Minecraft version manifest"; exit 2; }
  version_url="$(printf '%s' "$manifest" | sed -n "s/.*\"id\": *\"$1\", *\"type\": *\"release\", *\"url\": *\"\\([^\"]*\\)\".*/\\1/p")"
  [ -n "$version_url" ] || { echo "FAIL: Minecraft $1 is not in the manifest"; exit 2; }
  version_json="$(curl -fsSL "$version_url" | tr -d '\n')" || { echo "FAIL: cannot download the version description"; exit 2; }
  server_url="$(printf '%s' "$version_json" | sed -n 's/.*"server": *{[^}]*"url": *"\([^"]*\)".*/\1/p')"
  server_sha1="$(printf '%s' "$version_json" | sed -n 's/.*"server": *{[^}]*"sha1": *"\([^"]*\)".*/\1/p')"
  [ -n "$server_url" ] && [ -n "$server_sha1" ] || { echo "FAIL: cannot find the server jar for $1"; exit 2; }
  download "the Minecraft $1 server" "$server_url" "$2"
  check_checksum "the Minecraft $1 server jar" "$2" sha1 "$server_sha1"
}
pass() { echo "ok:   $1"; }

# expect <label> <haystack> <needle>: fixed-string match, no regex surprises.
expect() {
  if printf '%s' "$2" | grep -qF -- "$3"; then pass "$1"; else fail "$1 (expected to find: $3) got: $(printf '%s' "$2" | tr '
' ' ' | tail -c 400)"; fi
}

# expect_eventually <label> <needle> <id> <tool> <arguments>: a freshly started server needs a moment before
# a new entity is visible to queries (its chunk is still being promoted), so retry for a few seconds.
expect_eventually() {
  local out=""
  for _ in $(seq 1 15); do
    out="$(call "$3" "$4" "$5")"
    if printf '%s' "$out" | grep -qF -- "$2"; then pass "$1"; return; fi
    sleep 1
  done
  fail "$1 (expected to find: $2) got: $(printf '%s' "$out" | tr '\n' ' ' | tail -c 400)"
}

# refute <label> <haystack> <needle>: the opposite of expect.
refute() {
  if printf '%s' "$2" | grep -qF -- "$3"; then fail "$1 (found: $3)"; else pass "$1"; fi
}

mcp() { # mcp <json> [extra curl args]: POST a JSON-RPC message with the token and session
  curl -s --max-time 30 -X POST "http://127.0.0.1:$PORT/mcp" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" ${SESSION:+-H "Mcp-Session-Id: $SESSION"} -d "$1"
}
call() { # call <id> <tool> <arguments-json>
  mcp "{\"jsonrpc\":\"2.0\",\"id\":$1,\"method\":\"tools/call\",\"params\":{\"name\":\"$2\",\"arguments\":$3}}"
}

# expect_jar_version <jar>: initialize reports the version in the jar's file name (minecraft-mcp-<version>[+...]).
expect_jar_version() {
  local version init
  version="$(basename "$1" .jar | sed -E 's/^minecraft-mcp-//; s/(\+.*|-universal)$//')"
  init="$(curl -s -X POST "http://127.0.0.1:$PORT/mcp" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}')"
  expect "initialize reports the jar's version $version" "$init" "\"serverInfo\":{\"name\":\"minecraft-mcp\",\"version\":\"$version\""
}

read_token() { sed -n 's/.*"token": *"\([^"]*\)".*/\1/p' "$CONFIG_FILE"; }

# start_session: initialize and mark the client ready. A restarted endpoint has forgotten old sessions.
start_session() {
  SESSION=""
  HEADERS="$(curl -s -D - -o /dev/null -X POST "http://127.0.0.1:$PORT/mcp" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}')"
  SESSION="$(printf '%s' "$HEADERS" | grep -i '^mcp-session-id' | awk '{print $2}' | tr -d '\r')"
  [ -n "$SESSION" ] && mcp '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null
}
