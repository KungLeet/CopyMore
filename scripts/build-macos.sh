#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/native/macos"
export CLANG_MODULE_CACHE_PATH="$PWD/.build/clang-cache"
export SWIFTPM_MODULECACHE_OVERRIDE="$CLANG_MODULE_CACHE_PATH"
swift build -c release --product CopyMore
BIN="$(swift build -c release --show-bin-path)"
APP="$ROOT/build/CopyMore.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN/CopyMore" "$APP/Contents/MacOS/CopyMore"
cp Info.plist "$APP/Contents/Info.plist"
codesign --force --sign - "$APP"
ditto -c -k --sequesterRsrc --keepParent "$APP" "$ROOT/build/CopyMore-macOS.zip"
echo "Built: $APP (local development signature)"
