#!/usr/bin/env bash
# Smoke test: start a real headless Fabric or NeoForge server with the mod and exercise it over MCP.
#
#   scripts/smoke/smoke.sh <fabric|neoforge> <path-to-mod-jar> [node-gradle.properties]
#
# Needs bash, curl, Java 21 and network access (the loader's installer, Fabric API for Fabric, the
# Mojang server jar). Everything happens in a throwaway directory that is removed on exit. Exits
# non-zero, with the server log, if the mod does not load, the endpoint does not start, or any check
# fails. The checks are the same for both loaders: only setting the server up and starting it differs.
#
# The eula=true written below accepts the Minecraft EULA for this disposable test server only;
# nothing is redistributed. The game and loader versions come from the node's own gradle.properties
# (default: the loader's 1.21.1 node), so the test always runs what the jar was built for.
set -u

LOADER="${1:?usage: smoke.sh <fabric|neoforge> <mod-jar> [node-gradle.properties]}"
case "$LOADER" in fabric|neoforge) ;; *) echo "FAIL: unknown loader '$LOADER' (fabric or neoforge)"; exit 2 ;; esac
NODE_PROPERTIES="${3:-$(cd "$(dirname "$0")/../.." && pwd)/adapters/$LOADER/versions/1.21.1/gradle.properties}"
property() { sed -n "s/^$1=//p" "$NODE_PROPERTIES" | tr -d '\r'; }
MINECRAFT_VERSION="$(property minecraft_version)"
[ -n "$MINECRAFT_VERSION" ] || { echo "FAIL: cannot read the versions from $NODE_PROPERTIES"; exit 2; }
# Commands and game rules that Minecraft renamed between versions, so the same checks run on every node.
GAME_RULE="$(property smoke_gamerule)"
TIME_QUERY="$(property smoke_time_query)"
TIME_EXPECT="$(property smoke_time_expect)"
[ -n "$GAME_RULE" ] && [ -n "$TIME_QUERY" ] && [ -n "$TIME_EXPECT" ] || { echo "FAIL: smoke_gamerule, smoke_time_query or smoke_time_expect missing in $NODE_PROPERTIES"; exit 2; }
START_TIMEOUT_SECONDS="${SMOKE_START_TIMEOUT:-300}"
PORT=25599

JAR="${2:?usage: smoke.sh <fabric|neoforge> <mod-jar>}"
JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
[ -f "$JAR" ] || { echo "FAIL: mod jar not found: $JAR"; exit 2; }

WORK="$(mktemp -d)"
OLD_TOKENS=""
SERVER_PID=""
CONFIG_FILE="config/minecraft-mcp.json"

cleanup() {
  exec 3>&- 2>/dev/null
  if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then kill "$SERVER_PID" 2>/dev/null; fi
  rm -rf "$WORK"
}
trap cleanup EXIT

. "$(dirname "$0")/lib.sh"

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
case "$LOADER" in
  fabric)
    FABRIC_LOADER_VERSION="$(property fabric_loader_version)"
    FABRIC_API_VERSION="$(property fabric_api_version)"
    [ -n "$FABRIC_LOADER_VERSION" ] && [ -n "$FABRIC_API_VERSION" ] || { echo "FAIL: missing Fabric versions in $NODE_PROPERTIES"; exit 2; }
    LOADER_VERSION="$FABRIC_LOADER_VERSION"
    LOADER_LABEL="Fabric $FABRIC_LOADER_VERSION"
    curl -fsSLo server.jar "https://meta.fabricmc.net/v2/versions/loader/$MINECRAFT_VERSION/$FABRIC_LOADER_VERSION/1.1.2/server/jar" \
      || { echo "FAIL: cannot download the Fabric server launcher"; exit 2; }
    curl -fsSLo mods/fabric-api.jar "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$FABRIC_API_VERSION/fabric-api-$FABRIC_API_VERSION.jar" \
      || { echo "FAIL: cannot download Fabric API"; exit 2; }
    LAUNCH=(java -Xmx1G -jar server.jar nogui)
    ;;
  neoforge)
    NEOFORGE_VERSION="$(property neoforge_version)"
    [ -n "$NEOFORGE_VERSION" ] || { echo "FAIL: missing neoforge_version in $NODE_PROPERTIES"; exit 2; }
    LOADER_VERSION="$NEOFORGE_VERSION"
    LOADER_LABEL="NeoForge $NEOFORGE_VERSION"
    curl -fsSLo installer.jar "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NEOFORGE_VERSION/neoforge-$NEOFORGE_VERSION-installer.jar" \
      || { echo "FAIL: cannot download the NeoForge installer"; exit 2; }
    java -jar installer.jar --installServer > install.log 2>&1 \
      || { echo "FAIL: the NeoForge server did not install. Log:"; tail -n 30 install.log; exit 2; }
    LAUNCH=(java -Xmx1G @user_jvm_args.txt "@libraries/net/neoforged/neoforge/$NEOFORGE_VERSION/unix_args.txt" nogui)
    ;;
esac
cp "$JAR" mods/minecraft-mcp.jar
echo "eula=true" > eula.txt
printf 'online-mode=false\nwhite-list=false\nlevel-type=flat\ngenerate-structures=false\nview-distance=4\nsimulation-distance=4\n' > server.properties

