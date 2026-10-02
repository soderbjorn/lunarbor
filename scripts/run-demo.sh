#!/usr/bin/env bash
#
# Run Lunarbor's browser demo from the working tree: the web bundle over a
# static server, with the demo vault in memory in the tab.
#
#   ./scripts/run-demo.sh              → http://localhost:8082/
#   ./scripts/run-demo.sh --prod       # the minified bundle the website ships
#   ./scripts/run-demo.sh --no-open    # don't launch a browser
#
# Demo mode is what the web bundle does in any plain browser (no Electron
# `noteApi` bridge, see web/.../demo/DemoMode.kt): it loads demo-vault.js —
# demo/vault/ packed by the :web:generateDemoVault task — into memory. Edits
# work and last until you reload; nothing is read from or written to disk.
#
# To edit the tour itself, open demo/vault/ in the desktop app:
#   LUNARBOR_VAULT="$PWD/demo/vault" ./scripts/run.sh
#
# Env:
#   LUNARBOR_DEMO_PORT   the static server's port (default: 8082)
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${LUNARBOR_DEMO_PORT:-8082}"

open_browser=1
flavour=development
for arg in "$@"; do
  case "$arg" in
    --no-open) open_browser=0 ;;
    --prod|--production) flavour=production ;;
    -h|--help) sed -n '2,8p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "usage: $0 [--prod] [--no-open]" >&2; exit 2 ;;
  esac
done

if [[ "$flavour" == production ]]; then
  task=":web:jsBrowserDistribution"
  dist="$REPO_ROOT/web/build/dist/js/productionExecutable"
else
  task=":web:jsBrowserDevelopmentExecutableDistribution"
  dist="$REPO_ROOT/web/build/dist/js/developmentExecutable"
fi

if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN > /dev/null 2>&1; then
  echo "error: something is already listening on :$PORT (set LUNARBOR_DEMO_PORT)" >&2
  exit 1
fi

echo "==> Building the $flavour web bundle ($task)"
"$REPO_ROOT/gradlew" -p "$REPO_ROOT" "$task"

[[ -f "$dist/index.html" && -f "$dist/web.js" && -f "$dist/demo-vault.js" ]] || {
  echo "error: no bundle at $dist (expected index.html, web.js, demo-vault.js)" >&2
  exit 1
}

url="http://localhost:$PORT/"
echo "==> Serving $dist at $url  (Ctrl-C to stop)"
if [[ "$open_browser" -eq 1 ]]; then
  (sleep 1; open "$url" 2>/dev/null || xdg-open "$url" 2>/dev/null || true) &
fi
exec python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$dist"
