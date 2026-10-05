#!/usr/bin/env bash
# AutoBridge DHU test driver.
#
# Run this in YOUR OWN terminal (zsh/bash) so it inherits Screen Recording +
# Accessibility permission. It captures the DHU window at each step, drives it
# to open Maps and a music app, and saves a logcat for the CarHome surface.
#
# Prereqs (already true in this session):
#   - device connected over adb (ANDROID_SERIAL below)
#   - DHU running and projecting (head unit server started)
#   - adb forward tcp:5277 tcp:5277 active
#
# Usage:  scripts/dhu-test.sh
set -uo pipefail

SERIAL="${ANDROID_SERIAL:-YXEMRCGYAI49S4SS}"
ADB="adb -s $SERIAL"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/docs/dhu-shots"
mkdir -p "$OUT"
SHOT="$ROOT/scripts/dhu-shot.sh"

shot() {   # shot <label> ; small settle delay first
  sleep 1.5
  "$SHOT" "$1" || echo "   (capture failed for $1)"
}

key()  { $ADB shell input keyevent "$1"; sleep 0.6; }
tap()  { $ADB shell input tap "$1" "$2"; sleep 1.0; }   # phone-screen coords (not DHU)

echo "== 0. sanity =="
$ADB get-state || { echo "device not connected"; exit 1; }
$ADB forward tcp:5277 tcp:5277 >/dev/null 2>&1 || true

echo "== starting logcat capture =="
$ADB logcat -c
$ADB logcat -v time CarHome:V AutoBridge:V CarApp:V "androidx.car.app:V" ActivityManager:I '*:S' \
  > "$OUT/logcat-dhu.txt" 2>&1 &
LOGPID=$!
trap 'kill $LOGPID 2>/dev/null' EXIT

echo "== 1. capture whatever the DHU shows now (boot / launcher) =="
shot "home-01-boot"

echo "== 2. open AutoBridge via the AA app launcher =="
# AA launcher button is bottom-left on the DHU rail; nudge via DHU keymap if touch coords differ.
# AutoBridge is a NAVIGATION car app -> it appears under the nav launcher.
$ADB shell am start -n com.google.android.projection.gearhead/com.google.android.apps.auto.carservice.gmscorecompat.FirstActivityImpl >/dev/null 2>&1 || true
shot "home-02-autobridge"

echo "== 3. open Google Maps on the car display =="
$ADB shell am start -n com.google.android.projection.gearhead/com.google.android.apps.auto.carservice.gmscorecompat.FirstActivityImpl >/dev/null 2>&1 || true
# Maps nav launcher intent (projected):
$ADB shell am start -a com.google.android.gms.car.NAVIGATION >/dev/null 2>&1 || true
shot "map-01-maps"

echo "== 4. start music (media app) =="
# Try YouTube Music, then generic media button as fallback.
$ADB shell am start -n com.google.android.apps.youtube.music/.activities.MusicActivity >/dev/null 2>&1 || true
sleep 2
key 85    # KEYCODE_MEDIA_PLAY_PAUSE
shot "music-01-playing"

echo "== 5. back to AutoBridge home, confirm it survived nav+media =="
$ADB shell am start -n com.google.android.projection.gearhead/com.google.android.apps.auto.carservice.gmscorecompat.FirstActivityImpl >/dev/null 2>&1 || true
shot "home-03-return"

sleep 2
kill $LOGPID 2>/dev/null
echo
echo "== DONE =="
echo "Screenshots: $OUT/*.png"
echo "Log:         $OUT/logcat-dhu.txt"
echo
echo "Errors/warnings seen for CarHome:"
grep -iE "CarHome|AutoBridge|surface|template|exception|crash" "$OUT/logcat-dhu.txt" | tail -40
