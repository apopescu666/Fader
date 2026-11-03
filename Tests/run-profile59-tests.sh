#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .build/ModuleCache
swiftc -D FADER_TESTING -O -warnings-as-errors -module-cache-path "$PWD/.build/ModuleCache" \
  -framework SwiftUI -framework AVFoundation -framework ImageIO -framework UniformTypeIdentifiers \
  Sources/Models/Track.swift Sources/Services/Library.swift Sources/Services/FLACArtworkReader.swift \
  Sources/Services/FaderDedupTrackArtworkStore.swift Sources/Services/FaderDedupExperimentalReader.swift \
  Sources/Services/FaderDedupAutonomousStore.swift Sources/Services/SmartRefreshInventory.swift Sources/Services/FolderCacheStore.swift \
  Sources/Services/FaderStorage.swift Sources/Utils/ID3Parser.swift Tests/profile59-main.swift -o .build/profile59-tests
export FADER_TEST_PROFILE="$(/usr/bin/uuidgen)"
.build/profile59-tests write "$PWD/Tests/fixtures"
.build/profile59-tests read "$PWD/Tests/fixtures"
