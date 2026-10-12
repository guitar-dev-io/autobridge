#!/usr/bin/env bash
# Guard the safety gate and the personal-flavor bypass against incidental edits.
#
# The parked/mode gate is one build-wide constant (SafetyEnforcement) plus the runtime bypass
# (BypassPolicyStore), and the personal flavor relies on exactly how they behave today. Work on
# calibration, connection stability or the home UI may *call* FeaturePolicy and *read*
# ParkingStateStore, but must not change any of the files below. A change here is a deliberate,
# separate decision, never a side effect of another branch.
#
# Usage: scripts/check-protected.sh [base-ref]   (default base: main)
# Exits 1 and names each file when a protected path differs from the base.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="${1:-main}"
cd "$ROOT_DIR"

PROTECTED=(
  "app/src/main/java/dev/autobridge/safety/"
  "app/src/main/java/dev/autobridge/core/policy/FeaturePolicy.kt"
  "app/src/test/java/dev/autobridge/safety/"
)

if ! git rev-parse --verify --quiet "$BASE" >/dev/null; then
  echo "check-protected: base ref '$BASE' not found" >&2
  exit 2
fi

changed="$(git diff --name-only "$BASE" -- "${PROTECTED[@]}")"

# The flavor block in the build script is shared with everything else in that file, so only the
# lines that name the personal flavor are guarded rather than the whole file.
flavor_lines="$(git diff -U0 "$BASE" -- app/build.gradle.kts | grep -E '^[+-][^+-]' | grep -i 'personal' || true)"

status=0
if [[ -n "$changed" ]]; then
  echo "check-protected: protected safety files changed against $BASE:" >&2
  echo "$changed" | sed 's/^/  /' >&2
  status=1
fi
if [[ -n "$flavor_lines" ]]; then
  echo "check-protected: personal flavor lines changed in app/build.gradle.kts:" >&2
  echo "$flavor_lines" | sed 's/^/  /' >&2
  status=1
fi

if [[ $status -eq 0 ]]; then
  echo "check-protected: OK (no protected path differs from $BASE)"
fi
exit $status
