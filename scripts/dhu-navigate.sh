#!/usr/bin/env bash
# AutoBridge: navigate to a destination on the DHU, then drive the car there.
#
# This ties the two halves together. On its own, dhu-route.sh moves the GPS pin
# along coordinates you supply, and Google Maps on the car knows nothing about
# where you are headed. This script:
#
#   1. resolves the destination (a place name -> lat,lng via OpenStreetMap, or a
#      "lat,lng" pair used as-is);
#   2. pulls the REAL driving route origin -> destination from OSRM and writes it
#      as a .route file of waypoints;
#   3. fires a navigation intent at Google Maps on the car so it starts guiding
#      to that same destination;
#   4. hands the generated .route to dhu-route.sh so the simulated GPS pin
#      follows the exact road Maps just drew.
#
# Because both halves target one destination and one road geometry, the pin
# tracks the Maps route instead of wandering off it.
#
# Needs the internet (OSRM routing + OSM place lookup, both free, no API key).
#
#   A) Full run via dhu-run.sh (recommended) -- build/install/DHU + navigate:
#        scripts/dhu-run.sh --navigate "The Mall Bangkapi"
#
#   B) Against a DHU that is already running and connected:
#        scripts/dhu-navigate.sh "The Mall Bangkapi"
#        scripts/dhu-navigate.sh 13.7658,100.6428
#
#   Build only the route file, don't touch the car (no device needed):
#        NAV_NO_DEVICE=1 scripts/dhu-navigate.sh "The Mall Bangkapi"
#
# Env overrides:
#   NAV_FROM        origin "lat,lng" (default: 13.7303,100.5414, Lumphini).
#                   This is the car's "current position" the drive starts from.
#   NAV_KMH         cruising speed passed to dhu-route.sh (default: 50)
#   NAV_NO_MAPS     1 = skip the Google Maps navigation intent, just drive GPS
#   NAV_NO_DEVICE   1 = resolve + build the route file only; no adb, no DHU feed
#   NAV_ROUTE_OUT   where to write the generated route (default: a temp file)
#   ANDROID_SERIAL  adb serial for the Maps intent (default: first device)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ROUTE_SCRIPT="$ROOT/scripts/dhu-route.sh"
GEARHEAD=com.google.android.projection.gearhead

# Everything we print for the human goes to stderr. stdout is reserved for the
# sensor stream (emitted by dhu-route.sh at the end), so when this script is
# piped into the DHU only valid commands reach it, never our progress text.
log() { printf '%s\n' "$*" >&2; }

FROM="${NAV_FROM:-13.7303,100.5414}"
KMH="${NAV_KMH:-50}"
NO_MAPS="${NAV_NO_MAPS:-0}"
NO_DEVICE="${NAV_NO_DEVICE:-0}"

DEST_ARG=""
for arg in "$@"; do
  case "$arg" in
    -h|--help) sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)
      if [[ -z "$DEST_ARG" ]]; then DEST_ARG="$arg"
      else echo "!! unexpected extra argument: $arg" >&2; exit 2; fi ;;
  esac
done
if [[ -z "$DEST_ARG" ]]; then
  echo "!! usage: dhu-navigate.sh \"<place name>\" | <lat,lng>" >&2
  exit 2
fi

need() { command -v "$1" >/dev/null 2>&1 || { echo "!! missing required tool: $1" >&2; exit 1; }; }
need curl
need python3

# ---- 1. resolve destination to lat,lng ------------------------------------
# A bare "lat,lng" (optionally spaced) is taken as-is; anything else is a place
# name geocoded through OSM Nominatim.
latlng_re='^[[:space:]]*-?[0-9]+(\.[0-9]+)?[[:space:]]*,[[:space:]]*-?[0-9]+(\.[0-9]+)?[[:space:]]*$'
if [[ "$DEST_ARG" =~ $latlng_re ]]; then
  DEST_LAT="$(echo "$DEST_ARG" | cut -d, -f1 | tr -d ' ')"
  DEST_LNG="$(echo "$DEST_ARG" | cut -d, -f2 | tr -d ' ')"
  DEST_LABEL="$DEST_LAT,$DEST_LNG"
  log "== destination: $DEST_LABEL (coordinates) =="
else
  log "== geocoding \"$DEST_ARG\" via OpenStreetMap... =="
  GEO=$(curl -s --max-time 20 \
    -A "AutoBridge-DHU-sim/1.0" \
    --get "https://nominatim.openstreetmap.org/search" \
    --data-urlencode "q=$DEST_ARG" \
    --data-urlencode "format=json" \
    --data-urlencode "limit=1") || GEO=""
  read -r DEST_LAT DEST_LNG DEST_LABEL <<EOF
