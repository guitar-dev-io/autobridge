#!/usr/bin/env bash
# AutoBridge: drive the DHU along a ROUTE on the map, waypoint by waypoint.
#
# Where dhu-drive.sh rolls in a straight line to exercise speed/gear, this
# follows an actual path: you give it an ordered list of map coordinates (the
# turns of a route) and it walks the car between them, computing the heading
# for each leg and interpolating GPS fixes at a steady speed. The projected
# session sees a car that departs, follows the road, and arrives — which is
# what Maps / navigation UI needs to react to.
#
# It feeds the same `sensor gps/speed/gear` stream dhu-drive.sh does, on stdin.
#
#   A) Pipe into a DHU you launch yourself:
#        scripts/dhu-route.sh <route-file> | <DHU_HOME>/desktop-head-unit -i touch
#
#   B) Let dhu-run.sh wire it up for you (recommended):
#        scripts/dhu-run.sh --route scripts/routes/bangkok-demo.route
#
#   Dry run (stdout is a terminal) just prints the sensor stream:
#        scripts/dhu-route.sh scripts/routes/bangkok-demo.route
#
# Route file formats (auto-detected):
#   - plain : one "<lat> <lng>" per line (comma also accepted: "<lat>,<lng>").
#             blank lines and lines starting with # are ignored.
#   - gpx   : a .gpx file; <trkpt>/<rtept>/<wpt> lat= lon= are read in order.
#             export one straight from Google Maps / a GPS app and point here.
#
# Env overrides:
#   ROUTE_KMH        cruising speed in km/h (default: 50)
#   ROUTE_STEP_SEC   seconds between GPS fixes (default: 1.0)
#   ROUTE_ACCURACY   reported GPS accuracy in metres (default: 5)
#   ROUTE_LOOP       1 = loop back to the start forever (default: 0, drive once)
set -uo pipefail

KMH="${ROUTE_KMH:-50}"
STEP="${ROUTE_STEP_SEC:-1.0}"
ACC="${ROUTE_ACCURACY:-5}"
LOOP="${ROUTE_LOOP:-0}"

ROUTE_FILE=""
for arg in "$@"; do
  case "$arg" in
    -h|--help)
      sed -n '2,35p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *)
      if [[ -z "$ROUTE_FILE" ]]; then
        ROUTE_FILE="$arg"
      else
        echo "!! unexpected extra argument: $arg" >&2
        exit 2
      fi ;;
  esac
done

if [[ -z "$ROUTE_FILE" ]]; then
  echo "!! usage: dhu-route.sh <route-file>   (plain 'lat lng' lines or .gpx)" >&2
  exit 2
fi
if [[ ! -f "$ROUTE_FILE" ]]; then
  echo "!! route file not found: $ROUTE_FILE" >&2
  exit 2
fi

# ---- read waypoints -------------------------------------------------------
# Produce "<lat> <lng>" lines on stdout regardless of input format.
parse_route() {
  local file="$1"
  if [[ "$file" == *.gpx ]] || grep -qi '<gpx' "$file" 2>/dev/null; then
    # Pull lat/lon out of trkpt/rtept/wpt tags, in document order.
    grep -oiE '<(trkpt|rtept|wpt)[^>]*' "$file" \
      | sed -nE 's/.*lat="([-0-9.]+)".*lon="([-0-9.]+)".*/\1 \2/p;
                 s/.*lon="([-0-9.]+)".*lat="([-0-9.]+)".*/\2 \1/p'
  else
    # Plain: strip comments/blanks, allow comma or whitespace separators.
    sed -e 's/#.*$//' "$file" \
      | awk 'NF >= 1 { gsub(/,/, " "); if (NF >= 2) print $1, $2 }'
  fi
}

# Read waypoints into an array. `mapfile` is bash 4+; macOS ships bash 3.2, so
# read line by line for portability (also works under set -u).
WPTS=()
while IFS= read -r _line; do
  [[ -n "$_line" ]] && WPTS+=("$_line")
done < <(parse_route "$ROUTE_FILE")

if [[ "${#WPTS[@]}" -lt 2 ]]; then
  echo "!! route needs at least 2 waypoints, got ${#WPTS[@]} from $ROUTE_FILE" >&2
  exit 2
fi

emit() { printf '%s\n' "$1"; }
f6() { awk -v v="$1" 'BEGIN{ printf "%.6f", v }'; }
f1() { awk -v v="$1" 'BEGIN{ printf "%.1f", v }'; }

