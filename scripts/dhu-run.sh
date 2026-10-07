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
#   scripts/dhu-run.sh safe 1080            # pick a screen config
#   scripts/dhu-run.sh lab 720 rotary
#   screen configs: small 720 720-hidpi 1080 1080-hidpi 1440 portrait
#   scripts/dhu-run.sh --no-restart          # leave Android Auto alone
#   scripts/dhu-run.sh --drive               # simulate a moving car (city)
#   scripts/dhu-run.sh --drive highway       # simulate a highway drive
#   scripts/dhu-run.sh --no-build --drive    # just drive an installed build
#   scripts/dhu-run.sh --route scripts/routes/bangkok-demo.route
#                                            # follow a route on the map
#   scripts/dhu-run.sh --navigate "The Mall Bangkapi"
#                                            # Maps-navigate + drive there
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
CONFIG="720"
INPUT="touch"
DRIVE=0
DRIVE_SCENARIO="city"
ROUTE_FILE=""
WANT_ROUTE_VALUE=0
NAV_DEST=""
WANT_NAV_VALUE=0

# Screen configs resolved in order: scripts/dhu/ (if present), then docs/dhu/ (where this repo's
# profiles live: small 720 720-hidpi 1080 1080-hidpi 1440 portrait), then the DHU's stock configs.
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
  # --route / --navigate take the next token as their value.
  if [[ "$WANT_ROUTE_VALUE" -eq 1 ]]; then
    ROUTE_FILE="$arg"
    WANT_ROUTE_VALUE=0
    continue
  fi
  if [[ "$WANT_NAV_VALUE" -eq 1 ]]; then
    NAV_DEST="$arg"
    WANT_NAV_VALUE=0
    continue
  fi
  case "$arg" in
    --no-build) BUILD=0 ;;
    --no-restart) RESTART=0 ;;
    --drive) DRIVE=1 ;;
    --route) WANT_ROUTE_VALUE=1 ;;
    --route=*) ROUTE_FILE="${arg#--route=}" ;;
    --navigate|--nav) WANT_NAV_VALUE=1 ;;
    --navigate=*) NAV_DEST="${arg#--navigate=}" ;;
    --nav=*) NAV_DEST="${arg#--nav=}" ;;
    park|city|highway) DRIVE_SCENARIO="$arg" ;;
    safe|personal|lab) FLAVOR="$arg" ;;
    touch|rotary|hybrid) INPUT="$arg" ;;
    -h|--help)
      sed -n '2,39p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *)
      if config_ini "$arg" >/dev/null; then
        CONFIG="$arg"
      else
        echo "!! unknown argument: $arg" >&2
        echo "   flavors: safe|personal|lab   input: touch|rotary|hybrid" >&2
        echo "   drive scenarios: park|city|highway (with --drive)" >&2
        echo "   route: --route <file>  (follow a map route)" >&2
        echo "   configs: $(config_names)" >&2
        exit 2
      fi ;;
  esac
done
if [[ "$WANT_ROUTE_VALUE" -eq 1 ]]; then
  echo "!! --route needs a file path argument" >&2
  exit 2
fi
if [[ "$WANT_NAV_VALUE" -eq 1 ]]; then
  echo "!! --navigate needs a destination (place name or lat,lng)" >&2
  exit 2
fi
# --navigate / --route both imply drive mode. Precedence: navigate > route.
if [[ -n "$NAV_DEST" || -n "$ROUTE_FILE" ]]; then
  DRIVE=1
fi

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
if [[ -n "$NAV_DEST" ]]; then
  echo "   drive  : navigate to '$NAV_DEST'  (Maps + GPS follows real road)"
elif [[ -n "$ROUTE_FILE" ]]; then
  echo "   drive  : route $ROUTE_FILE  (following map waypoints)"
elif [[ "$DRIVE" -eq 1 ]]; then
  echo "   drive  : $DRIVE_SCENARIO  (simulated moving car)"
fi
echo

# The sensor feeders live next to this script and pipe into the DHU on stdin.
DRIVE_SCRIPT="$ROOT/scripts/dhu-drive.sh"
ROUTE_SCRIPT="$ROOT/scripts/dhu-route.sh"
NAV_SCRIPT="$ROOT/scripts/dhu-navigate.sh"
if [[ -n "$NAV_DEST" ]]; then
  if [[ ! -f "$NAV_SCRIPT" ]]; then
    echo "!! --navigate requested but $NAV_SCRIPT is missing" >&2
    exit 1
  fi
  [[ -x "$NAV_SCRIPT" ]] || chmod +x "$NAV_SCRIPT" 2>/dev/null || true
elif [[ -n "$ROUTE_FILE" ]]; then
  if [[ ! -f "$ROUTE_SCRIPT" ]]; then
    echo "!! --route requested but $ROUTE_SCRIPT is missing" >&2
    exit 1
  fi
  [[ -x "$ROUTE_SCRIPT" ]] || chmod +x "$ROUTE_SCRIPT" 2>/dev/null || true
  if [[ ! -f "$ROUTE_FILE" ]]; then
    echo "!! route file not found: $ROUTE_FILE" >&2
    exit 1
  fi
