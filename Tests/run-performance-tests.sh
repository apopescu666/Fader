#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/fader-performance.XXXXXX")"
trap 'rm -rf "$TEST_DIR"' EXIT
swiftc -O -warnings-as-errors -module-cache-path "$PWD/.build/ModuleCache" \
  -framework SwiftUI -framework AppKit -framework ImageIO \
  Sources/Models/Track.swift Sources/UI/FaderTrackListing.swift Sources/UI/FaderArtworkImageCache.swift \
  Sources/Services/FaderDedupTrackArtworkStore.swift Sources/Services/FaderDedupExperimentalReader.swift \
  Tests/performance-main.swift -o "$TEST_DIR/performance-tests"
"$TEST_DIR/performance-tests" Tests/fixtures
