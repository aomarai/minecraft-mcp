#!/usr/bin/env bash
# Smoke test of the Fabric game client: starts a real client (Gradle's runClient) on a virtual display, connects
# to the client's MCP endpoint, and checks that the client-only tools are there and work. It creates a throwaway
# single-player world with the screen tools themselves, then moves, digs and places as the local player and
# checks the result against the integrated server's own endpoint.
#
#   scripts/smoke/client-smoke.sh <node>      e.g. 1.21.1, 26.2, 26.3
#
# Needs Java 21 and 25 (as the build does), xvfb-run, and a software OpenGL (Mesa). Without a display and without
# xvfb-run it says so and exits 0, so a machine that cannot run a client skips cleanly. It never accepts the
# Minecraft EULA or signs in: the development client plays offline under a throwaway name.
set -u
NODE="${1:?usage: client-smoke.sh <node, e.g. 1.21.1>}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
. "$ROOT/scripts/smoke/lib.sh"

if [ -z "${DISPLAY:-}" ] && ! command -v xvfb-run > /dev/null; then
  echo "SKIP: no display and no xvfb-run, so no game client can start here"
  exit 0
fi

RUN_DIR="$ROOT/adapters/fabric/versions/$NODE/run"
LOG="$(mktemp)"
rm -rf "$RUN_DIR"   # a fresh config, token and world every time

# The client endpoint writes; the integrated server's endpoint writes too (give_item). Both stay on loopback.
export LIBGL_ALWAYS_SOFTWARE=1
export MINECRAFT_MCP_CLIENT_ALLOW_WRITES=true
export MINECRAFT_MCP_ALLOW_WRITES=true
# Creating a world blocks the client for a while; give calls room.
export MINECRAFT_MCP_CLIENT_CALL_TIMEOUT_MS=60000

launch=(./gradlew ":adapters:fabric:$NODE:runClient" --no-daemon)
if [ -z "${DISPLAY:-}" ]; then
  launch=(xvfb-run -a -s "-screen 0 1280x720x24" "${launch[@]}")
fi
(cd "$ROOT" && exec setsid "${launch[@]}" > "$LOG" 2>&1) &
LAUNCHER=$!

cleanup() {
  # setsid made the launcher the leader of its own process group; end the whole group (Gradle, the game, Xvfb).
  kill -TERM -- "-$LAUNCHER" 2> /dev/null
  sleep 3
  kill -KILL -- "-$LAUNCHER" 2> /dev/null
}
trap cleanup EXIT

CLIENT_PORT=25598
SERVER_PORT=25599
N=100
c() { N=$((N + 1)); call "$N" "$@"; }
text_of() { printf '%s' "$1" | sed -n 's/^data: //p; t; /^{/p' | sed 's/\\"/"/g'; }
use_client() { PORT=$CLIENT_PORT; CONFIG_FILE="$RUN_DIR/config/minecraft-mcp-client.json"; TOKEN="$(read_token)"; start_session; }
use_server() { PORT=$SERVER_PORT; CONFIG_FILE="$RUN_DIR/config/minecraft-mcp.json"; TOKEN="$(read_token)"; start_session; }

echo "== waiting for the client (the first run downloads the game's assets)"
for _ in $(seq 1 120); do
  grep -q "MCP client server listening on port $CLIENT_PORT" "$LOG" && break
  kill -0 "$LAUNCHER" 2> /dev/null || break
  sleep 5
done
if ! grep -q "MCP client server listening on port $CLIENT_PORT" "$LOG"; then
  echo "FAIL: the client endpoint never started"; tail -60 "$LOG"; exit 1
fi
pass "the client endpoint started"

use_client
[ -n "$SESSION" ] && pass "initialize" || { fail "initialize"; exit 1; }

echo "== the client offers the client tools and none of the server ones"
tools="$(mcp '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')"
for tool in take_screenshot get_screen click_screen_element type_in_screen scroll_screen move_to look_at jump \
    break_block place_block interact; do
  expect "tools/list has $tool" "$tools" "\"name\":\"$tool\""
done
for tool in run_command list_players query_blocks stop_server; do
  refute "tools/list has no server tool $tool" "$tools" "\"name\":\"$tool\""
done

echo "== screenshot"
shot="$(c take_screenshot '{}')"
expect "take_screenshot returns an image" "$shot" '"type":"image"'
expect "take_screenshot returns a PNG" "$shot" '"mimeType":"image/png"'
[ "${#shot}" -gt 2000 ] && pass "the image is not empty (${#shot} characters of base64)" || fail "the image is tiny: ${#shot}"
[ "${#shot}" -lt 3000000 ] && pass "the image is within the size bound" || fail "the image is over the bound: ${#shot}"

