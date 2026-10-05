#!/usr/bin/env bash
# AutoBridge: build + install a variant, wire up adb, and launch the Desktop
# Head Unit (DHU) in one command.
#
# Run this in a REAL Terminal.app / iTerm window (not an IDE task runner): the
# DHU and the screenshot helpers need Screen Recording / Accessibility
# permission, which only a real terminal is granted.
#
# What it does:
#   1. assembles+installs the chosen variant onto the connected phone
#   2. clears and re-adds the tcp:5277 forward the DHU talks over
#   3. launches the DHU with the chosen screen config and input mode
#
# Prereqs:
#   - phone connected over adb (USB, or Wi-Fi via `adb connect`)
#   - Android Auto on the phone: Developer mode on, then
#     three-dot menu -> "Start head unit server" BEFORE (or right after) running
#     this. The head unit server accepts ONE connection: if the DHU drops, stop
#     and start the server again before re-running.
#
# Usage:
#   scripts/dhu-run.sh                       # safe debug, 720 config, touch
#   scripts/dhu-run.sh personal              # personal debug variant
#   scripts/dhu-run.sh safe 720-hidpi        # pick a docs/dhu/*.ini config
#   scripts/dhu-run.sh lab small rotary      # flavor + config + input mode
#   scripts/dhu-run.sh --no-build safe       # skip gradle, just forward + DHU
#
# Env overrides:
#   ANDROID_SERIAL   adb serial of the phone (default below)
#   DHU_HOME         DHU install dir (default: SDK extras/google/auto)
set -uo pipefail

# ---- defaults -------------------------------------------------------------
SERIAL="${ANDROID_SERIAL:-YXEMRCGYAI49S4SS}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
DHU_HOME="${DHU_HOME:-$SDK/extras/google/auto}"
PORT=5277

BUILD=1
FLAVOR="safe"
CONFIG="720"
INPUT="touch"

# ---- parse args -----------------------------------------------------------
# Positional, order-independent-ish: --no-build is a flag; the first bare word
# that matches a flavor sets FLAVOR, a word matching a docs/dhu/*.ini sets
# CONFIG, and touch|rotary|hybrid sets INPUT.
for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=0 ;;
    safe|personal|lab) FLAVOR="$arg" ;;
    touch|rotary|hybrid) INPUT="$arg" ;;
    -h|--help)
      sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *)
      if [[ -f "$ROOT/docs/dhu/$arg.ini" ]]; then
        CONFIG="$arg"
      else
        echo "!! unknown argument: $arg" >&2
        echo "   flavors: safe|personal|lab   configs: $(cd "$ROOT/docs/dhu" && ls *.ini | sed 's/\.ini//' | tr '\n' ' ')  input: touch|rotary|hybrid" >&2
        exit 2
      fi ;;
  esac
done

INI="$ROOT/docs/dhu/$CONFIG.ini"
ADB=(adb -s "$SERIAL")
# Capitalize the flavor for the Gradle task name: safe -> installSafeDebug.
TASK="install$(tr '[:lower:]' '[:upper:]' <<<"${FLAVOR:0:1}")${FLAVOR:1}Debug"

echo "== config =="
echo "   flavor : $FLAVOR  (gradle: $TASK)"
echo "   screen : $CONFIG  ($INI)"
echo "   input  : $INPUT"
echo "   device : $SERIAL"
echo "   DHU    : $DHU_HOME/desktop-head-unit"
echo

# ---- sanity checks --------------------------------------------------------
if ! command -v adb >/dev/null 2>&1; then
  echo "!! adb not on PATH. Add \$ANDROID_SDK_ROOT/platform-tools." >&2
  exit 1
fi
if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "!! device $SERIAL not connected. 'adb devices' to check, or set ANDROID_SERIAL." >&2
  exit 1
fi
if [[ ! -x "$DHU_HOME/desktop-head-unit" ]]; then
  echo "!! DHU not found at $DHU_HOME/desktop-head-unit" >&2
  echo "   Install it: Android Studio > SDK Manager > SDK Tools >" >&2
  echo "   'Android Auto Desktop Head Unit Emulator', or set DHU_HOME." >&2
  exit 1
fi
if [[ ! -f "$INI" ]]; then
  echo "!! screen config not found: $INI" >&2
  exit 1
fi

# ---- 1. build + install ---------------------------------------------------
if [[ "$BUILD" -eq 1 ]]; then
  echo "== building + installing $FLAVOR debug =="
  ( cd "$ROOT" && ANDROID_SERIAL="$SERIAL" ./gradlew "$TASK" ) || {
    echo "!! gradle $TASK failed" >&2; exit 1; }
else
  echo "== skipping build (--no-build) =="
fi

# ---- 2. adb forward -------------------------------------------------------
echo "== wiring adb forward tcp:$PORT =="
"${ADB[@]}" forward --remove-all >/dev/null 2>&1 || true
"${ADB[@]}" forward "tcp:$PORT" "tcp:$PORT" >/dev/null
"${ADB[@]}" forward --list

# ---- 3. launch DHU --------------------------------------------------------
echo
echo "== launching DHU =="
echo "   If the DHU connects but shows no video, the head unit server was"
echo "   already claimed by a previous session: stop + start it again on the"
echo "   phone (Android Auto > three-dot menu), then re-run with --no-build."
echo "   Quit the DHU with Ctrl-C in this window."
echo
cd "$DHU_HOME"
exec ./desktop-head-unit -c "$INI" -i "$INPUT"
