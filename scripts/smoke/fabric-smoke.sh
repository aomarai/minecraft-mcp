#!/usr/bin/env bash
# Smoke test: start a real headless Fabric server with the mod and exercise it over MCP.
#
#   scripts/smoke/fabric-smoke.sh <path-to-mod-jar> [node-gradle.properties]
#
# Needs bash, curl, Java 21 and network access (Fabric launcher, Fabric API, Mojang server jar).
# Everything happens in a throwaway directory that is removed on exit. Exits non-zero, with the
# server log, if the mod does not load, the endpoint does not start, or any check fails.
#
# The eula=true written below accepts the Minecraft EULA for this disposable test server only;
# nothing is redistributed. The game, loader and Fabric API versions come from the node's own
# gradle.properties (default: the 1.21.1 node), so the test always runs what the jar was built for.
set -u

NODE_PROPERTIES="${2:-$(cd "$(dirname "$0")/../.." && pwd)/adapters/fabric/versions/1.21.1/gradle.properties}"
property() { sed -n "s/^$1=//p" "$NODE_PROPERTIES" | tr -d ''; }
MINECRAFT_VERSION="$(property minecraft_version)"
FABRIC_LOADER_VERSION="$(property fabric_loader_version)"
FABRIC_API_VERSION="$(property fabric_api_version)"
[ -n "$MINECRAFT_VERSION" ] && [ -n "$FABRIC_LOADER_VERSION" ] && [ -n "$FABRIC_API_VERSION" ]   || { echo "FAIL: cannot read the versions from $NODE_PROPERTIES"; exit 2; }
FABRIC_INSTALLER_VERSION="1.1.2"
START_TIMEOUT_SECONDS="${SMOKE_START_TIMEOUT:-300}"
PORT=25599

JAR="${1:?usage: fabric-smoke.sh <mod-jar>}"
JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
[ -f "$JAR" ] || { echo "FAIL: mod jar not found: $JAR"; exit 2; }

WORK="$(mktemp -d)"
TOKEN=""
OLD_TOKENS=""
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

read_token() { sed -n 's/.*"token": *"\([^"]*\)".*/\1/p' config/minecraft-mcp.json; }

# start_session: initialize and mark the client ready. A restarted endpoint has forgotten old sessions.
start_session() {
  SESSION=""
  HEADERS="$(curl -s -D - -o /dev/null -X POST "http://127.0.0.1:$PORT/mcp" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}')"
  SESSION="$(printf '%s' "$HEADERS" | grep -i '^mcp-session-id' | awk '{print $2}' | tr -d '\r')"
  [ -n "$SESSION" ] && mcp '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null
}

# console <command> <expected log text> <label>: type a command at the server console and look for its reply.
console() {
  local before; before=$(wc -l < server.log)
  echo "$1" >&3
  for _ in $(seq 1 30); do
    if tail -n +$((before + 1)) server.log | grep -qF -- "$2"; then pass "$3"; return; fi
    sleep 1
  done
  fail "$3 (expected in the log: $2)"
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
# Writes and admin come from the environment, which wins over the generated config file. The token is the
# generated one from that file, so that rotating it can be tested.
MINECRAFT_MCP_ALLOW_WRITES=true MINECRAFT_MCP_ALLOW_ADMIN=true \
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
TOKEN="$(read_token)"
[ "${#TOKEN}" -ge 16 ] && pass "a token was generated into the config file" || fail "no token in config/minecraft-mcp.json"

echo "== protocol"
expect "no token is rejected (401)" "$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PORT/mcp" -d '{}')" "401"
expect "wrong token is rejected (401)" "$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PORT/mcp" -H 'Authorization: Bearer not-the-token-not-the-token-1234' -d '{}')" "401"
start_session
[ -n "$SESSION" ] && pass "initialize returned a session" || fail "initialize returned no session id"

TOOLS="$(mcp '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')"
TOOL_COUNT="$(printf '%s' "$TOOLS" | grep -o '"inputSchema"' | wc -l | tr -d ' ')"
for tool in server_info list_players query_blocks list_registry list_entities get_world_state list_permissions get_inventory read_log read_events fill_blocks teleport give_item send_message kick_player ban_player save_world stop_server run_command run_admin_command; do expect "tools/list has $tool" "$TOOLS" "\"name\":\"$tool\""; done

