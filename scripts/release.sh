#!/usr/bin/env bash
# Bump the version, commit it, tag it, and push — in the one order that keeps the tag honest.
#
# .github/workflows/release.yml checks out whatever commit a pushed `vX.Y.Z` tag points at and
# compares that tag against version.properties *at that commit*. Running set-version.sh and then
# `git tag` by hand is an easy way to tag the commit *before* the version bump lands (the tag is
# created against HEAD, which does not yet include the uncommitted edit) — the release then fails
# the "tag matches version.properties" guard. This script always commits the bump first, so the
# tag it creates points at a commit where version.properties already says what the tag says.
#
#   ./scripts/release.sh patch              # 0.4.21 -> 0.4.22, commit + tag, print the push commands
#   ./scripts/release.sh minor --push       # same, then push the branch and the tag
#   ./scripts/release.sh 0.5.0 --code 40 --push
#
# Pass-through: patch/minor/major or an explicit x.y.z, and --code, mean what they mean to
# set-version.sh. --push is this script's own flag — without it, nothing leaves the machine.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

usage() { sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

TARGET=""
CODE_ARGS=()
DO_PUSH=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --push) DO_PUSH=1; shift ;;
    --code)
      [[ $# -ge 2 ]] || { echo "release: --code needs a number" >&2; exit 1; }
      CODE_ARGS=(--code "$2"); shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "release: unknown option $1" >&2; usage >&2; exit 1 ;;
    *)
      [[ -z "$TARGET" ]] || { echo "release: more than one version given" >&2; exit 1; }
      TARGET="$1"; shift ;;
  esac
done
[[ -n "$TARGET" ]] || { echo "release: give a version, or patch/minor/major" >&2; usage >&2; exit 1; }

# A clean tree, save for what this script is about to write, keeps the version-bump commit from
# picking up unrelated work that happened to be sitting in the working directory.
if [[ -n "$(git status --porcelain -- . ':!version.properties' ':!README.md')" ]]; then
  echo "release: working tree has changes outside version.properties/README.md — commit or" \
       "stash them first, so the version-bump commit stays just the version bump." >&2
  git status --short -- . ':!version.properties' ':!README.md' >&2
  exit 1
fi

"$ROOT_DIR/scripts/set-version.sh" "$TARGET" "${CODE_ARGS[@]}"

NEW_NAME="$(grep -E '^versionName[[:space:]]*=' version.properties | sed -E 's/^versionName[[:space:]]*=[[:space:]]*//' | tr -d '\r')"
NEW_CODE="$(grep -E '^versionCode[[:space:]]*=' version.properties | sed -E 's/^versionCode[[:space:]]*=[[:space:]]*//' | tr -d '\r')"
TAG="v$NEW_NAME"

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "release: tag $TAG already exists locally — pick a different version, or delete it" \
       "first if it's a known-bad tag (git tag -d $TAG; git push origin :refs/tags/$TAG)." >&2
  exit 1
fi

git add version.properties README.md
git commit -m "Set the version to $NEW_NAME ($NEW_CODE)"

# Tagging after the commit (not before) is the entire point of this script: the tag now points at
# the commit that actually carries the version it is named for.
git tag -a "$TAG" -m "AutoBridge $NEW_NAME ($NEW_CODE)"

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if (( DO_PUSH )); then
  git push origin "$BRANCH"
  git push origin "$TAG"
  echo "release: pushed $BRANCH and $TAG — the Release workflow will build and publish it."
else
  echo "release: committed and tagged $TAG locally. Push both to start the release workflow:"
  echo "         git push origin $BRANCH"
  echo "         git push origin $TAG"
fi
