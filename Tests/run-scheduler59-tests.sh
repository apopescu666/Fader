#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .build/ModuleCache
swiftc -D FADER_TESTING -O -warnings-as-errors -module-cache-path "$PWD/.build/ModuleCache" \
  -framework SwiftUI -framework AVFoundation -framework ImageIO -framework UniformTypeIdentifiers \
  Sources/Models/Track.swift Sources/Services/Library.swift Sources/Services/FLACArtworkReader.swift \
  Sources/Services/FaderDedupTrackArtworkStore.swift Sources/Services/FaderDedupExperimentalReader.swift \
  Sources/Services/FaderStorage.swift Sources/Services/FaderInitialFolderImport.swift Sources/Services/FaderImportScheduler.swift \
  Sources/Utils/ID3Parser.swift Tests/scheduler59-main.swift -o .build/scheduler59-tests
.build/scheduler59-tests