refute "a dedicated server does not offer the client-only screenshot tool" "$TOOLS" '"name":"take_screenshot"'

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
# The game ticks between the two commands, so allow a few ticks of drift: 6000 becomes 600x or 60xx.
expect "and a read sees it" "$(call 10 run_command '{"command":"time query daytime"}')" 'The time is 60'
expect "an unrecognised command is not a plain write" "$(call 11 run_command '{"command":"nonsense"}')" 'this command needs admin access; use run_admin_command'

echo "== policy refusals and confirmation"
expect "a destructive command through run_command is refused" "$(call 12 run_command '{"command":"stop"}')" 'this command needs admin access; use run_admin_command'
expect "run_admin_command needs confirm=true" "$(call 13 run_admin_command '{"command":"stop","confirm":false}')" 'admin action requires confirmation'
sleep 1
kill -0 "$SERVER_PID" 2>/dev/null && pass "the server is still running: nothing was stopped" || fail "the server stopped without confirmation"
expect "a confirmed admin command runs" "$(call 14 run_admin_command '{"command":"setblock 3 -50 3 minecraft:gold_block","confirm":true}')" '"isError":false'
expect "and the placed block is read back by registry id" "$(call 15 query_blocks '{"dimension":"minecraft:overworld","min_x":3,"min_y":-50,"min_z":3,"max_x":3,"max_y":-50,"max_z":3,"limit":1}')" 'minecraft:gold_block'

echo "== registries and entities"
expect "list_registry finds a block by search" "$(call 20 list_registry '{"registry":"minecraft:block","search":"gold_block"}')" 'minecraft:gold_block'
PAGE="$(call 21 list_registry '{"registry":"block","limit":2}')"
expect "a bare registry name means minecraft:" "$PAGE" '\"registry\":\"minecraft:block\"'
expect "listings are paged" "$PAGE" '\"hasMore\":true'
expect "the next page starts where this one ended" "$(call 22 list_registry '{"registry":"minecraft:block","offset":2,"limit":2}')" '\"offset\":2'
expect "a dynamic registry is listed too" "$(call 23 list_registry '{"registry":"minecraft:worldgen/biome","search":"plains","limit":5}')" 'minecraft:plains'
expect "an unknown registry names the available ones" "$(call 24 list_registry '{"registry":"mod:nothing"}')" 'available: '
expect "a malformed id is rejected clearly" "$(call 25 list_registry '{"registry":"Not Valid!"}')" 'registry must be a resource location'
expect "the page size is bounded" "$(call 26 list_registry '{"registry":"minecraft:block","limit":201}')" 'must have a maximum value of 200'
expect "an entity can be spawned with a confirmed admin command" "$(call 27 run_admin_command '{"command":"summon minecraft:armor_stand 0 -50 0","confirm":true}')" '"isError":false'
expect_eventually "list_entities finds it by type, by registry id" 'minecraft:armor_stand' 28 list_entities '{"dimension":"minecraft:overworld","x":0,"y":-50,"z":0,"radius":5,"type":"minecraft:armor_stand"}'
expect "a different type finds nothing" "$(call 29 list_entities '{"dimension":"minecraft:overworld","x":0,"y":-50,"z":0,"radius":5,"type":"minecraft:zombie"}')" '\"entities\":[]'
expect "the radius is bounded" "$(call 30 list_entities '{"dimension":"minecraft:overworld","x":0,"y":0,"z":0,"radius":100}')" 'must have a maximum value of 64'
expect "an unknown dimension is reported with the available ones" "$(call 31 list_entities '{"dimension":"mod:nowhere","x":0,"y":0,"z":0,"radius":5}')" 'available: minecraft:overworld'

echo "== world state and permissions"
STATE="$(call 40 get_world_state '{"dimension":"minecraft:overworld"}')"
expect "get_world_state reports the difficulty" "$STATE" '\"difficulty\":'
expect "and every game rule, by name" "$STATE" '\"doDaylightCycle\":\"true\"'
expect "and the time" "$STATE" '\"dayTime\":'
expect "a game rule change by an admin command is visible to the read" "$(call 41 run_admin_command '{"command":"gamerule doDaylightCycle false","confirm":true}')" '"isError":false'
expect "the new rule value is reported" "$(call 42 get_world_state '{"dimension":"minecraft:overworld"}')" '\"doDaylightCycle\":\"false\"'
expect "an unknown dimension names the available ones" "$(call 43 get_world_state '{"dimension":"mod:nowhere"}')" 'available: minecraft:overworld'
expect "an unconfigured server has no operators" "$(call 44 list_permissions '{}')" '\"operators\":[]'
expect "granting operator succeeds" "$(call 45 run_admin_command '{"command":"op Steve","confirm":true}')" '"isError":false'
expect_eventually "list_permissions shows the new operator and level" 'Steve' 46 list_permissions '{}'
expect "the whitelist state is reported" "$(call 47 list_permissions '{}')" '\"whitelistEnabled\":false'

