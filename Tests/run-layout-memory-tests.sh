#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .build/ModuleCache
TEST_DIR="$(mktemp -d "$PWD/.build/layout-memory-tests.XXXXXX")"
trap 'rm -rf "$TEST_DIR"' EXIT
swiftc -D FADER_TESTING Sources/Services/FaderStorage.swift -O -warnings-as-errors -module-cache-path "$PWD/.build/ModuleCache" \
  -framework SwiftUI -framework AppKit Sources/UI/FaderLayoutMemory.swift \
  Sources/UI/FaderLayoutPolicy.swift Sources/UI/FaderWindowSizing.swift \
  Sources/UI/FaderAccentNativeSplit.swift Tests/layout-memory-main.swift -o "$TEST_DIR/LayoutMemoryTests"
TEST_DOMAIN="fader.layout-tests.$(/usr/bin/uuidgen)"
"$TEST_DIR/LayoutMemoryTests" "$TEST_DOMAIN" write
"$TEST_DIR/LayoutMemoryTests" "$TEST_DOMAIN" read
