#!/usr/bin/env bash
# Smoke test for the stdio shim jar: start the universal jar (which needs no game server to answer
# server_info), then talk to it the way a stdio-only MCP host does, through the shim.
#
#   scripts/smoke/shim-smoke.sh <universal-jar> <stdio-shim-jar>
#
# Needs bash, curl and Java 21. Everything happens in a throwaway directory.
set -u

UNIVERSAL_JAR="${1:?usage: shim-smoke.sh <universal-jar> <stdio-shim-jar>}"
SHIM_JAR="${2:?usage: shim-smoke.sh <universal-jar> <stdio-shim-jar>}"
UNIVERSAL_JAR="$(cd "$(dirname "$UNIVERSAL_JAR")" && pwd)/$(basename "$UNIVERSAL_JAR")"
SHIM_JAR="$(cd "$(dirname "$SHIM_JAR")" && pwd)/$(basename "$SHIM_JAR")"
[ -f "$UNIVERSAL_JAR" ] && [ -f "$SHIM_JAR" ] || { echo "FAIL: jar not found"; exit 2; }

PORT=25599
WORK="$(mktemp -d)"
PID=""
cleanup() { [ -n "$PID" ] && kill "$PID" 2>/dev/null; rm -rf "$WORK"; }
trap cleanup EXIT

# The jar is not told where a game server is; it only needs a password to start.
MINECRAFT_MCP_PORT="$PORT" MINECRAFT_MCP_RCON_PASSWORD="not used in this test" \
  java -jar "$UNIVERSAL_JAR" "$WORK" > "$WORK/universal.log" 2>&1 &
PID=$!
for _ in $(seq 1 60); do grep -q 'Minecraft MCP (universal)' "$WORK/universal.log" 2>/dev/null && break; sleep 1; done
grep -q 'Minecraft MCP (universal)' "$WORK/universal.log" || { echo "FAIL: the universal jar did not start"; cat "$WORK/universal.log"; exit 1; }
TOKEN="$(sed -n 's/.*"token": *"\([^"]*\)".*/\1/p' "$WORK/minecraft-mcp.json")"

# What a host sends: initialize, the initialized notification, then two requests.
OUT="$(printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"shim-smoke","version":"1"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"server_info","arguments":{}}}' \
  | MINECRAFT_MCP_TOKEN="$TOKEN" MINECRAFT_MCP_URL="http://127.0.0.1:$PORT/mcp" java -jar "$SHIM_JAR" 2>/dev/null)"

fail=0
check() { if printf '%s' "$OUT" | grep -qF -- "$2"; then echo "ok:   $1"; else echo "FAIL: $1 (expected to find: $2) got: $(printf '%s' "$OUT" | tr '\n' ' ' | tail -c 300)"; fail=1; fi; }
check "initialize is answered over stdio" '"serverInfo"'
check "tools/list is answered" '"name":"server_info"'
check "a tool call goes through to the server" 'universal/RCON'
LINES="$(printf '%s\n' "$OUT" | grep -c .)"
[ "$LINES" -eq 3 ] && echo "ok:   one reply per request (the notification gets none)" || { echo "FAIL: expected 3 reply lines, got $LINES"; fail=1; }
printf '%s' "$OUT" | grep -qF -- "$TOKEN" && { echo "FAIL: the token appears in the shim's output"; fail=1; }

exit "$fail"
