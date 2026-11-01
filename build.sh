#!/bin/bash
# Build a local, ad-hoc signed Fader.app using Apple's Command Line Tools.
set -euo pipefail
cd "$(dirname "$0")"
ARCH="arm64"
BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/fader-build.XXXXXX")"
trap 'rm -rf "$BUILD_DIR"' EXIT
APP="$BUILD_DIR/Fader.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
SOURCES=()
while IFS= read -r -d '' source; do SOURCES+=("$source"); done < <(find Sources -name '*.swift' -type f -print0)
mkdir -p "$PWD/.build/ModuleCache"
echo "Compiling Fader 1.0 for $ARCH (macOS 14+)"
swiftc -O -warnings-as-errors -target "$ARCH-apple-macos14.0" \
  -module-cache-path "$PWD/.build/ModuleCache" \
  -framework SwiftUI -framework AppKit -framework AVFoundation \
  -framework UniformTypeIdentifiers -lsqlite3 \
  -o "$APP/Contents/MacOS/Fader" "${SOURCES[@]}"
mkdir -p "$APP/Contents/Helpers"
swiftc -O -warnings-as-errors -target "$ARCH-apple-macos14.0" \
  -module-cache-path "$PWD/.build/ModuleCache" -framework SwiftUI \
  Sources/Models/Track.swift Sources/Services/PlaybackSessionStore.swift \
  Sources/Services/FaderCompactSessionStore.swift Sources/Services/FaderCoalescingWriter.swift \
  Sources/Services/FaderDedupTrackArtworkStore.swift Sources/Services/FaderDedupExperimentalReader.swift \
  Tools/FaderPrepareSession.swift -o "$APP/Contents/Helpers/FaderPrepareSession"
codesign --force --sign - "$APP/Contents/Helpers/FaderPrepareSession"
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleName</key><string>Fader</string>
<key>CFBundleDisplayName</key><string>Fader</string>
<key>CFBundleExecutable</key><string>Fader</string>
<key>CFBundleIdentifier</key><string>com.fader.player</string>
<key>CFBundleVersion</key><string>57</string>
<key>CFBundleShortVersionString</key><string>1.0</string>
<key>CFBundleIconFile</key><string>FaderIcon</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>LSMinimumSystemVersion</key><string>14.0</string>
<key>NSHighResolutionCapable</key><true/>
<key>CFBundleDocumentTypes</key><array><dict>
<key>CFBundleTypeName</key><string>Audio files</string>
<key>CFBundleTypeRole</key><string>Viewer</string>
<key>LSHandlerRank</key><string>Alternate</string>
<key>LSItemContentTypes</key><array>
<string>public.mp3</string><string>public.mpeg-4-audio</string>
<string>public.aac-audio</string><string>com.microsoft.waveform-audio</string>
<string>public.aiff-audio</string><string>org.xiph.flac</string>
</array>
<key>CFBundleTypeExtensions</key><array><string>mp3</string><string>m4a</string>
<string>aac</string><string>wav</string><string>aif</string><string>aiff</string><string>flac</string></array>
</dict></array>
</dict></plist>
PLIST
ICONSET="$BUILD_DIR/FaderIcon.iconset"
mkdir -p "$ICONSET"
for size in 16 32 128 256 512; do
  sips -s format png --resampleHeightWidth "$size" "$size" Resources/FaderIcon.png --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
  double=$((size * 2))
  sips -s format png --resampleHeightWidth "$double" "$double" Resources/FaderIcon.png --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/FaderIcon.icns"
plutil -lint "$APP/Contents/Info.plist"
codesign --force --sign - "$APP"
codesign --verify --deep --strict "$APP"
# Replace only after compilation, icon creation and signing have all succeeded.
if [ -e Fader.app ]; then
  PREVIOUS="Fader-build-backup-$(date +%Y%m%d-%H%M%S).app"
  mv Fader.app "$PREVIOUS"
  echo "Previous build preserved: $PREVIOUS"
fi
ditto "$APP" Fader.app
echo "Built Fader.app — no compiler warnings. Drag it to Applications to install."
