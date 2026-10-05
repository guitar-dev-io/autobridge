#!/bin/zsh
# Capture the Desktop Head Unit window to a PNG by locating its window bounds
# via System Events and grabbing that screen rectangle.
# Usage: dhu_capture.sh <output_path>
set -e
OUT="${1:-docs/dhu-shots/after_home.png}"
mkdir -p "$(dirname "$OUT")"

BOUNDS=$(osascript <<'AS' 2>/dev/null
tell application "System Events"
    set procs to (every process whose name contains "desktop-head-unit")
    if (count of procs) is 0 then return ""
    set p to item 1 of procs
    if (count of windows of p) is 0 then return ""
    set w to window 1 of p
    set {x, y} to position of w
    set {ww, hh} to size of w
    return (x as string) & "," & (y as string) & "," & (ww as string) & "," & (hh as string)
end tell
AS
)

if [ -z "$BOUNDS" ]; then
    echo "NO_BOUNDS"
    exit 3
fi

X=$(echo "$BOUNDS" | cut -d, -f1)
Y=$(echo "$BOUNDS" | cut -d, -f2)
W=$(echo "$BOUNDS" | cut -d, -f3)
H=$(echo "$BOUNDS" | cut -d, -f4)
echo "bounds X=$X Y=$Y W=$W H=$H"
screencapture -x -o -R"${X},${Y},${W},${H}" "$OUT"
echo "OK -> $OUT"
