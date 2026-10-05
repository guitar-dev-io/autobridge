#!/usr/bin/env bash
# Sync versionName / versionCode from version.properties into README.md.
# Keeps the README header and the Google Play upload note in sync with the
# authoritative version file that app/build.gradle.kts reads.
#
# Run it after editing version.properties by hand; scripts/set-version.sh calls it for you.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION_FILE="$ROOT_DIR/version.properties"
README_FILE="$ROOT_DIR/README.md"

if [[ ! -f "$VERSION_FILE" ]]; then
  echo "sync-version: cannot find $VERSION_FILE" >&2
  exit 1
fi
if [[ ! -f "$README_FILE" ]]; then
  echo "sync-version: cannot find $README_FILE" >&2
  exit 1
fi

read_prop() {
  grep -E "^$1[[:space:]]*=" "$VERSION_FILE" | tail -n1 \
    | sed -E "s/^$1[[:space:]]*=[[:space:]]*//" | tr -d '\r' | sed -E 's/[[:space:]]+$//'
}

VERSION_NAME="$(read_prop versionName)"
VERSION_CODE="$(read_prop versionCode)"

if [[ -z "$VERSION_NAME" || -z "$VERSION_CODE" ]]; then
  echo "sync-version: failed to read versionName/versionCode from $VERSION_FILE" >&2
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
