#!/usr/bin/env bash
# Smoke test for the universal tier: start a real vanilla, Paper or (modded) Fabric server with NO MCP mod, start the standalone
# jar next to it, and exercise it over MCP (RCON, Query and the log are the only ways it can reach the game).
#
#   scripts/smoke/universal-smoke.sh <vanilla|paper|fabric> <path-to-universal-jar>
#
# Needs bash, curl, Java 21 and network access (Mojang or PaperMC for the server jar). Everything happens in a
# throwaway directory that is removed on exit. Exits non-zero, with the logs, if the server or the jar does not
# start or any check fails.
#
# The eula=true written below accepts the Minecraft EULA for this disposable test server only; nothing is
# redistributed. The server jars are verified against the checksums published with them.
set -u

KIND="${1:?usage: universal-smoke.sh <vanilla|paper|fabric> <universal-jar>}"
case "$KIND" in vanilla|paper|fabric) ;; *) echo "FAIL: unknown server '$KIND' (vanilla, paper or fabric)"; exit 2 ;; esac
JAR="${2:?usage: universal-smoke.sh <vanilla|paper|fabric> <universal-jar>}"
JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
[ -f "$JAR" ] || { echo "FAIL: universal jar not found: $JAR"; exit 2; }

MINECRAFT_VERSION="1.21.1"
# Paper publishes a checksum with every build; pinning the build keeps the test repeatable.
PAPER_URL="https://fill-data.papermc.io/v1/objects/39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9/paper-1.21.1-133.jar"
PAPER_SHA256="39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9"
START_TIMEOUT_SECONDS="${SMOKE_START_TIMEOUT:-300}"
PORT=25599
WRONG_PORT=25600
RCON_PORT=25575
QUERY_PORT=25565

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
SERVER_DIR="$WORK/server"
MCP_DIR="$WORK/mcp"
CONFIG_FILE="$MCP_DIR/minecraft-mcp.json"
SERVER_PID=""
UNIVERSAL_PID=""
WRONG_PID=""
RCON_PASSWORD="$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 24)"

cleanup() {
  exec 3>&- 2>/dev/null
  for pid in "$SERVER_PID" "$UNIVERSAL_PID" "$WRONG_PID"; do
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then kill "$pid" 2>/dev/null; fi
  done
  rm -rf "$WORK"
}
trap cleanup EXIT

. "$(dirname "$0")/lib.sh"

# expect_match <label> <haystack> <extended regex>
expect_match() {
  if printf '%s' "$2" | grep -qE -- "$3"; then pass "$1"; else fail "$1 (expected to match: $3) got: $(printf '%s' "$2" | tr '\n' ' ' | tail -c 400)"; fi
}

# wait_for <seconds> <command...>: poll until the command succeeds.
wait_for() {
  local seconds="$1"; shift
  for _ in $(seq 1 "$seconds"); do "$@" && return 0; sleep 1; done
  return 1
}

