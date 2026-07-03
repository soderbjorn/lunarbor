#!/usr/bin/env bash
# Copies the Obsidian vault verbatim into the TreeFacts storage directory.
#
# No transformation: file contents, names, and directory structure are
# preserved exactly as-is, including hidden folders such as .obsidian/.
# File metadata (mtime, mode, xattrs, ACLs) is preserved via `ditto`.
#
# Usage:
#   ./obsidian_to_treefacts.sh
#   ./obsidian_to_treefacts.sh --wipe   # clears destination first

set -euo pipefail

SRC="/Users/soderbjorn/Documents/My vault"
DEST="$HOME/treefacts-db"

WIPE=0
for arg in "$@"; do
  case "$arg" in
    --wipe) WIPE=1 ;;
    -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; exit 1 ;;
  esac
done

if [[ ! -d "$SRC" ]]; then
  echo "Source vault does not exist or is not a directory: $SRC" >&2
  exit 1
fi

if [[ "$WIPE" -eq 1 && -e "$DEST" ]]; then
  echo "Wiping $DEST"
  rm -rf -- "$DEST"
fi

mkdir -p -- "$DEST"

echo "Copying $SRC -> $DEST"
# `ditto SRC DEST` copies the contents of SRC into DEST (no extra nesting).
ditto -- "$SRC" "$DEST"
echo "Done."
