#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/fader-tests.XXXXXX")"
trap 'rm -rf "$TEST_DIR"' EXIT
cp -R Tests/fixtures "$TEST_DIR/fixtures"
swiftc -D FADER_TESTING Sources/Services/FaderStorage.swift -warnings-as-errors -module-cache-path "$PWD/.build/ModuleCache" \
  -framework SwiftUI -framework AVFoundation -framework ImageIO -framework UniformTypeIdentifiers \
  Sources/Models/Track.swift Sources/Services/Library.swift Sources/Services/FLACArtworkReader.swift \
  Sources/Services/FaderDedupTrackArtworkStore.swift Sources/Services/FaderDedupExperimentalReader.swift \
  Sources/Utils/ID3Parser.swift Tests/main.swift -o "$TEST_DIR/library-tests"
"$TEST_DIR/library-tests" "$TEST_DIR/fixtures"

swiftc -D FADER_TESTING Sources/Services/FaderStorage.swift -warnings-as-errors -module-cache-path "$PWD/.build/ModuleCache" Sources/UI/FaderLayoutPolicy.swift Tests/layout-main.swift -o "$TEST_DIR/layout-tests"
"$TEST_DIR/layout-tests"
