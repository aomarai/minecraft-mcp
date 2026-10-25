#!/usr/bin/env bash
# Smoke test: start a real headless Fabric server with the mod and exercise it over MCP.
#
#   scripts/smoke/fabric-smoke.sh <path-to-mod-jar>
#
# Needs bash, curl, Java 21 and network access (Fabric launcher, Fabric API, Mojang server jar).
# Everything happens in a throwaway directory that is removed on exit. Exits non-zero, with the
# server log, if the mod does not load, the endpoint does not start, or any check fails.
#
# The eula=true written below accepts the Minecraft EULA for this disposable test server only;
# nothing is redistributed. The versions must match adapters/fabric/build.gradle.kts.
set -u

MINECRAFT_VERSION="1.21.1"
FABRIC_LOADER_VERSION="0.20.1"
FABRIC_INSTALLER_VERSION="1.1.2"
FABRIC_API_VERSION="0.116.17+1.21.1"
START_TIMEOUT_SECONDS="${SMOKE_START_TIMEOUT:-300}"
PORT=25599

JAR="${1:?usage: fabric-smoke.sh <mod-jar>}"
JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
[ -f "$JAR" ] || { echo "FAIL: mod jar not found: $JAR"; exit 2; }

WORK="$(mktemp -d)"
TOKEN="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
FAILURES=0
SERVER_PID=""

cleanup() {
  exec 3>&- 2>/dev/null
  if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then kill "$SERVER_PID" 2>/dev/null; fi
  rm -rf "$WORK"
}
trap cleanup EXIT

fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }
pass() { echo "ok:   $1"; }

# expect <label> <haystack> <needle>: fixed-string match, no regex surprises.
expect() {
  if printf '%s' "$2" | grep -qF -- "$3"; then pass "$1"; else fail "$1 (expected to find: $3) got: $(printf '%s' "$2" | head -c 300)"; fi
}

mcp() { # mcp <json> [extra curl args]: POST a JSON-RPC message with the token and session
  curl -s --max-time 30 -X POST "http://127.0.0.1:$PORT/mcp" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" ${SESSION:+-H "Mcp-Session-Id: $SESSION"} -d "$1"
}
call() { # call <id> <tool> <arguments-json>
  mcp "{\"jsonrpc\":\"2.0\",\"id\":$1,\"method\":\"tools/call\",\"params\":{\"name\":\"$2\",\"arguments\":$3}}"
}

echo "== preparing server in $WORK"
cd "$WORK" && mkdir mods || exit 2
curl -fsSLo server.jar "https://meta.fabricmc.net/v2/versions/loader/$MINECRAFT_VERSION/$FABRIC_LOADER_VERSION/$FABRIC_INSTALLER_VERSION/server/jar" \
  || { echo "FAIL: cannot download the Fabric server launcher"; exit 2; }
curl -fsSLo mods/fabric-api.jar "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$FABRIC_API_VERSION/fabric-api-$FABRIC_API_VERSION.jar" \
  || { echo "FAIL: cannot download Fabric API"; exit 2; }
cp "$JAR" mods/minecraft-mcp.jar
echo "eula=true" > eula.txt
printf 'online-mode=false\nlevel-type=flat\ngenerate-structures=false\nview-distance=4\nsimulation-distance=4\n' > server.properties

echo "== starting server"
mkfifo console
# Writes, admin and the token come from the environment, which wins over the generated config file.
MINECRAFT_MCP_ALLOW_WRITES=true MINECRAFT_MCP_ALLOW_ADMIN=true MINECRAFT_MCP_TOKEN="$TOKEN" \
  java -Xmx1G -jar server.jar nogui < console > server.log 2>&1 &
SERVER_PID=$!
exec 3>console

# Wait for the mod's own start-up message. Once the game reports "Done" the mod has had its chance, so
# a missing message after a short grace period means it did not load: fail fast instead of waiting
# out the whole timeout.
done_seconds=""
for second in $(seq 1 "$START_TIMEOUT_SECONDS"); do
  grep -q "MCP server listening\|MCP server not started\|MCP server failed" server.log 2>/dev/null && break
  kill -0 "$SERVER_PID" 2>/dev/null || break
  [ -z "$done_seconds" ] && grep -q 'Done (' server.log 2>/dev/null && done_seconds="$second"
  [ -n "$done_seconds" ] && [ $((second - done_seconds)) -ge 30 ] && break
  sleep 1
done
if ! grep -q "MCP server listening on port $PORT" server.log; then
  echo "FAIL: the mod did not start its endpoint. Server log:"; tail -n 60 server.log; exit 1
fi
pass "mod loaded and the endpoint is listening"

