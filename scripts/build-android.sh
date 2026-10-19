#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/native/android"
./gradlew :app:assembleDebug :app:testDebugUnitTest
mkdir -p "$ROOT/build"
cp app/build/outputs/apk/debug/app-debug.apk "$ROOT/build/CopyMore-Android.apk"
echo "Built: $ROOT/build/CopyMore-Android.apk (development signature)"
