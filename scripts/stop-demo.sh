#!/usr/bin/env bash
#
# Stop the browser demo's static server (scripts/run-demo.sh) if one is
# listening.
#
#   ./scripts/stop-demo.sh            # port 8082 (or $LUNARBOR_DEMO_PORT)
#   ./scripts/stop-demo.sh 8083       # another port
#
# Only a Python http.server is stopped: anything else on the port is
# reported by name and left alone, since this script did not start it.
#
set -euo pipefail

PORT="${1:-${LUNARBOR_DEMO_PORT:-8082}}"

pids="$(lsof -nP -t -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true)"
if [[ -z "$pids" ]]; then
  echo "Nothing is listening on :$PORT."
  exit 0
fi

status=0
for pid in $pids; do
  cmd="$(ps -o command= -p "$pid" 2>/dev/null || true)"
  if [[ "$cmd" == *http.server* ]]; then
    echo "Stopping the demo server on :$PORT (pid $pid)"
    kill "$pid"
  else
    echo "error: :$PORT is held by something else (pid $pid): $cmd" >&2
    status=1
  fi
done

# Give the server a moment to exit, then force it if it is still there.
for pid in $pids; do
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.2
  done
  if kill -0 "$pid" 2>/dev/null && [[ "$(ps -o command= -p "$pid" 2>/dev/null)" == *http.server* ]]; then
    echo "Server pid $pid did not exit; sending SIGKILL"
    kill -9 "$pid" 2>/dev/null || true
  fi
done

exit "$status"
