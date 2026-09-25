#!/usr/bin/env bash
# Builds and launches the TreeFacts Electron app via `./gradlew electron:run`.
#
# Usage:
#   scripts/run.sh                 # vault from the environment (see below)
#   scripts/run.sh <vault-path>    # sets TREEFACTS_VAULT=<vault-path>
#
# The environment is forwarded to Electron unchanged, so these work too:
#   TREEFACTS_VAULT=<abs path>     vault root (default: ~/treefacts-db)
#   TREEFACTS_LOCAL_DATA=<dir>     isolated run: Electron userData, the
#                                  single-instance lock and all settings
#                                  files live under <dir>; the vault
#                                  defaults to <dir>/vault.
# The app logs the resolved paths at startup (`==> Vault: …`, `==> Data: …`).

set -euo pipefail

cd "$(dirname "$0")/.."

if [[ $# -ge 1 && -n "$1" ]]; then
  vault="$1"
  [[ "$vault" = /* ]] || vault="$PWD/$vault"
  export TREEFACTS_VAULT="$vault"
fi

exec ./gradlew electron:run
