#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

./gradlew :web:jsProductionExecutableCompileSync --rerun-tasks
./gradlew :web:jsBrowserProductionWebpack --rerun-tasks
./gradlew :electron:copyWebBundle
