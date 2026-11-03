#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .build/ModuleCache .build/presentation68
python3 - <<'FIXTURES'
from pathlib import Path
import wave
p=Path('.build/UI-Fixtures');p.mkdir(exist_ok=True)
for name in ['Lite Demo One.wav','Lite Demo Two.wav','Lite Demo Three.wav']:
 with wave.open(str(p/name),'wb') as f:
  f.setnchannels(1);f.setsampwidth(2);f.setframerate(44100);f.writeframes(bytes(44100*2*120))
FIXTURES
SOURCES=()
while IFS= read -r -d '' source; do SOURCES+=("$source"); done < <(find Sources -name '*.swift' -type f -print0)
swiftc -D FADER_TESTING -D FADER_PRESENTATION_TESTING -O -warnings-as-errors -module-cache-path "$PWD/.build/ModuleCache" -framework SwiftUI -framework AppKit -framework AVFoundation -framework UniformTypeIdentifiers -lsqlite3 "${SOURCES[@]}" Tests/presentation68-main.swift -o .build/presentation68-tests
.build/presentation68-tests "$PWD/.build/presentation68" "$PWD/.build/UI-Fixtures/Lite Demo One.wav" "$PWD/.build/UI-Fixtures/Lite Demo Two.wav" "$PWD/.build/UI-Fixtures/Lite Demo Three.wav"
