#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_root"
npm --prefix web ci
npm --prefix web run build
./gradlew :koog:test :koog-app:testDebugUnitTest :koog-app:assembleDebug "$@"
