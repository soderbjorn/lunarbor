#!/usr/bin/env bash
# Opens the TreeFacts on-disk database at $HOME/treefacts-db in Zed.

set -euo pipefail

VAULT="$HOME/treefacts-db"

if [[ ! -d "$VAULT" ]]; then
  echo "Vault directory does not exist: $VAULT" >&2
  exit 1
fi

if ! command -v zed >/dev/null 2>&1; then
  echo "zed is not on PATH" >&2
  exit 1
fi

exec zed "$VAULT"