echo "== screens: a new world, made with the screen tools"
screen_name() { text_of "$(c get_screen '{"limit":1}')" | sed -n 's/.*"screen":"\([A-Za-z]*\)".*/\1/p' | head -1; }
click_label() {
  local out id
  out="$(c get_screen "{\"label\":\"$1\",\"role\":\"button\"}")"
  id="$(printf '%s' "$out" | grep -o '[0-9]*:button' | head -1)"
  [ -n "$id" ] || { echo "no button '$1'"; return 1; }
  c click_screen_element "{\"element\":\"$id\"}" > /dev/null
}
seen=""
for i in $(seq 1 40); do
  name="$(screen_name)"
  seen="$seen $name"
  case "$name" in
    AccessibilityOnboardingScreen) click_label Continue ;;
    DisconnectedScreen) click_label "" ;;
    TitleScreen) click_label Singleplayer ;;
    SelectWorldScreen | CreateWorldScreen) click_label "Create New World" ;;
    "") [ "$i" -gt 3 ] && break ;;
  esac
  sleep 4
done
expect "get_screen read a real screen on the way (saw:$seen)" "$seen" "Screen"
[ -z "$(screen_name)" ] && pass "the player is in a world (no screen open)" || { fail "never reached a world (saw:$seen)"; tail -40 "$LOG"; exit 1; }

echo "== the player, checked against the integrated server"
sleep 8   # let the player land and the chunks settle
use_server
players="$(c list_players '{}')"
PNAME="$(text_of "$players" | sed -n 's/.*"name":"\([A-Za-z0-9_]*\)".*/\1/p' | head -1)"
expect "the integrated server sees the player" "$(text_of "$players")" "\"name\":\"$PNAME\""
pos() { use_server; text_of "$(c list_players '{}')" | grep -o '"x":[-0-9.]*,"y":[-0-9.]*,"z":[-0-9.]*' | head -1; }
floor_of() { awk -v v="$1" 'BEGIN { f = (v == int(v)) ? v : (v < 0 ? int(v) - 1 : int(v)); printf "%d", f }'; }
block_at() {
  use_server
  text_of "$(c query_blocks "{\"dimension\":\"minecraft:overworld\",\"min_x\":$1,\"min_y\":$2,\"min_z\":$3,\"max_x\":$1,\"max_y\":$2,\"max_z\":$3,\"limit\":4}")" \
    | grep -o '"id":"[a-z_:]*"' || echo "(air)"
}
P="$(pos)"
X="$(floor_of "$(printf '%s' "$P" | sed 's/"x":\([-0-9.]*\).*/\1/')")"
Y="$(floor_of "$(printf '%s' "$P" | sed 's/.*"y":\([-0-9.]*\),"z".*/\1/')")"
Z="$(floor_of "$(printf '%s' "$P" | sed 's/.*"z":\([-0-9.]*\)$/\1/')")"
echo "player $PNAME at $X,$Y,$Z"

# A solid block next to the one the player stands on: dig it, then put a block back.
BX=""; BZ=""
for off in "1 0" "-1 0" "0 1" "0 -1"; do
  set -- $off
  if [ "$(block_at $((X + $1)) $((Y - 1)) $((Z + $2)))" != "(air)" ]; then BX=$((X + $1)); BZ=$((Z + $2)); break; fi
done
if [ -z "$BX" ]; then
  fail "no solid block beside the player to dig"
else
  BY=$((Y - 1))
  before="$(block_at "$BX" "$BY" "$BZ")"
  use_client
  out="$(c break_block "{\"x\":$BX,\"y\":$BY,\"z\":$BZ}")"
  expect "break_block says it broke the block" "$out" "broke the block"
  sleep 1
  [ "$(block_at "$BX" "$BY" "$BZ")" = "(air)" ] && pass "the server agrees: $before is gone" || fail "the block is still there"
  # What the block dropped may already be in the hand (sand is), or nothing was (stone with bare hands): give dirt
  # for the second case, and accept whichever block ends up placed.
  use_server; c give_item "{\"player\":\"$PNAME\",\"item\":\"minecraft:dirt\",\"count\":4}" > /dev/null; sleep 1
  use_client
  out="$(c place_block "{\"x\":$BX,\"y\":$BY,\"z\":$BZ}")"
  expect "place_block says it placed a block" "$out" "placed"
  sleep 1
  [ "$(block_at "$BX" "$BY" "$BZ")" != "(air)" ] && pass "the server agrees: a block is there" || fail "nothing was placed"
  expect "placing on a taken position is refused" "$(c place_block "{\"x\":$BX,\"y\":$BY,\"z\":$BZ}")" "already a block"
  expect "a block out of reach is refused" "$(c break_block "{\"x\":$BX,\"y\":$BY,\"z\":$((BZ + 40))}")" "beyond the reach"
fi

use_client
expect "look_at answers" "$(c look_at "{\"x\":$X,\"y\":$Y,\"z\":$((Z + 6))}")" "facing yaw"
expect "jump answers" "$(c jump '{}')" "jumped"
sleep 1
expect "move_to arrives" "$(c move_to "{\"x\":$X.5,\"y\":$Y,\"z\":$((Z + 3)).5}")" "arrived at"

echo
if [ "$FAILURES" -gt 0 ]; then
  echo "$FAILURES client smoke check(s) failed"; tail -40 "$LOG"; exit 1
fi
echo "client smoke test passed"
