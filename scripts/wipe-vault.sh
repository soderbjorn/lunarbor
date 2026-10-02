#!/usr/bin/env bash
# Deletes a Lunarbor on-disk vault.
#
# Usage:
#   scripts/wipe-vault.sh [vault-path]
#
# Vault resolution matches the Electron app (RunPaths.kt):
#   1. the vault-path argument, if given
#   2. $LUNARBOR_VAULT
#   3. $LUNARBOR_LOCAL_DATA/vault
#   4. $HOME/lunarbor-db

set -euo pipefail

if [[ $# -ge 1 && -n "$1" ]]; then
  VAULT="$1"
elif [[ -n "${LUNARBOR_VAULT:-}" ]]; then
  VAULT="$LUNARBOR_VAULT"
elif [[ -n "${LUNARBOR_LOCAL_DATA:-}" ]]; then
  VAULT="$LUNARBOR_LOCAL_DATA/vault"
else
  VAULT="$HOME/lunarbor-db"
fi

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
