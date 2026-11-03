# Fader 1.02 — Build69 verification

Date: 2026-11-03. Baseline: user-validated Lite Test68. Release differences are production compilation/storage, clean window titles, and version/build metadata. Production bundle: com.fader.player; version 1.02; build 69; arm64; macOS 14+.

## Checks executed

- Optimized production build with warnings treated as errors: passed without compiler warnings.
- App/helper ad-hoc signature and strict deep verification: passed.
- DMG creation and hdiutil integrity verification: passed.
- Tests/run-tests.sh: passed (audio metadata, FLAC/MP3 covers, malformed input, format/scan/dedup behavior, layout bounds).
- Tests/run-layout-memory-tests.sh: passed (native frame/pane changes and new-process restoration).
- Tests/run-plan-tests.sh: passed (Finder routing, audio seek/crossfade, 60,000-track synthetic session/search, Unicode/literal/one-letter parity, cancellation and paging).
- Tests/run-lite-tests.sh: passed (deferred restore, saved data preservation, same live audio on expansion, Add/Replace/Cancel, deduplication/order, restart).
- Tests/run-presentation-tests.sh: passed (real native transfer sheet; current Lite song included; one dialog updated in place; direct Finder batch stays temporary; Cancel preserves saved queue and live song).
- Tests/run-smart-refresh-tests.sh: passed (unchanged/new/modified/missing files and cache preservation).
- Tests/run-volume-tests.sh: passed (binding/animation target, handle alignment, speaker waves and percent display).

The user confirmed Test68 with real use before release preparation. Automated tests use isolated profiles and synthetic files; their source files remain as development regression assets. No personal music, caches, session or library is included in release archives. No new network or device performance guarantee is implied. The app is ad-hoc signed and not Apple-notarized.

## Release baseline

Keep the full Source directory or the matching source ZIP, the Build69 DMG, SHA256SUMS.txt and this record. Source-SHA256.json records the clean source contents. Archive keeps older stable references (Build57, Build64, Stage10D49). Installed experimental editions and their isolated profiles are removed separately; production FaderPlayer data is preserved.