elif [[ "$DRIVE" -eq 1 ]]; then
  if [[ ! -f "$DRIVE_SCRIPT" ]]; then
    echo "!! --drive requested but $DRIVE_SCRIPT is missing" >&2
    exit 1
  fi
  [[ -x "$DRIVE_SCRIPT" ]] || chmod +x "$DRIVE_SCRIPT" 2>/dev/null || true
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
#
# We do NOT try to launch the head unit server directly: the activity the three-dot "Start head
# unit server" menu opens is not exported and its name changes across gearhead versions, so an
# `am start -n ...` guess fails on real devices. Instead we just open Android Auto so the menu is
# one tap away, then WAIT until the server is genuinely listening before launching the DHU.

# True only if the head unit server is actually listening on tcp:5277 INSIDE the device.
#
# Probing the forwarded port on this host is NOT a valid test: `adb forward` accepts the host-side
# connection even when nothing listens on the device, so the DHU prints "connected." and then dies
# with "Failed to read from transport - disconnect". The authoritative check is the device's own
# socket table: port 5277 == 0x1495, and a LISTEN socket shows state 0A. We read both IPv4
# (/proc/net/tcp) and IPv6 (/proc/net/tcp6).
server_is_up() {
  local out
  out="$("${ADB[@]}" shell 'cat /proc/net/tcp /proc/net/tcp6 2>/dev/null' 2>/dev/null)" || return 1
  # Columns: sl  local_address rem_address st ...   local_address is HEX_IP:HEX_PORT.
  # Match local port 1495 (=5277) in state 0A (LISTEN).
  awk '
    { split($2, l, ":");
      if (toupper(l[2]) == "1495" && toupper($4) == "0A") { found = 1 }
    }
    END { exit(found ? 0 : 1) }
  ' <<< "$out"
}

# Open the Android Auto app on the device via its launcher intent. This does not need an exported
# component name - it is the normal "tap the app icon" path - so it works across versions. It just
# brings the UI up so the "Start head unit server" menu is reachable; it does not start the server.
open_android_auto() {
  "${ADB[@]}" shell monkey -p "$GEARHEAD" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
}

if [[ "$RESTART" -eq 1 ]]; then
  echo
  echo "== force-stopping Android Auto =="
  "${ADB[@]}" shell am force-stop "$GEARHEAD"

  if server_is_up; then
    echo "   head unit server is already up (tcp:$PORT listening on device)."
  else
    echo "== opening Android Auto on the device =="
    open_android_auto
    echo "   on the device: Android Auto > three-dot menu > 'Start head unit server'"
    echo
    if [[ -t 0 ]]; then
      echo "   waiting for the server to actually listen on the device..."
      echo "   (press Enter to launch the DHU anyway, Ctrl-C to abort)"
      waited=0
      until server_is_up; do
        # read with a 1s timeout doubles as the poll delay: Enter breaks out immediately,
        # otherwise we loop and re-probe the device's own socket table.
        if read -r -t 1 _; then
          echo "   launching anyway on your request (server not detected yet)."
          break
        fi
        waited=$((waited + 1))
        if [[ $((waited % 15)) -eq 0 ]]; then
          echo "   still waiting (${waited}s). Tap 'Start head unit server' on the phone, or press Enter."
        fi
      done
      if server_is_up; then
        echo "   head unit server is up (tcp:$PORT listening on device). launching DHU."
      fi
    else
      # Non-interactive (IDE task runner): can't prompt. Poll up to 60s for the server, then proceed
      # and let the DHU surface the error if it never came up.
      waited=0
      until server_is_up; do
        sleep 1
        waited=$((waited + 1))
        [[ "$waited" -ge 60 ]] && { echo "   server not detected after ${waited}s; launching DHU anyway."; break; }
      done
      server_is_up && echo "   head unit server is up (tcp:$PORT listening on device)."
    fi
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

# Assemble the DHU command once; -c is only added when we resolved a config.
DHU_CMD=(./desktop-head-unit -i "$INPUT")
if [[ -n "$INI" ]]; then
  DHU_CMD=(./desktop-head-unit -c "$INI" -i "$INPUT")
fi

if [[ -n "$NAV_DEST" ]]; then
  # Navigate: dhu-navigate.sh resolves the destination, fetches the real road,
  # fires the Maps nav intent at the car, then streams GPS fixes along that
  # road. We pipe that stream into the DHU's stdin. It passes ANDROID_SERIAL and
  # the chosen origin/speed through the environment.
  echo "   navigating to '$NAV_DEST' and driving there on the DHU..."
  echo
  ANDROID_SERIAL="$SERIAL" NAV_KMH="${NAV_KMH:-}" \
    "$NAV_SCRIPT" "$NAV_DEST" | "${DHU_CMD[@]}"
elif [[ -n "$ROUTE_FILE" ]]; then
  # Follow a map route: pipe the GPS/speed/gear stream into the DHU's stdin.
  # The DHU keeps running after the car arrives (it sits parked), so we can't
  # exec; the pipeline's exit status follows the DHU, which is what we want.
  echo "   following route '$ROUTE_FILE' on the DHU..."
  echo
  "$ROUTE_SCRIPT" "$ROUTE_FILE" | "${DHU_CMD[@]}"
elif [[ "$DRIVE" -eq 1 ]]; then
  # Feed the simulated-car sensor stream into the DHU's stdin.
  echo "   feeding '$DRIVE_SCENARIO' drive simulation into the DHU..."
  echo
  "$DRIVE_SCRIPT" "$DRIVE_SCENARIO" | "${DHU_CMD[@]}"
else
  exec "${DHU_CMD[@]}"
fi
