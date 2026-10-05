#!/usr/bin/env bash
# AutoBridge: simulate a MOVING CAR against a running Desktop Head Unit.
#
# The DHU reads one command per line on its stdin and injects it into the
# projected session as if the car's own sensors reported it. This script feeds
# it a sequence of GPS / speed / gear updates so the phone (and AutoBridge)
# believes the car is parked, then shifts to drive, then rolls along a route.
#
# Use it to exercise anything that is gated on vehicle state: drive-mode
# lockouts, parked-only screens, speed-dependent UI, "do not interact while
# driving" templates, GPS-follow behaviour, etc.
#
# IMPORTANT: this does NOT launch the DHU. It drives one that is already
# running and connected. Two ways to use it:
#
#   A) Pipe into a DHU you launch yourself:
#        scripts/dhu-drive.sh | <DHU_HOME>/desktop-head-unit -c <cfg> -i touch
#
#   B) Let dhu-run.sh wire it up for you (recommended):
#        scripts/dhu-run.sh --drive            # launch DHU + feed a drive
#        scripts/dhu-run.sh --drive highway    # pick a scenario
#
# When run on its own with stdout attached to a terminal it just PRINTS the
# command stream (a dry run) so you can see exactly what would be injected:
#        scripts/dhu-drive.sh city
#
# Scenarios (first bare argument):
#   park      park -> short idle, no movement        (gear stays in park)
#   city      park -> drive, ~30-50 km/h stop-and-go  (default)
#   highway   park -> drive, accelerate to ~110 km/h, cruise
#
# Env overrides:
#   DRIVE_LAT / DRIVE_LNG   start coordinate (default: central Bangkok)
#   DRIVE_BEARING           initial heading in degrees (default: 90, due east)
#   DRIVE_STEP_SEC          seconds between sensor updates (default: 1.0)
#   DRIVE_LOOPS             number of movement steps to emit (default: per scenario)
set -uo pipefail

SCENARIO="city"
for arg in "$@"; do
  case "$arg" in
    park|city|highway) SCENARIO="$arg" ;;
    -h|--help)
      sed -n '2,46p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *)
      echo "!! unknown scenario: $arg  (park|city|highway)" >&2
      exit 2 ;;
  esac
done

# ---- route + motion model -------------------------------------------------
LAT="${DRIVE_LAT:-13.746600}"      # central Bangkok, near Lumphini
LNG="${DRIVE_LNG:-100.535500}"
BEARING="${DRIVE_BEARING:-90}"     # degrees, 0=N 90=E 180=S 270=W
STEP="${DRIVE_STEP_SEC:-1.0}"

case "$SCENARIO" in
  park)    LOOPS="${DRIVE_LOOPS:-8}";  TARGET_KMH=0   ;;
  city)    LOOPS="${DRIVE_LOOPS:-40}"; TARGET_KMH=45  ;;
  highway) LOOPS="${DRIVE_LOOPS:-60}"; TARGET_KMH=110 ;;
esac

# Degrees of latitude per metre is ~constant; longitude scales by cos(lat).
# One metre of latitude  ~= 1 / 111320 deg.
# One metre of longitude ~= 1 / (111320 * cos(lat)) deg.
# We step the position each tick by (speed_mps * step_sec) metres along BEARING.

emit() { printf '%s\n' "$1"; }

# Pretty-print a float with 6 decimals using awk (portable, no bc dependency).
f6() { awk -v v="$1" 'BEGIN{ printf "%.6f", v }'; }
f1() { awk -v v="$1" 'BEGIN{ printf "%.1f", v }'; }

# ---- 0. announce ----------------------------------------------------------
# Everything after `#` on a line is a comment the DHU ignores, so these are
# safe to pipe straight in.
emit "# AutoBridge drive simulation: scenario=$SCENARIO, ${LOOPS} steps @ ${STEP}s, target ${TARGET_KMH} km/h"

# ---- 1. parked ------------------------------------------------------------
emit "sensor gear park"
emit "sensor speed 0"
emit "sensor gps $(f6 "$LAT") $(f6 "$LNG") $(f1 "$BEARING") 0 5"
emit "# parked and idle"
# A few idle ticks so parked-only UI has time to settle.
for _ in 1 2 3; do
  emit "sensor speed 0"
  emit "sleep ${STEP}"
done

if [[ "$SCENARIO" == "park" ]]; then
  for _ in $(seq 1 "$LOOPS"); do
    emit "sensor speed 0"
    emit "sleep ${STEP}"
  done
  emit "# end of parked scenario"
  exit 0
fi

# ---- 2. shift to drive and roll out ---------------------------------------
emit "sensor gear drive"
emit "# shifted to drive; accelerating toward ${TARGET_KMH} km/h"

cur_lat="$LAT"
cur_lng="$LNG"
cur_kmh=0

# Simple trapezoid speed profile: ramp up, cruise, ease down near the end.
ramp=$(( LOOPS / 4 )); [[ "$ramp" -lt 1 ]] && ramp=1
coast_start=$(( LOOPS - ramp ))

for i in $(seq 1 "$LOOPS"); do
  if   [[ "$i" -le "$ramp" ]]; then
    cur_kmh=$(awk -v t="$TARGET_KMH" -v i="$i" -v r="$ramp" 'BEGIN{ printf "%.1f", t * i / r }')
  elif [[ "$i" -ge "$coast_start" ]]; then
    left=$(( LOOPS - i ))
    cur_kmh=$(awk -v t="$TARGET_KMH" -v l="$left" -v r="$ramp" 'BEGIN{ printf "%.1f", t * l / r }')
  else
    # City scenario does gentle stop-and-go around the target; highway cruises.
    if [[ "$SCENARIO" == "city" ]]; then
      cur_kmh=$(awk -v t="$TARGET_KMH" -v i="$i" 'BEGIN{ printf "%.1f", t * (0.6 + 0.4*((i%6)/6.0)) }')
    else
      cur_kmh="$TARGET_KMH"
    fi
  fi

  # km/h -> m/s, then advance the position along the bearing.
  read -r cur_lat cur_lng <<EOF
$(awk -v lat="$cur_lat" -v lng="$cur_lng" -v kmh="$cur_kmh" -v step="$STEP" -v brg="$BEARING" 'BEGIN{
    pi = 3.14159265358979;
    mps = kmh / 3.6;
    dist = mps * step;                 # metres this tick
    rad = brg * pi / 180.0;
    dnorth = dist * cos(rad);          # metres north
    deast  = dist * sin(rad);          # metres east
    dlat = dnorth / 111320.0;
    clat = cos(lat * pi / 180.0); if (clat < 0.000001) clat = 0.000001;
    dlng = deast / (111320.0 * clat);
    printf "%.6f %.6f", lat + dlat, lng + dlng;
}')
EOF

  mps=$(awk -v k="$cur_kmh" 'BEGIN{ printf "%.1f", k/3.6 }')
  emit "sensor speed ${mps}"
  emit "sensor gps ${cur_lat} ${cur_lng} $(f1 "$BEARING") ${mps} 5"
  emit "sleep ${STEP}"
done

# ---- 3. come to a stop and park -------------------------------------------
emit "# arriving; slowing to a stop"
emit "sensor speed 0"
emit "sensor gear park"
emit "sensor gps ${cur_lat} ${cur_lng} $(f1 "$BEARING") 0 5"
emit "# end of ${SCENARIO} scenario"
