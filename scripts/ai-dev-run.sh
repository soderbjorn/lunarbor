#!/usr/bin/env bash
# Launches one ISOLATED TreeFacts Electron run for an agent (/ai-dev, /verify),
# confirms the isolation took effect, and returns once the app is up.
#
# Usage (from the worktree root, after building the Electron resources):
#   TREEFACTS_LOCAL_DATA=/tmp/ai-dev/<KEY> scripts/ai-dev-run.sh <port> [<dataDir>]
#
#   <port>     CDP port, passed as --remote-debugging-port=<port>. It is also
#              the handle for stopping the run:
#                  pkill -f -- "--remote-debugging-port=<port>"
#   <dataDir>  Optional. When given it must equal $TREEFACTS_LOCAL_DATA — a
#              typo check, so a brief's command line and its environment
#              cannot silently disagree.
#
# What is enforced (each is a refusal, exit status 2, nothing launched):
#   - TREEFACTS_LOCAL_DATA must be set, absolute, and outside $HOME. It moves
#     Electron's userData (and the single-instance lock) to <dir>/electron and
#     the settings files out of ~/Library/Application Support/Darkness.
#   - TREEFACTS_VAULT must be unset (or exactly <dir>/vault), so the vault is
#     always <dir>/vault. A run can never be pointed at ~/treefacts-db.
#   - <port> must be a number and nothing may already listen on it — two runs
#     sharing a port would also share the pkill that stops them.
#   - The Electron resources must be built (see below); this script never
#     builds, because the Gradle call needs the caller's own
#     -Plunula.toolkit.path.
#
# After launching it waits for the main process's two startup lines and prints
# them. It kills the run it started (by its port) and exits non-zero if they
# are missing or do not name <dir>/vault and <dir>, or if the CDP endpoint
# never answers (the app died after logging). The app keeps running in
# the background after a successful return; its output is in
# <dir>/electron-run.log.
#
# Build first (repeat after each change; add -Plunula.toolkit.path=… in a
# worktree):
#   ./gradlew :electron:npmInstall :electron:copyWebBundle :electron:copyMainBundle

set -euo pipefail

cd "$(dirname "$0")/.."
root="$PWD"

refuse() {
  echo "ai-dev-run: REFUSED — $*" >&2
  exit 2
}

[[ $# -ge 1 && $# -le 2 ]] || refuse "usage: TREEFACTS_LOCAL_DATA=<dir> $0 <port> [<dataDir>]"
port="$1"
[[ "$port" =~ ^[0-9]+$ ]] || refuse "port '$port' is not a number."

data="${TREEFACTS_LOCAL_DATA:-}"
[[ -n "${data// /}" ]] || refuse "TREEFACTS_LOCAL_DATA is not set. Without it the app opens the real vault (~/treefacts-db) and the real settings folder."
[[ "$data" = /* ]] || refuse "TREEFACTS_LOCAL_DATA must be an absolute path (got '$data')."
data="${data%/}"
[[ -n "$data" ]] || refuse "TREEFACTS_LOCAL_DATA must not be the filesystem root."
case "$data/" in
  "$HOME"/*) refuse "TREEFACTS_LOCAL_DATA ($data) is inside your home directory. Use a throwaway path such as /tmp/ai-dev/<KEY>." ;;
esac
if [[ $# -eq 2 && "${2%/}" != "$data" ]]; then
  refuse "dataDir argument '$2' does not match TREEFACTS_LOCAL_DATA '$data'."
fi

vault="$data/vault"
if [[ -n "${TREEFACTS_VAULT:-}" && "${TREEFACTS_VAULT%/}" != "$vault" ]]; then
  refuse "TREEFACTS_VAULT is set to '$TREEFACTS_VAULT'. Leave it unset; an isolated run always uses $vault."
fi
unset TREEFACTS_VAULT

if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
  refuse "port $port is already in use. Pick the port your brief assigned, or stop your earlier run: pkill -f -- \"--remote-debugging-port=$port\""
fi

electron_bin="$root/electron/node_modules/.bin/electron"
[[ -x "$electron_bin" && -d "$root/electron/resources/main" && -d "$root/electron/resources/web" ]] ||
  refuse "Electron resources are not built in $root/electron. Run: ./gradlew :electron:npmInstall :electron:copyWebBundle :electron:copyMainBundle"

mkdir -p "$data"
log="$data/electron-run.log"
: > "$log"

(
  cd "$root/electron"
  export TREEFACTS_LOCAL_DATA="$data"
  exec nohup "$electron_bin" . --remote-debugging-port="$port"
) >>"$log" 2>&1 &
pid=$!

stop_run() {
  pkill -f -- "--remote-debugging-port=$port" 2>/dev/null || true
}

want_vault="==> Vault: $vault (TREEFACTS_LOCAL_DATA)"
want_data="==> Data: $data (TREEFACTS_LOCAL_DATA)"

for _ in $(seq 1 120); do
  if grep -q '^==> Data: ' "$log" && grep -q '^==> Vault: ' "$log"; then break; fi
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "ai-dev-run: Electron exited before logging its paths. Output:" >&2
    cat "$log" >&2
    exit 1
  fi
  sleep 0.5
done

got_vault="$(grep -m1 '^==> Vault: ' "$log" || true)"
got_data="$(grep -m1 '^==> Data: ' "$log" || true)"
echo "$got_vault"
echo "$got_data"

if [[ "$got_vault" != "$want_vault" || "$got_data" != "$want_data" ]]; then
  stop_run
  echo "ai-dev-run: ISOLATION NOT CONFIRMED — stopped the run on port $port." >&2
  echo "  expected: $want_vault" >&2
  echo "  expected: $want_data" >&2
  exit 1
fi

# The log lines come from the main process before any window exists, so they
# do not prove the app is usable: Chromium can still abort a second later
# (e.g. inside a Seatbelt sandbox). Only report success once CDP answers.
cdp_up=""
for _ in $(seq 1 60); do
  if ! kill -0 "$pid" 2>/dev/null; then break; fi
  if curl -s -o /dev/null "http://localhost:$port/json/version"; then cdp_up=1; break; fi
  sleep 0.5
done
if [[ -z "$cdp_up" ]]; then
  stop_run
  echo "ai-dev-run: the app did not come up (no CDP answer on port $port). Output:" >&2
  cat "$log" >&2
  exit 1
fi

echo "ai-dev-run: isolated run up — pid $pid, CDP http://localhost:$port/json, log $log"
echo "ai-dev-run: stop it with: pkill -f -- \"--remote-debugging-port=$port\""
