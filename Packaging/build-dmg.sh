#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT}"
mkdir -p "$OUT"
DEST="$OUT/Fader-1.02-Build69-AppleSilicon.dmg"
if [ -e "$DEST" ]; then
  echo "Output already exists: $DEST" >&2
  exit 1
fi
codesign --verify --deep --strict "$ROOT/Fader.app"
STAGE="$(mktemp -d "${TMPDIR:-/tmp}/fader101-package.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
ditto "$ROOT/Fader.app" "$STAGE/Fader.app"
ln -s /Applications "$STAGE/Applications"
cp "$ROOT/Packaging/INSTALL.txt" "$STAGE/INSTALL.txt"
cp "$ROOT/LICENSE" "$STAGE/LICENSE.txt"
hdiutil create -volname "Fader 1.02" -srcfolder "$STAGE" -fs HFS+ -format UDZO "$DEST"
hdiutil verify "$DEST"
