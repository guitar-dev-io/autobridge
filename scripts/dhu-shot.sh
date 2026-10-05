#!/usr/bin/env bash
# Capture the Desktop Head Unit window to docs/dhu-shots/.
#
# Run this from a real Terminal.app/iTerm window, NOT from inside an IDE task
# runner: capturing another app's window needs Screen Recording permission,
# which the terminal has (macOS will prompt once) but a sandboxed task may not.
#
# Usage:
#   scripts/dhu-shot.sh            # capture once -> docs/dhu-shots/dhu-<timestamp>.png
#   scripts/dhu-shot.sh <label>    # capture once -> docs/dhu-shots/<label>.png
#   scripts/dhu-shot.sh --watch    # capture every 2s until Ctrl-C
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/docs/dhu-shots"
mkdir -p "$OUT"

# Find the on-screen window id of the DHU via CoreGraphics (JXA).
dhu_window_id() {
  osascript -l JavaScript <<'JXA'
ObjC.import('CoreGraphics');
var opts = $.kCGWindowListOptionOnScreenOnly | $.kCGWindowListExcludeDesktopElements;
var wins = $.CGWindowListCopyWindowInfo(opts, $.kCGNullWindowID);
var n = $.CFArrayGetCount(wins);
var best = 0, bestArea = 0;
for (var i=0; i<n; i++){
  var d = ObjC.deepUnwrap($.CFArrayGetValueAtIndex(wins, i));
  var owner = (d.kCGWindowOwnerName||'')+'';
  var name  = (d.kCGWindowName||'')+'';
  var b = d.kCGWindowBounds||{};
  var area = (b.Width||0)*(b.Height||0);
  var isDhu = /head|desktop-head|automotive/i.test(owner) ||
              /head unit|autobridge dhu|android auto/i.test(name);
  if (isDhu && area > bestArea){ best = d.kCGWindowNumber; bestArea = area; }
}
best ? (''+best) : '';
JXA
}

capture() {
  local label="${1:-dhu-$(date +%Y%m%d-%H%M%S)}"
  local wid
  wid="$(dhu_window_id || true)"
  if [[ -z "$wid" ]]; then
    echo "!! DHU window not found. Is the DHU open and on this desktop/space?" >&2
    echo "   If this is the first run, grant Terminal 'Screen Recording' in" >&2
    echo "   System Settings > Privacy & Security, then re-run." >&2
    return 1
  fi
  local path="$OUT/$label.png"
  # -o: no window shadow, -l<id>: that exact window regardless of z-order.
  screencapture -x -o -l"$wid" "$path"
  echo "captured -> $path"
}

if [[ "${1:-}" == "--watch" ]]; then
  echo "Watching DHU; Ctrl-C to stop. Saving to $OUT/"
  while true; do capture || true; sleep 2; done
else
  capture "${1:-}"
fi