echo "== inventory and log"
expect "get_inventory of someone who is not online says so" "$(call 50 get_inventory '{"player":"Nobody"}')" "player 'Nobody' is not online"
expect "get_inventory needs a player name" "$(call 51 get_inventory '{}')" "required property 'player' not found"
LOG="$(call 52 read_log '{"lines":200}')"
expect "read_log returns the server log" "$LOG" 'Done ('
expect "and flags it untrusted" "$LOG" '\"untrusted\":true'
expect "IP addresses in it are masked" "$LOG" '[ip]'
refute "and the raw address never appears" "$LOG" '127.0.0.1:25599'
expect "the line count is bounded" "$(call 53 read_log '{"lines":201}')" 'must have a maximum value of 200'
expect "read_log defaults to a short tail" "$(call 54 read_log '{}')" '\"lines\":['

EVENTS="$(call 55 read_events '{}')"
expect "read_events reports the feed, which is empty before anyone joins" "$EVENTS" '\"events\":[],\"latest\":0,\"dropped\":0'
expect "read_events takes a cursor" "$(call 56 read_events '{"after":0,"limit":5}')" '\"latest\":0'
expect "the event page size is bounded" "$(call 57 read_events '{"limit":201}')" 'must have a maximum value of 200'
RESOURCES="$(mcp '{"jsonrpc":"2.0","id":58,"method":"resources/list"}')"
expect "the event feed is also a resource clients can subscribe to" "$RESOURCES" 'minecraft://events'
expect "and reading it returns the same JSON" "$(mcp '{"jsonrpc":"2.0","id":59,"method":"resources/read","params":{"uri":"minecraft://events"}}')" '\"events\":[]'
expect "subscribing to it is accepted" "$(mcp '{"jsonrpc":"2.0","id":60,"method":"resources/subscribe","params":{"uri":"minecraft://events"}}')" '"result":{}'

echo "== typed write and admin tools"
BOX='"dimension":"minecraft:overworld","min_x":10,"min_y":-58,"min_z":10,"max_x":11,"max_y":-57,"max_z":11,"block":"minecraft:emerald_block"'
DRY="$(call 60 fill_blocks "{$BOX,\"dry_run\":true}")"
expect "fill_blocks dry run reports the volume" "$DRY" '\"volume\":8'
expect "and the exact command it would run" "$DRY" 'execute in minecraft:overworld run fill 10 -58 10 11 -57 11 minecraft:emerald_block'
expect "a dry run changes nothing" "$(call 61 query_blocks '{"dimension":"minecraft:overworld","min_x":10,"min_y":-58,"min_z":10,"max_x":11,"max_y":-57,"max_z":11,"limit":10}')" '\"blocks\":[]'
expect "fill_blocks fills the box" "$(call 62 fill_blocks "{$BOX}")" '"isError":false'
expect "and the new blocks are read back by registry id" "$(call 63 query_blocks '{"dimension":"minecraft:overworld","min_x":10,"min_y":-58,"min_z":10,"max_x":11,"max_y":-57,"max_z":11,"limit":10}')" 'minecraft:emerald_block'
expect "a box over the volume cap is refused" "$(call 64 fill_blocks '{"dimension":"minecraft:overworld","min_x":0,"min_y":0,"min_z":0,"max_x":40,"max_y":40,"max_z":40,"block":"stone","dry_run":true}')" 'exceeds maxBlockVolume'
expect "a malformed block id is rejected clearly" "$(call 65 fill_blocks '{"dimension":"minecraft:overworld","min_x":0,"min_y":0,"min_z":0,"max_x":1,"max_y":1,"max_z":1,"block":"stone run stop"}')" 'block must be a resource location'
expect "teleport reports the game's own error for an absent player" "$(call 66 teleport '{"player":"Nobody","x":0,"y":-50,"z":0}')" '"isError":true'
expect "a selector in place of a player name is rejected before the game sees it" "$(call 67 teleport '{"player":"@a","x":0,"y":-50,"z":0}')" 'player must be a player name'
expect "give_item reports the game's own error for an absent player" "$(call 68 give_item '{"player":"Nobody","item":"minecraft:diamond"}')" '"isError":true'
expect "send_message reaches the chat" "$(call 69 send_message '{"message":"hello from the typed tool"}')" '"isError":false'
expect "a message cannot span lines" "$(call 70 send_message '{"message":"hi\nstop"}')" 'must not contain control characters'
expect "kick_player needs confirmation" "$(call 71 kick_player '{"player":"Nobody","confirm":false}')" 'requires confirmation'
expect "and a confirmed kick of an absent player is the game's error" "$(call 72 kick_player '{"player":"Nobody","confirm":true}')" '"isError":true'
expect "ban_player needs confirmation" "$(call 73 ban_player '{"player":"Nobody","confirm":false}')" 'requires confirmation'
expect "save_world needs confirmation" "$(call 74 save_world '{"confirm":false}')" 'requires confirmation'
expect "a confirmed save_world succeeds" "$(call 75 save_world '{"confirm":true}')" '"isError":false'
expect "stop_server needs confirmation" "$(call 76 stop_server '{"confirm":false}')" 'requires confirmation'
sleep 1
kill -0 "$SERVER_PID" 2>/dev/null && pass "the server is still running: no typed admin tool acted unconfirmed" || fail "the server stopped without confirmation"

