#!/usr/bin/env bash
# Build an UNSIGNED, un-notarized release of the Lunarbor Electron desktop app.
#
# Adapted from Lunamux's script of the same name. Useful for fast local/test
# builds where you just want a .dmg without a Developer ID cert or Apple's
# notary service.
#
# electron/package.json signs and notarizes (see build-release-electron.sh for
# the full release); this script skips both:
#   1. Run only the Gradle staging tasks (install npm deps, copy the web bundle
#      and the Kotlin/JS main-process bundle into electron/resources/).
#   2. Invoke electron-builder directly with code signing and notarization
#      disabled: `CSC_IDENTITY_AUTO_DISCOVERY=false` plus `-c.mac.identity=null`
#      skip signing, and `-c.mac.notarize=false` overrides any package.json
#      `notarize` block.
#
# The resulting app is neither signed nor notarized, so Gatekeeper will block it
# on other machines (and even locally it needs a right-click -> Open or a
# quarantine-attr removal). This build is intended for local verification only.
#
# Final artifact: a .dmg under electron/dist/.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# --no-daemon so a stale daemon doesn't reuse cached env vars.
./gradlew --no-daemon \
    :electron:npmInstall \
    :electron:copyWebBundle \
    :electron:copyMainBundle

echo "==> Packaging with electron-builder (signing + notarization disabled)..."
cd electron
CSC_IDENTITY_AUTO_DISCOVERY=false \
    ./node_modules/.bin/electron-builder \
    -c.mac.identity=null \
    -c.mac.notarize=false

DMG="$(ls -t dist/Lunarbor-*.dmg | head -1)"
echo "==> Done (unsigned, NOT notarized): electron/$DMG"
