#!/bin/bash
set -euo pipefail
TASK_DIR="$(cd "$(dirname "$0")" && pwd)"
TASK_APP="$TASK_DIR/Fader Test 57.app"
TASK_BACKUP="$HOME/Library/Application Support/FaderPlayer/Backups/Before-Build57-Layout"
if /usr/bin/pgrep -x Fader >/dev/null; then
  echo 'Quit Fader with Cmd+Q, then reopen Start Test.command.'
  exit 1
fi
if [ ! -d "$TASK_APP" ]; then
  echo 'Extract the entire ZIP package before launching.'
  exit 1
fi
if [ ! -f "$TASK_BACKUP/.complete" ]; then
  mkdir -p "$TASK_BACKUP"
  if /usr/bin/defaults read com.fader.player >/dev/null 2>&1; then
    /usr/bin/defaults export com.fader.player "$TASK_BACKUP/preferences.plist"
  else
    if [ -f "$HOME/Library/Preferences/com.fader.player.plist" ]; then
      echo 'Cannot back up preferences. The test has stopped.'
      exit 1
    fi
    touch "$TASK_BACKUP/preferences.absent"
  fi
  touch "$TASK_BACKUP/.complete"
fi
echo "Preferences backup: $TASK_BACKUP"
echo 'Opening build 57. This does not install into Applications or reconvert the session from build 56.'
/usr/bin/open "$TASK_APP"
