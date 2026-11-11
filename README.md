# Fader — Music Player for macOS & Android TV

Fader is a free, open-source music player available for macOS and Android TV.

## Available Versions

### macOS — Version 1.02 (Build 69)

Native music player for Apple Silicon Macs running macOS 14 or later.

**[Download Fader for macOS](https://github.com/apopescu666/Fader/releases)**

### Android TV — Version 1.0

Native music player for Android TV, featuring network music browsing, playback controls, favorites, and a TV-friendly interface.

**[Download Fader Android TV 1.0](https://github.com/apopescu666/Fader/releases/tag/android-tv-v1.0.0)**

**[Android TV Source Code & Documentation](https://github.com/apopescu666/Fader/tree/main/AndroidTV)**

---

## macOS Documentation

# Fader 1.02

A native local music player for Apple Silicon Macs running macOS 14 or later.

**Version:** 1.02 · **Build:** 69 · **License:** MIT

## Features

- MP3, M4A, AAC, WAV, AIFF and FLAC playback, with metadata and artwork.
- Music folders, playlists, Current Queue, global and local search.
- Smart Refresh and background folder loading with limited concurrent reads.
- Crossfade, shuffle, repeat, seeking, graduated volume and speaker wave feedback.
- Persistent library, playlists, playback session, window and pane sizes.
- Integrated Fader Lite for opening a single audio file from Finder.

## Install

Download or open `Fader-1.02-Build69-AppleSilicon.dmg`. Quit Fader, then drag **Fader.app** to **Applications**, replacing the older app. Launch it from Applications. Installation does not change your default player.

The app is ad-hoc signed and is not notarized by Apple. If macOS blocks it, review System Settings → Privacy & Security and permit opening only if you trust the source.

No personal music, library, playlists, sessions, caches or test profiles are bundled. A new macOS account starts empty; an existing account keeps its production Fader data. Test65–68 data is deliberately not imported.

## Finder and Lite

- A single file opened from Finder starts in Lite when the full window is not visible.
- **Open Fader**, **Add tracks**, or dropping files on Lite transfers the playing Lite song together with new files into Current Queue.
- An empty queue accepts the transfer directly. A populated queue shows one **Add to Current Queue / Replace Queue / Cancel** dialog for the complete batch.
- Add preserves existing entries and appends unseen paths. Replace uses the complete new batch, including the Lite song. Playback continues from its current position and its source becomes **Current Queue**.
- Cancel keeps the saved queue intact and continues temporary Finder playback in the full window.
- Multiple songs opened directly from Finder remain in a temporary **Opened from Finder** list and do not modify Current Queue.
- Files on disk are not modified or deleted by these queue choices.

## Build

Requirements: Apple Silicon Mac, macOS 14+, Apple Command Line Tools (Swift compiler), Python 3 for some tests.

```sh
bash build.sh
```

The script builds an optimized arm64 `Fader.app`, treats compiler warnings as errors, generates the icon, signs the app ad-hoc and verifies the signature. It does not install the app. Existing local builds are preserved as build backups.

To create the DMG in an output directory:

```sh
bash Packaging/build-dmg.sh /absolute/output/directory
```

## Tests

Tests are development assets, not installable test editions. They use synthetic audio and isolated profiles when compiled with `FADER_TESTING`.

```sh
bash Tests/run-tests.sh
bash Tests/run-layout-memory-tests.sh
bash Tests/run-plan-tests.sh
bash Tests/run-lite-tests.sh
bash Tests/run-presentation-tests.sh
bash Tests/run-smart-refresh-tests.sh
bash Tests/run-volume-tests.sh
```

See `VERIFICATION.md` for checks actually executed for this release. Manually check audible playback, real Finder/drop events and your own storage setup before a future release.

## Data and backups

Production identifier: `com.fader.player`. Data: `~/Library/Application Support/FaderPlayer/`. Preferences: `~/Library/Preferences/com.fader.player.plist`. Music remains in its original locations. Quit Fader before backing up or restoring its data. A source archive or DMG is not a backup of your personal library.

## Continuing development

Read `DEVELOPMENT.md`. Keep this full source tree, `build.sh`, resources, tools, packaging scripts and tests. Keep the Build69 DMG and source archive as the validated baseline. Generated `.build/` and `.app` outputs are unnecessary for source version control. See `Source-SHA256.json` and the external `SHA256SUMS.txt` for integrity checks.

## Limitations

Apple Silicon/macOS 14+ only; no Intel or mobile build. Initial metadata scans of large or network folders can still take time. No fixed CPU usage or network performance is guaranteed. Some source labels may truncate in narrow panes and expose their full text on hover.

Copyright (c) 2026 Andrei Serban. MIT License. See `ASSET-PROVENANCE.md` for icon and synthetic fixture provenance.
