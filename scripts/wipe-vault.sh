#!/usr/bin/env bash
# Deletes the Notegrow on-disk database at $HOME/notegrow-db.

set -euo pipefail

VAULT="$HOME/notegrow-db"

if [[ ! -e "$VAULT" ]]; then
  echo "Nothing to delete: $VAULT does not exist."
  exit 0
fi

read -r -p "Delete $VAULT and everything inside it? [y/N] " reply
case "$reply" in
  y|Y|yes|YES) ;;
  *) echo "Aborted."; exit 1 ;;
esac

rm -rf -- "$VAULT"
echo "Deleted $VAULT"
