#!/usr/bin/env bash
# AutoBridge: build + install a variant, wire up adb, restart Android Auto and
# launch the Desktop Head Unit (DHU) in one command.
#
# Run this in a REAL Terminal.app / iTerm window (not an IDE task runner): the
# DHU and the screenshot helpers need Screen Recording / Accessibility
# permission, which only a real terminal is granted.
#
# What it does:
#   1. assembles+installs the chosen variant onto the connected device
#   2. clears and re-adds the tcp:5277 forward the DHU talks over
#   3. force-stops Android Auto, then waits for you to start the head unit
#      server (the one step that cannot be done from this side)
#   4. launches the DHU with the chosen screen config and input mode
#
# Prereqs:
#   - device connected over adb (USB, or Wi-Fi via `adb connect`)
#   - Android Auto on the device: Developer mode on, so the three-dot menu
#     offers "Start head unit server"
#
# Usage:
#   scripts/dhu-run.sh                       # safe debug, 720p, touch
#   scripts/dhu-run.sh --no-build            # launch only: no gradle, just
#                                            # forward + restart + DHU
#   scripts/dhu-run.sh personal              # personal debug variant
#   scripts/dhu-run.sh safe default_1080p    # pick a screen config
#   scripts/dhu-run.sh lab default_720p rotary
#   scripts/dhu-run.sh --no-restart          # leave Android Auto alone
#
# Env overrides:
#   ANDROID_SERIAL   adb serial (default: the connected device that has the app)
#   DHU_HOME         DHU install dir (default: SDK extras/google/auto)
set -uo pipefail

# ---- defaults -------------------------------------------------------------
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
DHU_HOME="${DHU_HOME:-$SDK/extras/google/auto}"
PORT=5277
GEARHEAD=com.google.android.projection.gearhead
PACKAGE=dev.autobridge

SERIAL="${ANDROID_SERIAL:-}"
BUILD=1
RESTART=1
FLAVOR="safe"
CONFIG="default_720p"
INPUT="touch"

# Screen configs: this repo's own first, then the DHU's stock ones. `docs/dhu/` is where they used
# to live and is no longer tracked, so it is a fallback rather than the source.
config_ini() {
  local name="$1" dir
  for dir in "$ROOT/scripts/dhu" "$ROOT/docs/dhu" "$DHU_HOME/config"; do
    if [[ -f "$dir/$name.ini" ]]; then
      echo "$dir/$name.ini"
      return 0
    fi
  done
  return 1
}

config_names() {
  local dir
  for dir in "$ROOT/scripts/dhu" "$ROOT/docs/dhu" "$DHU_HOME/config"; do
    [[ -d "$dir" ]] && (cd "$dir" && ls ./*.ini 2>/dev/null | sed 's|\./||;s|\.ini$||')
  done | sort -u | tr '\n' ' '
}

# The device to project from is the connected one that actually has AutoBridge on it. A hardcoded
# serial was wrong the moment the app moved to another handset, and a DHU pointed at a device
# without the app projects a car screen with nothing of ours on it. `pm path` exits 0 either way on
# some builds, so the test is whether it printed anything.
detect_serial() {
  local serials serial
  serials=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  [[ -z "$serials" ]] && return 1
  while read -r serial; do
    [[ -z "$serial" ]] && continue
    if [[ -n "$(adb -s "$serial" shell pm path "$PACKAGE" 2>/dev/null)" ]]; then
      echo "$serial"
      return 0
    fi
  done <<< "$serials"
  head -n1 <<< "$serials"
}

# ---- parse args -----------------------------------------------------------
# Positional, order-independent-ish: the flags are flags; the first bare word
# that matches a flavor sets FLAVOR, a word matching a known .ini sets CONFIG,
# and touch|rotary|hybrid sets INPUT.
for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=0 ;;
    --no-restart) RESTART=0 ;;
    safe|personal|lab) FLAVOR="$arg" ;;
    touch|rotary|hybrid) INPUT="$arg" ;;
    -h|--help)
      sed -n '2,33p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *)
      if config_ini "$arg" >/dev/null; then
        CONFIG="$arg"
      else
        echo "!! unknown argument: $arg" >&2
        echo "   flavors: safe|personal|lab   input: touch|rotary|hybrid" >&2
        echo "   configs: $(config_names)" >&2
        exit 2
      fi ;;
  esac
done

# ---- sanity checks --------------------------------------------------------
if ! command -v adb >/dev/null 2>&1; then
  echo "!! adb not on PATH. Add \$ANDROID_SDK_ROOT/platform-tools." >&2
  exit 1
fi
if [[ -z "$SERIAL" ]]; then
  SERIAL="$(detect_serial)"
  if [[ -z "$SERIAL" ]]; then
    echo "!! no adb device connected. 'adb devices' to check, or set ANDROID_SERIAL." >&2
    exit 1
  fi
fi
ADB=(adb -s "$SERIAL")
INI="$(config_ini "$CONFIG")" || INI=""
# Capitalize the flavor for the Gradle task name: safe -> installSafeDebug.
TASK="install$(tr '[:lower:]' '[:upper:]' <<<"${FLAVOR:0:1}")${FLAVOR:1}Debug"

echo "== config =="
echo "   flavor : $FLAVOR  (gradle: $TASK)"
echo "   screen : $CONFIG  (${INI:-DHU built-in default})"
echo "   input  : $INPUT"
echo "   device : $SERIAL"
echo "   DHU    : $DHU_HOME/desktop-head-unit"
echo

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

# ---- 3. restart Android Auto ----------------------------------------------
# The head unit server serves exactly ONE connection, and a gearhead process left from the previous
# session is still holding it. Skip this and the DHU reports `connected` and then dies with
# USB_ISSUE_PROJECTION_NOT_STARTED - which reads like an app fault and is not one.
if [[ "$RESTART" -eq 1 ]]; then
  echo
  echo "== force-stopping Android Auto =="
  "${ADB[@]}" shell am force-stop "$GEARHEAD"
  echo "   now on the device: Android Auto > three-dot menu > 'Start head unit server'"
  if [[ -t 0 ]]; then
    printf '   press Enter once it is started (Ctrl-C to abort)... '
    read -r _
  fi
fi

# ---- 4. launch DHU --------------------------------------------------------
echo
echo "== launching DHU =="
echo "   If the DHU connects but shows no video, the head unit server was"
echo "   already claimed: re-run this script (the force-stop above is the fix)."
echo "   Quit the DHU with Ctrl-C in this window."
echo
cd "$DHU_HOME"
if [[ -n "$INI" ]]; then
  exec ./desktop-head-unit -c "$INI" -i "$INPUT"
else
  exec ./desktop-head-unit -i "$INPUT"
fi
