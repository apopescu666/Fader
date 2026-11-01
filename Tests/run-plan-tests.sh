#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .build/ModuleCache
TEST_DIR="$(mktemp -d "$PWD/.build/plan-tests.XXXXXX")"
trap 'rm -rf "$TEST_DIR"' EXIT
SOURCES=()
while IFS= read -r -d '' source; do SOURCES+=("$source"); done < <(find Sources/Services Sources/Models Sources/Audio Sources/Utils -name '*.swift' -type f -print0)
swiftc -O -warnings-as-errors -module-cache-path "$PWD/.build/ModuleCache" \
  -framework SwiftUI -framework AppKit -framework AVFoundation -framework ImageIO \
  -framework UniformTypeIdentifiers -lsqlite3 "${SOURCES[@]}" \
  Tests/plan-main.swift -o "$TEST_DIR/FaderPlanTests"
"$TEST_DIR/FaderPlanTests" "$TEST_DIR/Data"
