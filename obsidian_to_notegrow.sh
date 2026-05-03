#!/usr/bin/env bash
# Copies an Obsidian vault verbatim into the Notegrow storage directory.
#
# No transformation: file contents, names, and directory structure are
# preserved exactly as-is, including hidden folders such as .obsidian/.
# File metadata (mtime, mode, xattrs, ACLs) is preserved via `ditto`.
#
# Usage:
#   ./obsidian_to_notegrow.sh
#   ./obsidian_to_notegrow.sh /path/to/vault
#   ./obsidian_to_notegrow.sh /path/to/vault /path/to/dest
#   ./obsidian_to_notegrow.sh --wipe              # clears destination first

set -euo pipefail

DEFAULT_SRC="/Users/soderbjorn/Documents/My vault"
DEFAULT_DEST="$HOME/notegrow-db"

WIPE=0
POSITIONAL=()
for arg in "$@"; do
  case "$arg" in
    --wipe) WIPE=1 ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) POSITIONAL+=("$arg") ;;
  esac
done

SRC="${POSITIONAL[0]:-$DEFAULT_SRC}"
DEST="${POSITIONAL[1]:-$DEFAULT_DEST}"

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