echo "== protocol"
expect "no token is rejected (401)" "$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PORT/mcp" -d '{}')" "401"
expect "wrong token is rejected (401)" "$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PORT/mcp" -H 'Authorization: Bearer not-the-token-not-the-token-1234' -d '{}')" "401"
HEADERS="$(curl -s -D - -o /dev/null -X POST "http://127.0.0.1:$PORT/mcp" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}')"
SESSION="$(printf '%s' "$HEADERS" | grep -i '^mcp-session-id' | awk '{print $2}' | tr -d '\r')"
[ -n "$SESSION" ] && pass "initialize returned a session" || fail "initialize returned no session id"
mcp '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null

TOOLS="$(mcp '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')"
for tool in server_info list_players query_blocks run_command run_admin_command; do expect "tools/list has $tool" "$TOOLS" "\"name\":\"$tool\""; done

echo "== reads"
expect "server_info describes Fabric and Minecraft" "$(call 3 server_info '{}')" "Fabric $FABRIC_LOADER_VERSION / Minecraft $MINECRAFT_VERSION / server"
expect "list_players on an empty server" "$(call 4 list_players '{}')" '{\"players\":[]}'
FLAT="$(call 5 query_blocks '{"dimension":"minecraft:overworld","min_x":0,"min_y":-64,"min_z":0,"max_x":0,"max_y":-64,"max_z":0,"limit":10}')"
expect "query_blocks returns registry ids" "$FLAT" 'minecraft:bedrock'
expect "unknown dimension names the available ones" "$(call 6 query_blocks '{"dimension":"mod:nowhere","min_x":0,"min_y":0,"min_z":0,"max_x":0,"max_y":0,"max_z":0,"limit":1}')" 'available: minecraft:overworld'
expect "a volume over the cap is refused" "$(call 7 query_blocks '{"dimension":"minecraft:overworld","min_x":0,"min_y":0,"min_z":0,"max_x":999,"max_y":99,"max_z":99,"limit":10}')" 'exceeds maxBlockVolume'

echo "== policy-gated write"
expect "run_command runs through Brigadier" "$(call 8 run_command '{"command":"say smoke test"}')" '"isError":false'
expect "a write command takes effect" "$(call 9 run_command '{"command":"time set 6000"}')" '"isError":false'
expect "and a read sees it" "$(call 10 run_command '{"command":"time query daytime"}')" 'The time is 6000'
expect "an unrecognised command is not a plain write" "$(call 11 run_command '{"command":"nonsense"}')" 'this command needs admin access; use run_admin_command'

echo "== policy refusals and confirmation"
expect "a destructive command through run_command is refused" "$(call 12 run_command '{"command":"stop"}')" 'this command needs admin access; use run_admin_command'
expect "run_admin_command needs confirm=true" "$(call 13 run_admin_command '{"command":"stop","confirm":false}')" 'admin action requires confirmation'
sleep 1
kill -0 "$SERVER_PID" 2>/dev/null && pass "the server is still running: nothing was stopped" || fail "the server stopped without confirmation"
expect "a confirmed admin command runs" "$(call 14 run_admin_command '{"command":"setblock 3 -50 3 minecraft:gold_block","confirm":true}')" '"isError":false'
expect "and the placed block is read back by registry id" "$(call 15 query_blocks '{"dimension":"minecraft:overworld","min_x":3,"min_y":-50,"min_z":3,"max_x":3,"max_y":-50,"max_z":3,"limit":1}')" 'minecraft:gold_block'

echo "== audit and files"
AUDIT="config/minecraft-mcp-audit.jsonl"
[ -s "$AUDIT" ] && pass "audit log written" || fail "audit log missing"
expect "audit records a denied call" "$(cat "$AUDIT" 2>/dev/null)" '"decision":"deny"'
if grep -qF "$TOKEN" "$AUDIT" server.log 2>/dev/null; then fail "the token appears in the audit log or server log"; else pass "the token appears in neither the audit log nor the server log"; fi
case "$(stat -c %a "$AUDIT" 2>/dev/null)" in 600) pass "audit log is owner-only";; *) fail "audit log permissions are $(stat -c %a "$AUDIT" 2>/dev/null)";; esac

echo "== shutdown through MCP"
expect "a confirmed stop is accepted" "$(call 16 run_admin_command '{"command":"stop","confirm":true}')" '"isError":false'
stop_started=$SECONDS
for _ in $(seq 1 90); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
if kill -0 "$SERVER_PID" 2>/dev/null; then
  fail "the server process was still running 90 seconds after stop (a thread may be keeping the JVM alive)"
  tail -n 25 server.log
else
  pass "the server process exited $((SECONDS - stop_started))s after stop"
fi
grep -q "MCP server stopped" server.log && pass "endpoint stopped with the server" || fail "no 'MCP server stopped' in the log"
[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/mcp")" = "000" ] && pass "port is closed" || fail "port still answers"

if [ "$FAILURES" -ne 0 ]; then echo "== $FAILURES check(s) failed. Server log tail:"; tail -n 40 server.log; exit 1; fi
echo "== all checks passed"
