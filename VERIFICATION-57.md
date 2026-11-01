# Fader Test 57 — layout persistence

Based on Test 56. Only the four files listed in Changed-files.txt differ in Sources. FaderApp changes only the invisible window observer attachment from 0×0 to noninteractive 1×1.

All audio, Finder routing, search, Smart Refresh, session persistence and metadata code is byte-identical to Test 56. The build script changes only bundle version 56 → 57; the session conversion helper source is unchanged.

Geometry now uses an explicit stable window-frame preference and saves pane positions plus proportions. User resize notifications and drag completion persist divider choices; automatic parent resizing does not replace those choices. Closing or quitting flushes pending geometry writes. Existing legacy geometry keys are accepted, and frames are constrained to an available screen.

Validation: optimized Apple Silicon app compiled with warnings-as-errors; binary is newer than every source. LayoutMemory.log records passing native invisible window resize/move/close tests, divider restoration, legacy/invalid preferences, screen fitting and persistence across separate processes. Tests use an isolated preference domain. Actual visible mouse interaction remains for user confirmation.

The launcher backs up preferences only, does not install into Applications, and does not reconvert the existing compact session. No user library or session was modified by automated tests.