echo "== preparing a $KIND server in $SERVER_DIR"
mkdir -p "$SERVER_DIR" "$MCP_DIR" && cd "$SERVER_DIR" || exit 2
if [ "$KIND" = "fabric" ]; then
  # A modded server: Fabric with its API, the way a modpack runs, but without this project's mod. The versions
  # are the ones the Fabric adapter is built for.
  NODE_PROPERTIES="$REPO/adapters/fabric/versions/1.21.1/gradle.properties"
  property() { sed -n "s/^$1=//p" "$NODE_PROPERTIES" | tr -d '
'; }
  LOADER_VERSION="$(property fabric_loader_version)"; API_VERSION="$(property fabric_api_version)"
  [ -n "$LOADER_VERSION" ] && [ -n "$API_VERSION" ] || { echo "FAIL: cannot read the Fabric versions from $NODE_PROPERTIES"; exit 2; }
  curl -fsSLo server.jar "https://meta.fabricmc.net/v2/versions/loader/$MINECRAFT_VERSION/$LOADER_VERSION/1.1.2/server/jar"     || { echo "FAIL: cannot download the Fabric server launcher"; exit 2; }
  mkdir mods && curl -fsSLo mods/fabric-api.jar "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$API_VERSION/fabric-api-$API_VERSION.jar"     || { echo "FAIL: cannot download Fabric API"; exit 2; }
elif [ "$KIND" = "paper" ]; then
  curl -fsSLo server.jar "$PAPER_URL" || { echo "FAIL: cannot download Paper"; exit 2; }
  [ "$(sha256sum server.jar | cut -d' ' -f1)" = "$PAPER_SHA256" ] || { echo "FAIL: Paper does not match its published checksum"; exit 2; }
else
  # Mojang publishes a manifest of versions, each naming its server jar and the jar's SHA-1.
  MANIFEST="$(curl -fsSL https://piston-meta.mojang.com/mc/game/version_manifest_v2.json | tr -d '\n')" \
    || { echo "FAIL: cannot download the Minecraft version manifest"; exit 2; }
  VERSION_URL="$(printf '%s' "$MANIFEST" | sed -n "s/.*\"id\": *\"$MINECRAFT_VERSION\", *\"type\": *\"release\", *\"url\": *\"\\([^\"]*\\)\".*/\\1/p")"
  [ -n "$VERSION_URL" ] || { echo "FAIL: Minecraft $MINECRAFT_VERSION is not in the manifest"; exit 2; }
  VERSION_JSON="$(curl -fsSL "$VERSION_URL" | tr -d '\n')" || { echo "FAIL: cannot download the version description"; exit 2; }
  SERVER_URL="$(printf '%s' "$VERSION_JSON" | sed -n 's/.*"server": *{[^}]*"url": *"\([^"]*\)".*/\1/p')"
  SERVER_SHA1="$(printf '%s' "$VERSION_JSON" | sed -n 's/.*"server": *{[^}]*"sha1": *"\([^"]*\)".*/\1/p')"
  [ -n "$SERVER_URL" ] && [ -n "$SERVER_SHA1" ] || { echo "FAIL: cannot find the server jar for $MINECRAFT_VERSION"; exit 2; }
  curl -fsSLo server.jar "$SERVER_URL" || { echo "FAIL: cannot download the Minecraft server"; exit 2; }
  [ "$(sha1sum server.jar | cut -d' ' -f1)" = "$SERVER_SHA1" ] || { echo "FAIL: the server jar does not match its published checksum"; exit 2; }
fi
echo "eula=true" > eula.txt
printf 'online-mode=false\nwhite-list=false\nlevel-type=flat\ngenerate-structures=false\nview-distance=4\nsimulation-distance=4\nenable-rcon=true\nrcon.port=%s\nrcon.password=%s\nenable-query=true\nquery.port=%s\n' \
  "$RCON_PORT" "$RCON_PASSWORD" "$QUERY_PORT" > server.properties

echo "== starting the server"
mkfifo console
java -Xmx1G -jar server.jar nogui < console > server.log 2>&1 &
SERVER_PID=$!
exec 3>console
wait_for "$START_TIMEOUT_SECONDS" grep -q 'Done (' server.log \
  || { echo "FAIL: the $KIND server did not finish starting. Server log:"; tail -n 60 server.log; exit 1; }
pass "the $KIND server is up"
kill -0 "$SERVER_PID" 2>/dev/null || { echo "FAIL: the server stopped"; tail -n 40 server.log; exit 1; }

echo "== starting the universal jar (no mod anywhere)"
cd "$MCP_DIR" || exit 2
MINECRAFT_MCP_ALLOW_WRITES=true MINECRAFT_MCP_ALLOW_ADMIN=true MINECRAFT_MCP_PORT="$PORT" \
  MINECRAFT_MCP_RCON_PORT="$RCON_PORT" MINECRAFT_MCP_RCON_PASSWORD="$RCON_PASSWORD" \
  MINECRAFT_MCP_QUERY_PORT="$QUERY_PORT" MINECRAFT_MCP_SERVER_DIRECTORY="$SERVER_DIR" \
  java -jar "$JAR" "$MCP_DIR" > universal.log 2>&1 &
UNIVERSAL_PID=$!
wait_for 60 grep -q 'Minecraft MCP (universal)' universal.log \
  || { echo "FAIL: the universal jar did not start. Its log:"; tail -n 40 universal.log; exit 1; }
pass "the universal jar is listening"
TOKEN="$(read_token)"
[ "${#TOKEN}" -ge 16 ] && pass "a token was generated into the config file" || fail "no token in $CONFIG_FILE"

echo "== protocol"
expect "no token is rejected (401)" "$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PORT/mcp" -d '{}')" "401"
expect "wrong token is rejected (401)" "$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PORT/mcp" -H 'Authorization: Bearer not-the-token-not-the-token-1234' -d '{}')" "401"
start_session
[ -n "$SESSION" ] && pass "initialize returned a session" || fail "initialize returned no session id"
expect_jar_version "$JAR"

TOOLS="$(mcp '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')"
for tool in server_info list_players read_log read_events fill_blocks teleport give_item send_message kick_player ban_player save_world stop_server run_command run_admin_command; do
  expect "tools/list has $tool" "$TOOLS" "\"name\":\"$tool\""
done
for tool in query_blocks list_registry list_entities get_world_state list_permissions get_inventory take_screenshot; do
  refute "tools/list does not offer $tool (it cannot work without a mod)" "$TOOLS" "\"name\":\"$tool\""
done

echo "== reads"
expect "server_info says universal/RCON and the version Query reports" "$(call 3 server_info '{}')" "universal/RCON / Minecraft $MINECRAFT_VERSION / server"
expect "list_players on an empty server" "$(call 4 list_players '{}')" '{\"players\":[]}'
expect "run_command runs a read through RCON" "$(call 5 run_command '{"command":"list"}')" 'There are 0 of a max of'
LOG="$(call 6 read_log '{"lines":200}')"
expect "read_log returns the server log" "$LOG" 'Done ('
expect "and flags it untrusted" "$LOG" '\"untrusted\":true'
expect "read_events starts empty" "$(call 7 read_events '{}')" '\"events\":[]'
echo "[12:00:00] [Server thread/INFO]: <Steve> hello from the log" >> "$SERVER_DIR/logs/latest.log"
expect_eventually "chat written to the real log becomes an event" 'hello from the log' 8 read_events '{}'

echo "== policy-gated write"
expect "a write command takes effect" "$(call 10 run_command '{"command":"time set 6000"}')" '"isError":false'
expect "and a read sees it" "$(call 11 run_command '{"command":"time query daytime"}')" 'The time is 60'
expect "an unrecognised command is not a plain write" "$(call 12 run_command '{"command":"nonsense"}')" 'this command needs admin access; use run_admin_command'
expect "a destructive command through run_command is refused" "$(call 13 run_command '{"command":"stop"}')" 'this command needs admin access; use run_admin_command'
expect "run_admin_command needs confirm=true" "$(call 14 run_admin_command '{"command":"stop","confirm":false}')" 'admin action requires confirmation'
expect "the server is still running after the refusals" "$(kill -0 "$SERVER_PID" && echo alive)" 'alive'

echo "== admin and typed tools"
expect "a confirmed admin command runs" "$(call 20 run_admin_command '{"command":"gamerule doDaylightCycle false","confirm":true}')" '"isError":false'
expect "and the server reports the change" "$(call 21 run_admin_command '{"command":"gamerule doDaylightCycle","confirm":true}')" 'false'
DRY="$(call 22 fill_blocks '{"dimension":"minecraft:overworld","min_x":10,"min_y":-58,"min_z":10,"max_x":11,"max_y":-57,"max_z":11,"block":"minecraft:emerald_block","dry_run":true}')"
expect "fill_blocks dry run reports the volume" "$DRY" '\"volume\":8'
expect "send_message tells everyone" "$(call 23 send_message '{"message":"hello from the smoke test"}')" '"isError":false'
expect "save_world needs confirmation" "$(call 24 save_world '{"confirm":false}')" 'requires confirmation'
expect "a confirmed save_world succeeds" "$(call 25 save_world '{"confirm":true}')" '"isError":false'
expect "the server log shows the broadcast" "$(call 26 read_log '{"lines":200}')" 'hello from the smoke test'

echo "== the replies the player list depends on"
call 30 run_admin_command '{"command":"forceload add 0 0","confirm":true}' > /dev/null
call 31 run_admin_command '{"command":"summon minecraft:armor_stand 0 -50 0","confirm":true}' > /dev/null
POS=""; UUIDS=""
for _ in $(seq 1 15); do
  POS="$(call 32 run_admin_command '{"command":"data get entity @e[type=armor_stand,limit=1] Pos","confirm":true}')"
  printf '%s' "$POS" | grep -q 'following entity data' && break
  sleep 1
done
UUIDS="$(call 33 run_admin_command '{"command":"data get entity @e[type=armor_stand,limit=1] UUID","confirm":true}')"
expect_match "data get entity Pos has the shape the player list parses" "$POS" 'has the following entity data: \[-?[0-9.]+d, -?[0-9.]+d, -?[0-9.]+d\]'
expect_match "data get entity UUID has the shape the player list parses" "$UUIDS" 'has the following entity data: \[I; -?[0-9]+, -?[0-9]+, -?[0-9]+, -?[0-9]+\]'

echo "== the wrong RCON password"
mkdir -p "$WORK/wrong" && cd "$WORK/wrong" || exit 2
MINECRAFT_MCP_ALLOW_WRITES=true MINECRAFT_MCP_PORT="$WRONG_PORT" MINECRAFT_MCP_RCON_PORT="$RCON_PORT" \
  MINECRAFT_MCP_RCON_PASSWORD="not the password" java -jar "$JAR" "$WORK/wrong" > wrong.log 2>&1 &
WRONG_PID=$!
wait_for 60 grep -q 'Minecraft MCP (universal)' wrong.log || fail "the second instance did not start"
PORT_SAVED="$PORT"; TOKEN_SAVED="$TOKEN"; SESSION_SAVED="$SESSION"
PORT="$WRONG_PORT"; CONFIG_FILE="$WORK/wrong/minecraft-mcp.json"; TOKEN="$(read_token)"; start_session
expect "a wrong password is reported, and does not break the server" "$(call 40 run_command '{"command":"list"}')" 'authentication failed'
refute "the error does not contain either password" "$(call 41 run_command '{"command":"list"}')" "$RCON_PASSWORD"
kill "$WRONG_PID" 2>/dev/null; WRONG_PID=""
PORT="$PORT_SAVED"; TOKEN="$TOKEN_SAVED"; SESSION="$SESSION_SAVED"; CONFIG_FILE="$MCP_DIR/minecraft-mcp.json"

echo "== audit and secrets"
AUDIT="$(cat "$MCP_DIR/minecraft-mcp-audit.jsonl")"
expect "the audit log records the calls" "$AUDIT" '"tool":"run_command"'
refute "the audit log does not contain the RCON password" "$AUDIT" "$RCON_PASSWORD"
refute "the audit log does not contain the MCP token" "$AUDIT" "$TOKEN"
refute "the universal jar's log does not contain the RCON password" "$(cat "$MCP_DIR/universal.log")" "$RCON_PASSWORD"
refute "the universal jar's log does not contain the MCP token" "$(cat "$MCP_DIR/universal.log")" "$TOKEN"
expect "the config file is readable only by its owner" "$(stat -c %a "$CONFIG_FILE")" '600'

echo "== shutdown through MCP (stop_server)"
expect "a confirmed stop_server is accepted" "$(call 50 stop_server '{"confirm":true}')" '"isError":false'
if wait_for 90 bash -c "! kill -0 $SERVER_PID 2>/dev/null"; then pass "the game server stopped"; else fail "the game server did not stop"; fi
kill -TERM "$UNIVERSAL_PID" 2>/dev/null
if wait_for 20 bash -c "! kill -0 $UNIVERSAL_PID 2>/dev/null"; then pass "the universal jar exits when asked to"; else fail "the universal jar did not exit"; fi
UNIVERSAL_PID=""
refute "the endpoint is closed afterwards" "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/mcp" 2>/dev/null)" '401'

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "== all checks passed"
else
  echo "== $FAILURES check(s) FAILED. Server log tail:"; tail -n 40 "$SERVER_DIR/server.log"
  echo "== universal log tail:"; tail -n 40 "$MCP_DIR/universal.log"
  exit 1
fi
