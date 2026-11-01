#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/fader-session.XXXXXX")"
trap 'rm -rf "$TEST_DIR"' EXIT
swiftc -O -warnings-as-errors -module-cache-path "$PWD/.build/ModuleCache" -framework SwiftUI \
  Sources/Models/Track.swift Sources/Services/FaderCompactSessionStore.swift Sources/Services/PlaybackSessionStore.swift Sources/Services/FaderCoalescingWriter.swift \
  Sources/Services/FaderDedupTrackArtworkStore.swift Sources/Services/FaderDedupExperimentalReader.swift \
  Tests/persistence-main.swift -o "$TEST_DIR/session-tests"
"$TEST_DIR/session-tests"
