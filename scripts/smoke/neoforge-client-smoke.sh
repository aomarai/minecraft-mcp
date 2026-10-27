#!/usr/bin/env bash
# Smoke test of the NeoForge game client: starts a real client (Gradle's runClient, which loads the shaded mod jar
# from the run's mods/ folder) on a virtual display, joins a throwaway single-player world, and checks that the
# integrated server serves MCP on port 25599 like a dedicated server: server_info, list_players (the player is
# in the world) and one write (send_message). 26.x nodes need JDK 25 on the PATH.
#
#   scripts/smoke/neoforge-client-smoke.sh [node]      default 1.21.1
#
# Needs Java 21 (25 for the 26.x nodes), xvfb-run, a software OpenGL (Mesa) and network access (it fetches Mojang's server jar once to
# generate the flat world the client joins). Without a display and without xvfb-run it says so and exits 0.
# The world is made by Mojang's server with eula=true written into a throwaway folder; nothing is redistributed.
set -u
NODE="${1:-1.21.1}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
. "$ROOT/scripts/smoke/lib.sh"

if [ -z "${DISPLAY:-}" ] && ! command -v xvfb-run > /dev/null; then
  echo "SKIP: no display and no xvfb-run, so no game client can start here"
  exit 0
fi

RUN_DIR="$ROOT/adapters/neoforge/versions/$NODE/run"
WORK="$(mktemp -d)"
LOG="$WORK/client.log"
LAUNCHER=""
cleanup() {
  [ -n "$LAUNCHER" ] && { kill -TERM -- "-$LAUNCHER" 2> /dev/null; sleep 3; kill -KILL -- "-$LAUNCHER" 2> /dev/null; }
  rm -rf "$WORK"
}
trap cleanup EXIT

echo "== a flat world, made by Mojang's server"
MINECRAFT_VERSION="$(sed -n 's/^minecraft_version=//p' "$ROOT/adapters/neoforge/versions/$NODE/gradle.properties" | tr -d '\r')"
(
  cd "$WORK" || exit 1
  url="$(curl -s https://piston-meta.mojang.com/mc/game/version_manifest_v2.json \
    | python3 -c "import json,sys; m=json.load(sys.stdin); print([v['url'] for v in m['versions'] if v['id']=='$MINECRAFT_VERSION'][0])")"
  curl -s -o server.jar "$(curl -s "$url" | python3 -c "import json,sys; print(json.load(sys.stdin)['downloads']['server']['url'])")"
  echo eula=true > eula.txt
  printf 'level-name=smoke\nonline-mode=false\nlevel-type=minecraft\\:flat\ngenerate-structures=false\n' > server.properties
  java -jar server.jar nogui < <(sleep 40; echo stop) > server.log 2>&1
) || true
[ -f "$WORK/smoke/level.dat" ] && pass "a world was generated" || { fail "no world was generated"; exit 1; }

rm -rf "$RUN_DIR"
mkdir -p "$RUN_DIR/saves"
cp -r "$WORK/smoke" "$RUN_DIR/saves/smoke"
echo eula=true > "$RUN_DIR/eula.txt"
# No first-run accessibility screen, no blur (slow on a software renderer), a small world.
printf 'onboardAccessibility:false\ntutorialStep:none\nmenuBackgroundBlurriness:0\nrenderDistance:2\nsimulationDistance:5\nmaxFps:10\n' > "$RUN_DIR/options.txt"

echo "== starting the client"
export LIBGL_ALWAYS_SOFTWARE=1
export MINECRAFT_MCP_ALLOW_WRITES=true
launch=(./gradlew ":adapters:neoforge:$NODE:runClient" -PquickPlay=smoke --no-daemon)
[ -z "${DISPLAY:-}" ] && launch=(xvfb-run -a -s "-screen 0 854x480x24" "${launch[@]}")
(cd "$ROOT" && exec setsid "${launch[@]}" > "$LOG" 2>&1) &
LAUNCHER=$!

for _ in $(seq 1 120); do grep -q 'MCP server listening on port' "$LOG" 2> /dev/null && break; sleep 3; done
grep -q 'MCP server listening on port' "$LOG" && pass "the integrated server serves MCP in single-player" \
  || { fail "the integrated server never started serving"; tail -n 40 "$LOG"; exit 1; }

PORT=25599
CONFIG_FILE="$RUN_DIR/config/minecraft-mcp.json"
TOKEN="$(read_token)"
start_session
[ -n "$SESSION" ] && pass "initialize returned a session" || fail "initialize returned no session id"
expect "server_info says NeoForge on this Minecraft version" "$(call 2 server_info '{}')" "NeoForge"
# The server answers a moment before the player has finished joining, so ask again for a few seconds.
expect_eventually "list_players sees the player in the world" '\"dimension\":\"minecraft:overworld\"' 3 list_players '{}'
expect "a write goes through (send_message)" "$(call 4 send_message '{"message":"hello from the client smoke test"}')" '"isError":false'
sleep 1
expect "and it is attributed to MCP" "$(cat "$RUN_DIR/logs/latest.log")" "[MCP] hello from the client smoke test"

echo
if [ "$FAILURES" -gt 0 ]; then
  echo "$FAILURES NeoForge client smoke check(s) failed"; tail -n 40 "$LOG"; exit 1
fi
echo "NeoForge client smoke test passed"
