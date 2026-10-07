#!/usr/bin/env bash
# AutoBridge: launch the DHU on the Next-Gen Ranger 10.1" portrait panel.
# Screen config: 800x1280 @ dpi 160, touch  (docs/dhu/ford-10inch-portrait.ini)
#
# PLACEHOLDER geometry — not measured on real Ford/Ranger hardware. See
# docs/FORD_NEXT_GEN.md; FORD_NEXT_GEN is still hardwareValidated=false.
#
# Thin wrapper around scripts/dhu-run.sh so you don't have to remember the
# config name. Pass any extra dhu-run.sh args through, e.g.:
#
#   scripts/launch-ford-10inch.sh                 # build + install personal, then DHU
#   scripts/launch-ford-10inch.sh --no-build      # skip gradle, just re-forward + DHU
#   scripts/launch-ford-10inch.sh safe            # use the safe flavor instead
#   scripts/launch-ford-10inch.sh --no-build safe # reconnect on safe, no rebuild
#
# Run this in a REAL Terminal.app / iTerm window, not an IDE task runner — the DHU
# needs Screen Recording / Accessibility permission that only a real terminal has.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="ford-10inch-portrait"
INPUT="touch"
FLAVOR="personal"

# Split the passed args: flags (start with -) and a known input mode pass through
# untouched; the first flavor word overrides the default. The config is fixed to
# the Ranger 10.1" panel — that's the whole point of this wrapper.
flags=()
for arg in "$@"; do
  case "$arg" in
    safe|personal|lab) FLAVOR="$arg" ;;
    *) flags+=("$arg") ;;  # flags, input mode, scenarios, etc. -> pass through
  esac
done

# Only add the default input mode if the caller didn't already name one.
has_input=0
for arg in "$@"; do
  case "$arg" in touch|rotary|hybrid) has_input=1 ;; esac
done

cmd=("$ROOT/scripts/dhu-run.sh" "${flags[@]}" "$FLAVOR" "$CONFIG")
[[ "$has_input" -eq 0 ]] && cmd+=("$INPUT")

echo "== launch Next-Gen Ranger 10.1\" portrait (800x1280 @ dpi 160) =="
echo "   -> ${cmd[*]}"
echo
exec "${cmd[@]}"
