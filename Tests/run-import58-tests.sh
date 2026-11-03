#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .build/ModuleCache
swiftc -D FADER_TESTING -O -warnings-as-errors -module-cache-path "$PWD/.build/ModuleCache" \
  -framework SwiftUI -framework AVFoundation -framework ImageIO -framework UniformTypeIdentifiers \
  Sources/Models/Track.swift Sources/Services/Library.swift Sources/Services/FLACArtworkReader.swift \
  Sources/Services/FaderDedupTrackArtworkStore.swift Sources/Services/FaderDedupExperimentalReader.swift \
  Sources/Services/FaderStorage.swift Sources/Services/FaderInitialFolderImport.swift Sources/Services/FaderImportScheduler.swift \
  Sources/Utils/ID3Parser.swift Tests/import58-main.swift -o .build/import58-tests
.build/import58-tests Tests/fixtures --clean-profile
.build/import58-tests Tests/fixtures --clean-profile --serial-import
