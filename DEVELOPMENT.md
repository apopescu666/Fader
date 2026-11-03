# Development baseline — Fader 1.02, Build69

Date: 2026-11-03. The owner confirmed that Lite Test68 works. Build69 promotes that behavior into the production bundle, removes test window labels and uses version 1.02. No new audio, search or UI feature is added in this packaging step.

## Preserve the approved behavior

- Lite and full Fader share one Player instance. Transfer live playback by changing the list/source indices, not by stopping, reopening or seeking the audio.
- Lite expansion/drop must include the playing song. One transaction and one dialog handle the whole batch. Add deduplicates by normalized path; Replace retains the Lite song in the replacement list.
- Cancel preserves the saved base queue and saved position while the temporary song continues playing.
- Direct multiple-file Finder opening stays temporary; it is not a Lite queue transfer.
- Keep production data compatibility and the distinction between Current Queue and Playing From.
- Preserve themes, window/pane persistence, empty-playlist drop handling, the PLAY seek handle, graduated volume, speaker waves and percent display.
- Preserve the bounded network import scheduler, active-folder priority, background imports, Smart Refresh and single-letter search behavior.

## Main files

- `Sources/UI/FaderPresentationController.swift`: Finder/Lite/full-window routing, one queue-transfer transaction and dialog.
- `Sources/UI/FaderLiteView.swift`: compact player and Lite drop area.
- `Sources/Services/FaderLiteImport.swift`: complete batch collection and current-song inclusion.
- `Sources/Audio/Player.swift`: shared playback, source reassignment and Cancel checkpoint.
- `Sources/Services/FaderFileOpenRouter.swift`: native Finder open events.
- `Sources/Services/FaderStorage.swift`: production and explicitly compiled test storage.
- `Sources/UI/FaderLayoutMemory.swift` and related layout files: window/pane persistence.
- `Sources/Services/Library.swift`, `SQLiteSearchService.swift` and Smart Refresh services: library/search/import behavior.

## Safe development workflow

1. Copy this source baseline or use a version-control branch.
2. Make narrowly scoped changes and add regression coverage for actual failures.
3. Run focused tests, then the relevant playback/search/session checks.
4. Build with warnings treated as errors.
5. Test with isolated profiles or a separate macOS account. A normal build with `com.fader.player` uses existing production data.
6. Increment build/version deliberately; update release notes, verification and checksums.
7. Keep release archives immutable for rollback. Do not commit `.build`, built apps, personal data or music.

`FADER_LITE_PREVIEW` exists only for deliberately compiled isolated Lite development builds. The release build script does not set it. `FADER_TESTING` uses UUID profiles for automated tests. Test source filenames retain historical numbers to preserve regression provenance; they are not installed applications.

Older references are archived separately: published 1.0 Build57, 1.01 Build64 and Stage10D49. Use Build69 as the current baseline.
