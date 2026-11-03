#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
TEST_DIR="$(mktemp -d "$PWD/Tests/fader-refresh.XXXXXX")"
trap 'rm -rf "$TEST_DIR"' EXIT
# Redirect only the test compilation's cache directory. The production source is unchanged.
python3 - "$TEST_DIR" <<'PY'
from pathlib import Path
import sys
s=Path('Sources/Services/FolderCacheStore.swift').read_text()
a=s.index('    private static var directory: URL {')
b=s.index('    private static func cacheURL',a)
s=s[:a]+'''    private static var directory: URL {
        URL(fileURLWithPath: CommandLine.arguments[1]).appendingPathComponent("FolderCache")
    }

'''+s[b:]
Path(sys.argv[1], 'FolderCacheStore.swift').write_text(s)
PY
swiftc -D FADER_TESTING Sources/Services/FaderStorage.swift -O -warnings-as-errors -module-cache-path "$PWD/.build/ModuleCache" \
  -framework SwiftUI -framework AVFoundation -framework ImageIO -framework UniformTypeIdentifiers \
  Sources/Models/Track.swift Sources/Services/Library.swift Sources/Services/FLACArtworkReader.swift \
  Sources/Services/FaderDedupTrackArtworkStore.swift Sources/Services/FaderDedupExperimentalReader.swift \
  Sources/Services/FaderDedupAutonomousStore.swift Sources/Services/SmartRefreshInventory.swift \
  Sources/Utils/ID3Parser.swift "$TEST_DIR/FolderCacheStore.swift" Tests/smart-refresh-main.swift \
  -o "$TEST_DIR/refresh-tests"
FADER_DEDUP_AUTONOMOUS=0 "$TEST_DIR/refresh-tests" "$TEST_DIR" Tests/fixtures