echo "== operator commands (/mcp from the console)"
console "mcp status" "MCP server is listening on 127.0.0.1:$PORT, writes and admin enabled, $TOOL_COUNT tools" "/mcp status reports the endpoint and access"
# A bad configuration must not take the working server down.
cp config/minecraft-mcp.json config.good
sed -i 's/"port": [0-9]*/"port": 99999999/' config/minecraft-mcp.json
console "mcp reload" "Not changed:" "/mcp reload refuses an invalid configuration"
cp config.good config/minecraft-mcp.json
expect "and the server keeps serving after the refused reload" "$(call 17 server_info '{}')" '"isError":false'
# A valid change is applied: a smaller volume cap.
sed -i 's/"maxBlockVolume": [0-9]*/"maxBlockVolume": 8/' config/minecraft-mcp.json
console "mcp reload" "MCP server reloaded" "/mcp reload applies a changed configuration"
start_session
expect "the new volume cap is in force" "$(call 18 query_blocks '{"dimension":"minecraft:overworld","min_x":0,"min_y":0,"min_z":0,"max_x":2,"max_y":2,"max_z":2,"limit":10}')" 'exceeds maxBlockVolume 8'
# Rotating the token: the old one stops working at once, the new one works, and neither is ever printed.
OLD_TOKENS="$TOKEN"
console "mcp token rotate" "MCP token rotated" "/mcp token rotate succeeds"
TOKEN="$(read_token)"
[ -n "$TOKEN" ] && [ "$TOKEN" != "$OLD_TOKENS" ] && pass "the config file holds a new token" || fail "the token did not change"
expect "the old token is rejected" "$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PORT/mcp" -H "Authorization: Bearer $OLD_TOKENS" -d '{}')" "401"
start_session
[ -n "$SESSION" ] && pass "the new token is accepted" || fail "the new token was rejected"
OLD_TOKENS="$OLD_TOKENS $TOKEN"

echo "== audit and files"
AUDIT="config/minecraft-mcp-audit.jsonl"
[ -s "$AUDIT" ] && pass "audit log written" || fail "audit log missing"
expect "audit records a denied call" "$(cat "$AUDIT" 2>/dev/null)" '"decision":"deny"'
for secret in $OLD_TOKENS; do
  if grep -qF "$secret" "$AUDIT" server.log 2>/dev/null; then fail "a token appears in the audit log or server log"; fi
done
pass "neither the old nor the new token appears in the audit log or the server log"
case "$(stat -c %a "$AUDIT" 2>/dev/null)" in 600) pass "audit log is owner-only";; *) fail "audit log permissions are $(stat -c %a "$AUDIT" 2>/dev/null)";; esac

echo "== shutdown through MCP (stop_server)"
expect "a confirmed stop_server is accepted" "$(call 16 stop_server '{"confirm":true}')" '"isError":false'
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
