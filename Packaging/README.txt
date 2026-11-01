Fader Test 57 — window and pane size persistence fix
Apple Silicon, macOS 14+

1. Extract the entire ZIP package.
2. Quit Fader with Cmd+Q.
3. Open Start Test.command.

The launcher backs up preferences only, under:
~/Library/Application Support/FaderPlayer/Backups/Before-Build57-Layout

It uses the same library and compact session as build 56. It does not run the
initial conversion or install into Applications. The backup from before build
56 remains intact. Do not run two versions of Fader simultaneously.

Manual verification on your Mac
- Resize and move the main window.
- Move the pane dividers, including the Playback & Queue divider.
- Quit with Cmd+Q and reopen: window size, position, and pane proportions should persist.
- Also verify closing the window with the red button and reopening it.

Build 57 changed geometry persistence and its window observer attachment only.
Audio, Finder opening, search, Smart Refresh, and compact sessions are unchanged
from build 56. Automated geometry tests used invisible native windows and an
isolated test profile. Visible interaction still requires manual verification.

This directory contains the test distribution launcher, not a standalone app.
Keep Start Test.command beside Fader Test 57.app when preparing that distribution.
