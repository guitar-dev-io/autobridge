#!/usr/bin/env bash
# Guard what the Play build asks the user for.
#
# Two failure modes, both of which have shipped in other people's apps and neither of which the
# build notices:
#
#   1. A sideload-only capability leaks into the safe (Play) flavor. QUERY_ALL_PACKAGES is the one
#      that matters: Play accepts it for a short list of app types that AutoBridge is not, and it
#      reaches the Play build the moment someone moves a declaration from src/projection into
#      src/main. There is no lint check for "this belongs to another flavor".
#   2. A permission that needs a Play Console declaration appears with nothing written down about
#      it. Those declarations are prose a human writes in the console; docs/PLAY_DECLARATIONS.md is
#      where that prose lives, so a permission in the manifest and not in the doc is a release with
#      a form nobody filled in.
#
# Reads the merged manifest, because that is what the user installs - a per-flavor manifest says
# nothing about what the merge produced. Run ./gradlew :app:processSafeDebugMainManifest (or any
# safe assemble) first; CI assembles safeDebug anyway.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOC="$ROOT_DIR/PLAY_DECLARATIONS.md"

# Must not be in the Play build at all: "<thing in the manifest>|<why>".
FORBIDDEN=(
  "android.permission.QUERY_ALL_PACKAGES|sideload flavors only (src/projection); Play restricts it"
  "dev.autobridge.projection.ProjectionCarService|unofficial Android Auto SDK; Play rejects it"
  "dev.autobridge.duoscreen|Duo Screen is sideload only"
)

# Allowed, but each needs a written justification in the Play Console - so each needs an entry in
# the doc. The grep is for the permission's short name, which is how the doc names them.
NEEDS_DECLARATION=(
  "android.permission.RECORD_AUDIO"
  "android.permission.ACCESS_FINE_LOCATION"
  "android.permission.ACCESS_COARSE_LOCATION"
  "android.permission.BIND_ACCESSIBILITY_SERVICE"
  "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"
  "android.permission.WRITE_SETTINGS"
  "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
)

status=0
fail() { echo "check-play-manifest: $*" >&2; status=1; }

manifests=$(find "$ROOT_DIR/app/build/intermediates/merged_manifest" -path '*safe*' -name AndroidManifest.xml 2>/dev/null || true)
if [[ -z "$manifests" ]]; then
  echo "check-play-manifest: no merged safe manifest found." >&2
  echo "  run: ./gradlew :app:processSafeDebugMainManifest" >&2
  exit 1
fi

[[ -f "$DOC" ]] || { fail "missing ${DOC#"$ROOT_DIR/"}"; exit 1; }

for manifest in $manifests; do
  short=${manifest#"$ROOT_DIR/"}

  for entry in "${FORBIDDEN[@]}"; do
    needle=${entry%%|*}; reason=${entry#*|}
    if grep -q "$needle" "$manifest"; then
      fail "$short declares $needle — $reason"
    fi
  done

  for permission in "${NEEDS_DECLARATION[@]}"; do
    grep -q "$permission" "$manifest" || continue
    name=${permission##*.}
    grep -q "$name" "$DOC" || fail "$short declares $permission with no entry for $name in ${DOC#"$ROOT_DIR/"}"
  done
done

if [[ $status -eq 0 ]]; then
  echo "check-play-manifest: OK — $(echo "$manifests" | wc -l | tr -d ' ') merged safe manifest(s) checked"
fi
exit $status