echo "== starting server"
mkfifo console
# Writes and admin come from the environment, which wins over the generated config file. The token is the
# generated one from that file, so that rotating it can be tested.
MINECRAFT_MCP_ALLOW_WRITES=true MINECRAFT_MCP_ALLOW_ADMIN=true \
  "${LAUNCH[@]}" < console > server.log 2>&1 &
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
expect_jar_version "$JAR"

TOOLS="$(mcp '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')"
TOOL_COUNT="$(printf '%s' "$TOOLS" | grep -o '"inputSchema"' | wc -l | tr -d ' ')"
for tool in server_info list_players query_blocks list_registry list_entities get_world_state list_permissions get_inventory read_log read_events fill_blocks teleport give_item send_message kick_player ban_player save_world stop_server run_command run_admin_command; do expect "tools/list has $tool" "$TOOLS" "\"name\":\"$tool\""; done

refute "a dedicated server does not offer the client-only screenshot tool" "$TOOLS" '"name":"take_screenshot"'

echo "== reads"
# Newer versions do not keep the area around 0,0 loaded, and queries never load or generate chunks.
expect "a chunk can be kept loaded for the tests" "$(call 2 run_admin_command '{"command":"forceload add 0 0","confirm":true}')" '"isError":false'
expect "server_info describes the loader and Minecraft" "$(call 3 server_info '{}')" "$LOADER_LABEL / Minecraft $MINECRAFT_VERSION / server"
expect "list_players on an empty server" "$(call 4 list_players '{}')" '{\"players\":[]}'
expect_eventually "query_blocks returns registry ids" 'minecraft:bedrock' 5 query_blocks '{"dimension":"minecraft:overworld","min_x":0,"min_y":-64,"min_z":0,"max_x":0,"max_y":-64,"max_z":0,"limit":10}'
expect "unknown dimension names the available ones" "$(call 6 query_blocks '{"dimension":"mod:nowhere","min_x":0,"min_y":0,"min_z":0,"max_x":0,"max_y":0,"max_z":0,"limit":1}')" 'available: minecraft:overworld'
expect "a volume over the cap is refused" "$(call 7 query_blocks '{"dimension":"minecraft:overworld","min_x":0,"min_y":0,"min_z":0,"max_x":999,"max_y":99,"max_z":99,"limit":10}')" 'exceeds maxBlockVolume'
# A query of the largest default volume (32 x 32 x 32) over loaded air: nothing is found, so the whole box is walked.
# Its main-thread time is printed with the audit log below.
expect "a query over the whole default volume of air answers" "$(call 700 query_blocks '{"dimension":"minecraft:overworld","min_x":0,"min_y":100,"min_z":0,"max_x":31,"max_y":131,"max_z":31,"limit":10}')" '\"blocks\":[]'

echo "== policy-gated write"
expect "run_command runs through Brigadier" "$(call 8 run_command '{"command":"say smoke test"}')" '"isError":false'
expect "a write command takes effect" "$(call 9 run_command '{"command":"time set 6000"}')" '"isError":false'
# The game ticks between the two commands, so allow a few ticks of drift: 6000 becomes 600x or 60xx.
expect "and a read sees it" "$(call 10 run_command "{\"command\":\"time query $TIME_QUERY\"}")" "$TIME_EXPECT"
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
expect "and every game rule, by name" "$STATE" "\\\"$GAME_RULE\\\":\\\"true\\\""
expect "and the time" "$STATE" '\"dayTime\":'
expect "a game rule change by an admin command is visible to the read" "$(call 41 run_admin_command "{\"command\":\"gamerule $GAME_RULE false\",\"confirm\":true}")" '"isError":false'
expect "the new rule value is reported" "$(call 42 get_world_state '{"dimension":"minecraft:overworld"}')" "\\\"$GAME_RULE\\\":\\\"false\\\""
expect "an unknown dimension names the available ones" "$(call 43 get_world_state '{"dimension":"mod:nowhere"}')" 'available: minecraft:overworld'
expect "an unconfigured server has no operators" "$(call 44 list_permissions '{}')" '\"operators\":[]'
expect "granting operator succeeds" "$(call 45 run_admin_command '{"command":"op Steve","confirm":true}')" '"isError":false'
# The list was empty before, so level 4 can only be the operator just granted. The name is not compared: an offline
# server may store it as "Steve" or as "steve", and that is the server's choice, not what this checks.
expect_eventually "list_permissions shows the new operator at level 4" '\"level\":4' 46 list_permissions '{}'
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
expect "audit records how long a call held the game thread" "$(cat "$AUDIT" 2>/dev/null)" '"mainThreadMicros"'
expect "audit records whether a call overran the tick budget" "$(cat "$AUDIT" 2>/dev/null)" '"overrun"'
echo "     main-thread time of the slowest list_players and query_blocks calls, from the audit log:"
grep -F '"phase":"result"' "$AUDIT" \
  | sed -n 's/.*"tool":"\([a-z_]*\)".*"mainThreadMicros":\([0-9]*\),"totalMicros":\([0-9]*\),"overrun":\([a-z]*\).*/\2 \3 \1 \4/p' \
  | sort -n -r | awk '($3 == "query_blocks" || $3 == "list_players") && !seen[$3]++' \
  | while read -r main total tool over; do echo "       $tool: main thread $main us, total $total us, overrun $over"; done
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
