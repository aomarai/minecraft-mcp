#!/usr/bin/env bash
# Helpers shared by the smoke tests (smoke.sh for the mods, universal-smoke.sh for the universal tier). Source it.
#
# The caller sets PORT (the MCP endpoint's port) and CONFIG_FILE (the MCP config, where the generated token
# is), and keeps TOKEN and SESSION up to date through start_session. FAILURES counts failed checks.

FAILURES=0
TOKEN=""
SESSION=""

fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }
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

read_token() { sed -n 's/.*"token": *"\([^"]*\)".*/\1/p' "$CONFIG_FILE"; }

# start_session: initialize and mark the client ready. A restarted endpoint has forgotten old sessions.
start_session() {
  SESSION=""
  HEADERS="$(curl -s -D - -o /dev/null -X POST "http://127.0.0.1:$PORT/mcp" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}')"
  SESSION="$(printf '%s' "$HEADERS" | grep -i '^mcp-session-id' | awk '{print $2}' | tr -d '\r')"
  [ -n "$SESSION" ] && mcp '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null
}