# Great-circle distance (metres) and initial bearing (deg) between two points.
# Echoes "<dist_m> <bearing_deg>".
leg_geo() {
  awk -v lat1="$1" -v lng1="$2" -v lat2="$3" -v lng2="$4" 'BEGIN{
    pi = 3.14159265358979; R = 6371000.0;
    p1 = lat1*pi/180; p2 = lat2*pi/180;
    dp = (lat2-lat1)*pi/180; dl = (lng2-lng1)*pi/180;
    a = sin(dp/2)*sin(dp/2) + cos(p1)*cos(p2)*sin(dl/2)*sin(dl/2);
    c = 2*atan2(sqrt(a), sqrt(1-a));
    dist = R*c;
    y = sin(dl)*cos(p2);
    x = cos(p1)*sin(p2) - sin(p1)*cos(p2)*cos(dl);
    brg = atan2(y, x)*180/pi; if (brg < 0) brg += 360;
    printf "%.3f %.1f", dist, brg;
  }'
}

# Point at <frac> along the leg from (lat1,lng1) to (lat2,lng2), linear interp
# (fine at the metre scale of a sensor tick). Echoes "<lat> <lng>".
interp() {
  awk -v lat1="$1" -v lng1="$2" -v lat2="$3" -v lng2="$4" -v f="$5" 'BEGIN{
    printf "%.6f %.6f", lat1 + (lat2-lat1)*f, lng1 + (lng2-lng1)*f;
  }'
}

MPS=$(awk -v k="$KMH" 'BEGIN{ printf "%.3f", k/3.6 }')
STEP_M=$(awk -v m="$MPS" -v s="$STEP" 'BEGIN{ printf "%.3f", m*s }')
MPS1=$(f1 "$MPS")

emit "# AutoBridge route sim: $ROUTE_FILE, ${#WPTS[@]} waypoints, ${KMH} km/h, step ${STEP}s$( [[ "$LOOP" == 1 ]] && echo ', looping' )"

drive_once() {
  local start_lat start_lng
  read -r start_lat start_lng <<< "${WPTS[0]}"

  # Depart: parked at the first waypoint, then shift to drive.
  emit "sensor gear park"
  emit "sensor speed 0"
  emit "sensor gps $(f6 "$start_lat") $(f6 "$start_lng") 0 0 ${ACC}"
  emit "# at origin, shifting to drive"
  emit "sleep ${STEP}"
  emit "sensor gear drive"

  local i a_lat a_lng b_lat b_lng geo dist brg carry pos frac steps s cur_lat cur_lng
  carry=0   # leftover distance (m) rolled from the previous leg

  for (( i = 0; i < ${#WPTS[@]} - 1; i++ )); do
    read -r a_lat a_lng <<< "${WPTS[$i]}"
    read -r b_lat b_lng <<< "${WPTS[$((i+1))]}"
    geo=$(leg_geo "$a_lat" "$a_lng" "$b_lat" "$b_lng")
    dist=${geo% *}; brg=${geo#* }

    emit "# leg $((i+1))/$(( ${#WPTS[@]} - 1 )): heading ${brg} deg, ${dist} m"

    # Walk this leg in STEP_M chunks, honouring distance carried over so speed
    # stays constant across waypoints instead of resetting at each corner.
    pos=$(awk -v c="$carry" 'BEGIN{ printf "%.3f", c }')
    while awk -v p="$pos" -v d="$dist" 'BEGIN{ exit !(p <= d) }'; do
      frac=$(awk -v p="$pos" -v d="$dist" 'BEGIN{ printf "%.6f", (d>0)? p/d : 1 }')
      read -r cur_lat cur_lng <<< "$(interp "$a_lat" "$a_lng" "$b_lat" "$b_lng" "$frac")"
      emit "sensor speed ${MPS1}"
      emit "sensor gps ${cur_lat} ${cur_lng} ${brg} ${MPS1} ${ACC}"
      emit "sleep ${STEP}"
      pos=$(awk -v p="$pos" -v s="$STEP_M" 'BEGIN{ printf "%.3f", p+s }')
    done
    # Distance overshoot becomes the carry for the next leg.
    carry=$(awk -v p="$pos" -v d="$dist" 'BEGIN{ printf "%.3f", p-d }')
  done

  # Arrive: stop and park at the final waypoint.
  read -r end_lat end_lng <<< "${WPTS[$(( ${#WPTS[@]} - 1 ))]}"
  emit "# arrived at destination"
  emit "sensor speed 0"
  emit "sensor gps $(f6 "$end_lat") $(f6 "$end_lng") 0 0 ${ACC}"
  emit "sensor gear park"
}

if [[ "$LOOP" == 1 ]]; then
  while true; do drive_once; emit "# looping back to origin"; done
else
  drive_once
  emit "# end of route"
fi
