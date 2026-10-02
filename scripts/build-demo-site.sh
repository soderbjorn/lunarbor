#!/usr/bin/env bash
#
# Build Lunarbor's browser demo as a static website bundle.
#
#   ./scripts/build-demo-site.sh                  → build/demo-site/
#   ./scripts/build-demo-site.sh /path/to/site/demo-app
#
# The bundle is the production web build (`:web:jsBrowserDistribution`):
# index.html, web.js, its lazy chunks (Excalidraw), the Excalidraw fonts and
# demo-vault.js (demo/vault/ packed, see web/build.gradle.kts). In a plain
# browser it can only run as the demo (web/.../demo/DemoMode.kt): there is
# no Electron bridge, so it never looks for real files. Edits live in the
# visitor's tab until they reload.
#
# Serve the folder from any static host, at any path — every URL in it is
# relative. Source maps are left out.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${1:-$REPO_ROOT/build/demo-site}"
DIST="$REPO_ROOT/web/build/dist/js/productionExecutable"

echo "==> Building the production web bundle (:web:jsBrowserDistribution)"
"$REPO_ROOT/gradlew" -p "$REPO_ROOT" :web:jsBrowserDistribution

for f in index.html web.js demo-vault.js; do
  [[ -f "$DIST/$f" ]] || { echo "error: $DIST/$f is missing" >&2; exit 1; }
done

echo "==> Copying the bundle to $DEST"
mkdir -p "$DEST"
rsync -a --delete --exclude='*.map' "$DIST/" "$DEST/"

echo "==> Done: $(du -sh "$DEST" | cut -f1) in $DEST"
echo "    Preview: python3 -m http.server 8082 --directory \"$DEST\""
