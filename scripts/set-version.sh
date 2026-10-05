#!/usr/bin/env bash
# Set the app version in one command.
#
# version.properties is the only place the version is written; this script edits it, keeps
# versionCode monotonic, and runs sync-version.sh so README.md cannot drift.
#
#   ./scripts/set-version.sh 0.5.0            # set the name, versionCode + 1
#   ./scripts/set-version.sh patch            # 0.4.12 -> 0.4.13, versionCode + 1
#   ./scripts/set-version.sh minor            # 0.4.12 -> 0.5.0
#   ./scripts/set-version.sh major            # 0.4.12 -> 1.0.0
#   ./scripts/set-version.sh 0.5.0 --code 30  # pin versionCode as well
#   ./scripts/set-version.sh --show           # print what is set now and exit
#   ./scripts/set-version.sh minor --tag      # also create the v0.5.0 git tag
#
# --tag only tags; pushing it (which is what starts the release workflow) stays a separate,
# deliberate `git push origin v0.5.0`.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION_FILE="$ROOT_DIR/version.properties"

usage() { sed -n '2,19p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

[[ -f "$VERSION_FILE" ]] || { echo "set-version: cannot find $VERSION_FILE" >&2; exit 1; }

read_prop() {
  grep -E "^$1[[:space:]]*=" "$VERSION_FILE" | tail -n1 | sed -E "s/^$1[[:space:]]*=[[:space:]]*//" \
    | tr -d '\r' | sed -E 's/[[:space:]]+$//'
}

CURRENT_NAME="$(read_prop versionName)"
CURRENT_CODE="$(read_prop versionCode)"
if [[ -z "$CURRENT_NAME" || -z "$CURRENT_CODE" ]]; then
  echo "set-version: $VERSION_FILE is missing versionName or versionCode" >&2
  exit 1
fi

TARGET=""
NEW_CODE=""
MAKE_TAG=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --show)
      echo "versionName=$CURRENT_NAME"
      echo "versionCode=$CURRENT_CODE"
      exit 0
      ;;
    --code)
      [[ $# -ge 2 ]] || { echo "set-version: --code needs a number" >&2; exit 1; }
      NEW_CODE="$2"; shift 2 ;;
    --tag) MAKE_TAG=1; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "set-version: unknown option $1" >&2; usage >&2; exit 1 ;;
    *)
      [[ -z "$TARGET" ]] || { echo "set-version: more than one version given" >&2; exit 1; }
      TARGET="$1"; shift ;;
  esac
done

if [[ -z "$TARGET" ]]; then
  echo "set-version: give a version, or patch/minor/major" >&2
  usage >&2
  exit 1
fi

# patch/minor/major bump off the current name, with any pre-release suffix dropped first — so
# patch on 0.5.0-rc1 gives 0.5.1. To ship a candidate as its release, name it: set-version.sh 0.5.0.
case "$TARGET" in
  major|minor|patch)
    base="${CURRENT_NAME%%-*}"
    IFS='.' read -r cur_major cur_minor cur_patch <<<"$base"
    cur_major=${cur_major:-0}; cur_minor=${cur_minor:-0}; cur_patch=${cur_patch:-0}
    if ! [[ "$cur_major$cur_minor$cur_patch" =~ ^[0-9]+$ ]]; then
      echo "set-version: cannot bump '$CURRENT_NAME' — set an explicit x.y.z instead" >&2
      exit 1
    fi
    case "$TARGET" in
      major) NEW_NAME="$((cur_major + 1)).0.0" ;;
      minor) NEW_NAME="$cur_major.$((cur_minor + 1)).0" ;;
      patch) NEW_NAME="$cur_major.$cur_minor.$((cur_patch + 1))" ;;
    esac
    ;;
  *)
    NEW_NAME="${TARGET#v}"
    # The in-app update check compares this against a release tag, so keep the shape it can read:
    # x.y.z, optionally with a -rc1/-beta2 style suffix.
    if ! [[ "$NEW_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]]; then
      echo "set-version: '$TARGET' is not a x.y.z version (optionally -rc1)" >&2
      exit 1
    fi
    ;;
esac

if [[ -z "$NEW_CODE" ]]; then
  NEW_CODE=$((CURRENT_CODE + 1))
elif ! [[ "$NEW_CODE" =~ ^[0-9]+$ ]]; then
  echo "set-version: --code must be a number, got '$NEW_CODE'" >&2
  exit 1
elif (( NEW_CODE < CURRENT_CODE )); then
  # A lower code cannot install over what is already on a phone, so say so rather than writing it.
  echo "set-version: versionCode $NEW_CODE is below the current $CURRENT_CODE; Android will" \
       "refuse to install it over the existing build" >&2
  exit 1
fi

python3 - "$VERSION_FILE" "$NEW_NAME" "$NEW_CODE" <<'PY'
import re, sys

path, name, code = sys.argv[1], sys.argv[2], sys.argv[3]
with open(path, encoding="utf-8") as f:
    text = f.read()

for key, value in (("versionName", name), ("versionCode", code)):
    text, n = re.subn(rf"(?m)^{key}[ \t]*=.*$", f"{key}={value}", text, count=1)
    if not n:
        raise SystemExit(f"set-version: no {key}= line in {path}")

with open(path, "w", encoding="utf-8") as f:
    f.write(text)
PY

echo "set-version: $CURRENT_NAME ($CURRENT_CODE) -> $NEW_NAME ($NEW_CODE)"
"$ROOT_DIR/scripts/sync-version.sh"

if (( MAKE_TAG )); then
  if git -C "$ROOT_DIR" rev-parse -q --verify "refs/tags/v$NEW_NAME" >/dev/null; then
    echo "set-version: tag v$NEW_NAME already exists; left untouched" >&2
  else
    git -C "$ROOT_DIR" tag -a "v$NEW_NAME" -m "AutoBridge $NEW_NAME ($NEW_CODE)"
    echo "set-version: tagged v$NEW_NAME — push it to start the release workflow:"
    echo "             git push origin v$NEW_NAME"
  fi
fi
