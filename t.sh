
#!/bin/bash

# Configuration
ROOT_DIR="${1:-$HOME/Projects}"
WORKSPACE="${BITBUCKET_WORKSPACE:-your-workspace}"
MODE="${2:-preview}"

if [ "$WORKSPACE" = "your-workspace" ]; then
  echo "ERROR: Set BITBUCKET_WORKSPACE first"
  exit 1
fi

if [ ! -d "$ROOT_DIR" ]; then
  echo "ERROR: Directory not found: $ROOT_DIR"
  exit 1
fi

if [ "$MODE" != "preview" ] && [ "$MODE" != "apply" ]; then
  echo "Usage: $0 DIRECTORY [preview|apply]"
  exit 1
fi

echo "Root      : $ROOT_DIR"
echo "Workspace : $WORKSPACE"
echo "Mode      : $MODE"
echo "--------------------------------------"

find "$ROOT_DIR" \( -type d -name .git -prune \) -o \
  \( -type f -name .git -print \) -o \
  \( -type d -name .git -print \) |
while IFS= read -r git_entry; do
  repo_dir=$(dirname "$git_entry")

  old_url=$(git -C "$repo_dir" remote get-url origin 2>/dev/null) || continue

  case "$old_url" in
    *gitlab.com/*|*gitlab.com:*) ;;
    *) continue ;;
  esac

  repo_name=$(basename "$repo_dir")
  new_url="git@bitbucket.org:${WORKSPACE}/${repo_name}.git"

  echo ""
  echo "Project: $repo_name"
  echo "OLD: $old_url"
  echo "NEW: $new_url"

  if [ "$MODE" = "apply" ]; then
    # Check destination before changing origin
    if git ls-remote "$new_url" HEAD >/dev/null 2>&1; then
      if git -C "$repo_dir" remote set-url origin "$new_url"; then
        echo "SUCCESS"
      else
        echo "FAILED: Cannot update remote"
      fi
    else
      echo "SKIPPED: Destination inaccessible"
    fi
  else
    echo "PREVIEW ONLY"
  fi
done

echo ""
echo "Finished."
