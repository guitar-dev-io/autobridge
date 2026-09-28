#!/usr/bin/env bash
# Sync versionName / versionCode from app/build.gradle.kts into README.md.
# Keeps the README header and the Google Play upload note in sync with the
# authoritative Gradle build config.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_FILE="$ROOT_DIR/app/build.gradle.kts"
README_FILE="$ROOT_DIR/README.md"

if [[ ! -f "$GRADLE_FILE" ]]; then
  echo "sync-version: cannot find $GRADLE_FILE" >&2
  exit 1
fi
if [[ ! -f "$README_FILE" ]]; then
  echo "sync-version: cannot find $README_FILE" >&2
  exit 1
fi

# Extract the values from the defaultConfig block.
VERSION_NAME="$(grep -Eo 'versionName[[:space:]]*=[[:space:]]*"[^"]+"' "$GRADLE_FILE" | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')"
VERSION_CODE="$(grep -Eo 'versionCode[[:space:]]*=[[:space:]]*[0-9]+' "$GRADLE_FILE" | head -n1 | sed -E 's/[^0-9]//g')"

if [[ -z "$VERSION_NAME" || -z "$VERSION_CODE" ]]; then
  echo "sync-version: failed to parse versionName/versionCode from $GRADLE_FILE" >&2
  exit 1
fi

# 1. Header line at the top of the README.
python3 - "$README_FILE" "$VERSION_NAME" "$VERSION_CODE" <<'PY'
import re, sys

readme, version_name, version_code = sys.argv[1], sys.argv[2], sys.argv[3]
with open(readme, "r", encoding="utf-8") as f:
    text = f.read()

header = f"**Version:** `{version_name}` &nbsp;\u00b7&nbsp; **Build (versionCode):** `{version_code}`"
text, n_header = re.subn(
    r"\*\*Version:\*\* `[^`]+` &nbsp;\u00b7&nbsp; \*\*Build \(versionCode\):\*\* `[^`]+`",
    header,
    text,
    count=1,
)

# 2. Google Play upload note.
text, n_note = re.subn(
    r"(contains `dev\.autobridge`, version `)[^`]+(`, and `versionCode )[0-9]+(`)",
    lambda m: f"{m.group(1)}{version_name}{m.group(2)}{version_code}{m.group(3)}",
    text,
    count=1,
)

with open(readme, "w", encoding="utf-8") as f:
    f.write(text)

print(f"sync-version: versionName={version_name} versionCode={version_code} "
      f"(header updated: {bool(n_header)}, play note updated: {bool(n_note)})")
PY
