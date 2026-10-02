#!/usr/bin/env bash
# Builds and launches the Lunarbor Electron app via `./gradlew electron:run`.
#
# Usage:
#   scripts/run.sh                 # vault from the environment (see below)
#   scripts/run.sh <vault-path>    # sets LUNARBOR_VAULT=<vault-path>
#
# The environment is forwarded to Electron unchanged, so these work too:
#   LUNARBOR_VAULT=<abs path>     vault root (default: ~/lunarbor-db)
#   LUNARBOR_LOCAL_DATA=<dir>     isolated run: Electron userData, the
#                                  single-instance lock and all settings
#                                  files live under <dir>; the vault
#                                  defaults to <dir>/vault.
# The app logs the resolved paths at startup (`==> Vault: …`, `==> Data: …`).
#
# Agents (/ai-dev, /verify) must NOT use this script: it enforces nothing and
# falls back to the real vault. They launch through scripts/ai-dev-run.sh,
# which refuses to start without an isolated LUNARBOR_LOCAL_DATA.

set -euo pipefail

cd "$(dirname "$0")/.."

if [[ $# -ge 1 && -n "$1" ]]; then
  vault="$1"
  [[ "$vault" = /* ]] || vault="$PWD/$vault"
  export LUNARBOR_VAULT="$vault"
fi

exec ./gradlew electron:run
