#!/usr/bin/env bash
# Regenereaza capturile de ecran din HTML (Chrome headless, 2x).
set -euo pipefail
cd "$(dirname "$0")"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
BASE="file://$(pwd | sed 's/ /%20/g')"
for f in library songs player settings; do
  "$CHROME" --headless=new --no-sandbox --disable-gpu --hide-scrollbars --user-data-dir="$PWD/.chrome-headless" \
    --force-device-scale-factor=2 --window-size=390,844 \
    --default-background-color=00000000 \
    --screenshot="shots/$f.png" "$BASE/$f.html" >/dev/null 2>&1 || true
  echo "shots/$f.png"
done
