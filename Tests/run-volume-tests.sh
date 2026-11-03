#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .build/ModuleCache
swiftc -D FADER_TESTING -parse-as-library -warnings-as-errors -module-cache-path "$PWD/.build/ModuleCache" -framework SwiftUI -framework AppKit Sources/UI/FaderVolumeSlider.swift Tests/volume61-main.swift -o .build/volume-tests
.build/volume-tests "$PWD/.build/volume-preview.png"
swiftc -D FADER_TESTING -parse-as-library -warnings-as-errors -module-cache-path "$PWD/.build/ModuleCache" -framework AppKit Tests/volume63-wave-main.swift -o .build/wave-tests
.build/wave-tests "$PWD/.build/wave-preview.png"