$(printf '%s' "$GEO" | python3 -c '
import sys, json
try:
    a = json.load(sys.stdin)
except Exception:
    a = []
if not a:
    print(""); sys.exit(0)
r = a[0]
name = (r.get("display_name","") or "").split(",")[0].strip() or "destination"
print(r["lat"], r["lon"], name.replace(" ", "_"))
')
EOF
  if [[ -z "${DEST_LAT:-}" || -z "${DEST_LNG:-}" ]]; then
    echo "!! could not geocode \"$DEST_ARG\". Try a more specific name or pass lat,lng." >&2
    exit 1
  fi
  DEST_LABEL="${DEST_LABEL//_/ }"
  log "   -> $DEST_LABEL  ($DEST_LAT, $DEST_LNG)"
fi

FROM_LAT="$(echo "$FROM" | cut -d, -f1 | tr -d ' ')"
FROM_LNG="$(echo "$FROM" | cut -d, -f2 | tr -d ' ')"

# ---- 2. fetch the real driving route from OSRM ----------------------------
log "== fetching driving route from OSRM =="
log "   from $FROM_LAT,$FROM_LNG  ->  to $DEST_LAT,$DEST_LNG"
# OSRM wants lng,lat; geojson geometry is a list of [lng,lat] points.
OSRM_URL="https://router.project-osrm.org/route/v1/driving/${FROM_LNG},${FROM_LAT};${DEST_LNG},${DEST_LAT}?overview=full&geometries=geojson"
OSRM_JSON=$(curl -s --max-time 30 "$OSRM_URL") || OSRM_JSON=""

ROUTE_OUT="${NAV_ROUTE_OUT:-$(mktemp -t dhu-nav-route.XXXXXX)}"
SUMMARY=$(printf '%s' "$OSRM_JSON" | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print("ERR parse"); sys.exit(0)
if d.get("code") != "Ok" or not d.get("routes"):
    print("ERR " + str(d.get("code"))); sys.exit(0)
r = d["routes"][0]
coords = r["geometry"]["coordinates"]   # [lng, lat]
import io
lines = []
for lng, lat in coords:
    lines.append("%.6f %.6f" % (lat, lng))   # emit lat lng
open("'"$ROUTE_OUT"'", "w").write(
    "# OSRM driving route, %d points, %d m, %d s\n" % (len(coords), round(r["distance"]), round(r["duration"]))
    + "\n".join(lines) + "\n")
print("OK %d %d %d" % (len(coords), round(r["distance"]), round(r["duration"])))
')

case "$SUMMARY" in
  OK\ *)
    read -r _ NPTS DIST DUR <<< "$SUMMARY"
    log "   route: $NPTS points, $((DIST/1000)).$(( (DIST%1000)/100 )) km, ~$((DUR/60)) min"
    log "   waypoints written: $ROUTE_OUT" ;;
  *)
    echo "!! OSRM did not return a route ($SUMMARY)." >&2
    echo "   Check connectivity, or pass your own origin via NAV_FROM." >&2
    exit 1 ;;
esac

if [[ "$NO_DEVICE" == 1 ]]; then
  log ""
  log "== NAV_NO_DEVICE set: route file ready, skipping car + DHU =="
  log "   feed it later with:  scripts/dhu-route.sh $ROUTE_OUT"
  exit 0
fi

# ---- 3. tell Google Maps on the car to navigate there ---------------------
if [[ "$NO_MAPS" != 1 ]]; then
  SERIAL_ARGS=()
  [[ -n "${ANDROID_SERIAL:-}" ]] && SERIAL_ARGS=(-s "$ANDROID_SERIAL")
  if command -v adb >/dev/null 2>&1; then
    log ""
    log "== starting Google Maps navigation to $DEST_LABEL =="
    # google.navigation: is the documented turn-by-turn deep link. mode=d = drive.
    # Routed through gearhead so it opens on the projected car display.
    NAV_URI="google.navigation:q=${DEST_LAT},${DEST_LNG}&mode=d"
    if adb "${SERIAL_ARGS[@]}" shell am start -a android.intent.action.VIEW \
      -d "'$NAV_URI'" >/dev/null 2>&1; then
      log "   navigation intent sent."
    else
      log "   (could not send navigation intent; is a device connected?)"
    fi
    # Give Maps a moment to compute the route and switch to guidance.
    sleep 4
  else
    log "!! adb not on PATH; skipping the Maps navigation intent."
  fi
fi

# ---- 4. drive the GPS pin along the fetched route -------------------------
log ""
log "== driving the route (pin follows the Maps road) =="
[[ -x "$ROUTE_SCRIPT" ]] || chmod +x "$ROUTE_SCRIPT" 2>/dev/null || true
# When stdout here is a terminal and no DHU is downstream, dhu-route.sh just
# prints the stream (dry run). In the real flow dhu-run.sh pipes us into the DHU.
ROUTE_KMH="$KMH" exec "$ROUTE_SCRIPT" "$ROUTE_OUT"
