# Fader

A local audio player for macOS with a folder-based music library, playlists, search, and crossfade.

**Version:** 1.0 · **Build:** 57  
**Platform:** Apple Silicon (arm64) · macOS 14 or later

## Features

- MP3, M4A, AAC, WAV, AIFF, and FLAC playback.
- A music library organized into folders and playlists.
- Current Queue and a Playback & Queue panel.
- Global search and search within the current list.
- Smart Refresh for updating folder contents.
- Metadata and artwork display, including embedded FLAC artwork.
- Crossfade, shuffle, repeat, and seeking.
- Opening audio files from Finder.
- Session restoration and persistent window and pane sizes.

## Installation

1. Download `Fader-1.0-Build57-AppleSilicon.dmg` from the repository's Releases section once the package has been published.
2. Quit any running version of Fader with **Cmd+Q**.
3. Open the DMG and drag **Fader.app** into **Applications**.
4. If an earlier version is installed, replace it.
5. Launch Fader from Applications.

The app is **ad-hoc signed** and is not notarized by Apple. If macOS blocks it, review the message in **System Settings → Privacy & Security** and allow it to open only if you trust the package's source.

The package contains no developer music library, playlists, or playback history. It starts with an empty library on a new account and uses existing Fader data on the same account. Installation does not automatically change your default audio player.

## Getting started

- Use **+** under Music Folders to add a folder containing music.
- Use **+** under Playlists to create a playlist.
- Use Global Search to search the indexed library, or the local search field to search the current list.
- Select a track and start playback. The **ⓘ** button next to Playback & Queue explains the available interactions.

The first scan and indexing of a large folder takes time. Test simultaneous importing and playback with your actual library and storage device; no fixed startup time or CPU usage is guaranteed for every library.

## Opening files from Finder

Fader declares its supported audio formats for Finder's **Open With** menu. Its appearance in that menu depends on macOS registering the application.

- If the track is already in the active list, Fader plays it from that list.
- Otherwise, it plays in a temporary **Opened from Finder** list.
- The temporary list does not automatically add the track to Current Queue or a playlist, and does not modify the original file.
- When opening a single track, playback stops at the end unless Repeat One is enabled.
- On relaunch, Fader restores the saved session without the temporary Finder list.

## Personal data and backups

Application data is stored separately from Fader.app:

| Location | Contents |
| --- | --- |
| `~/Library/Application Support/FaderPlayer/` | Session, library data, caches, and existing backups |
| `~/Library/Preferences/com.fader.player.plist` | Preferences, including window and pane geometry |
| Your original music folders | The audio files used by the app |

Quit Fader before backing up or restoring its data. Keep the entire FaderPlayer directory, preferences, and music files. A DMG or source archive is not a backup of your personal music library.

Do not publish personal data, sessions, caches, backups, or music files in the repository.

## Source code and building

The reference source archive is `Fader-1.0-Build57-Source.zip`. Extract it and use the contents of **Fader-1.0** as your GitHub project root, with `build.sh` and `Sources/` directly at the repository root.

Development requirements:

- An Apple Silicon Mac running macOS 14 or later.
- Apple Command Line Tools, including the Swift compiler.
- Python 3 for some tests.

If the Apple tools are missing, start their installation:

```sh
xcode-select --install
```

From the project directory:

```sh
bash build.sh
```

The script builds an optimized arm64 application targeting macOS 14+, treats compiler warnings as errors, generates the app icon, applies an ad-hoc signature, and verifies the app. The output is **Fader.app** in the project directory. If a previous build exists, the script preserves it as `Fader-build-backup-*.app`.

Building does not automatically install the app into Applications. Quit Fader before testing or replacing an installed version. For development testing, use a separate macOS account or back up your data: an app with the same bundle identifier may use your existing Fader profile.

## Project structure

| Directory / file | Purpose |
| --- | --- |
| `Sources/Audio/` | Playback and audio controls |
| `Sources/Services/` | Library, search, session, and supporting services |
| `Sources/Models/` | Data models |
| `Sources/UI/` | Interface and geometry persistence |
| `Sources/Utils/` | Utilities |
| `Resources/` | Visual assets |
| `Tools/` | Supporting tools, including session preparation |
| `Tests/` | Tests and fixtures |
| `Packaging/` | Instructions and launcher for the test distribution |
| `build.sh` | Application build script |
| `VERIFICATION-57.md` | Documented checks and limitations for build 57 |
| `Changed-files.txt` | Source files changed since build 56 |
| `Source-SHA256.json` | Source checksums from the reference archive |

## Testing

Run the tests relevant to your changes from the project root:

```sh
bash Tests/run-layout-memory-tests.sh
bash Tests/run-tests.sh
bash Tests/run-startup-tests.sh
bash Tests/run-persistence-tests.sh
bash Tests/run-performance-tests.sh
bash Tests/run-smart-refresh-tests.sh
bash Tests/run-plan-tests.sh
```

Automated tests supplement manual checks. For each new release, also verify:

1. Playback, pause, seeking forward/backward, and switching tracks.
2. Crossfade, shuffle, and repeat.
3. Global search, including single-letter queries, clearing the query, and local searches.
4. Importing a large folder and Smart Refresh after adding files.
5. Opening a track from Finder with the app both closed and already running.
6. Session and window/pane size restoration after Cmd+Q.
7. First launch on an account without existing Fader data.

For build 57, compilation with warnings treated as errors and the focused geometry tests passed. The user also confirmed that size restoration worked. This does not establish compatibility with every Mac configuration or music library.

## Continuing development

- Use build 57 as the current baseline and keep its DMG for rollback.
- Keep Stage10D49 separately as an earlier reference.
- Make changes with a limited scope; do not change the audio engine or search semantics for a visual adjustment.
- Preserve data compatibility or provide a migration with backup and rollback support.
- Measure startup, importing, search, and playback separately to identify CPU bottlenecks.
- Test a new version before replacing the stable installation.
- Update the version, documentation, and verification records for each release.

The checksums in `Source-SHA256.json` describe the original build 57 sources. After making changes, generate a new verification record for the new version.

## Files to publish on GitHub

Keep source files, resources, tests, tools, `build.sh`, and documentation in the repository. This README can replace the short README in the source archive.

Example `.gitignore` entries for local build artifacts:

```gitignore
.DS_Store
.build/
*.app/
*.dmg
*.zip
*.log
```

Publish the DMG and source archive under **Releases**, together with SHA-256 checksums for the final files. Do not commit build directories or personal backups. Adding this README does not modify the previously verified build 57 archives.

## Known limitations

- The current distribution supports Apple Silicon and macOS 14+ only.
- The app is ad-hoc signed; this package is not notarized by Apple.
- **Opened from Finder** may be truncated in narrow panels; hovering over it shows the full text.
- Initial scanning and indexing of large libraries may temporarily use significant resources.

## License

Licensed under the [MIT License](LICENSE).

Copyright (c) 2026 Andrei Serban.

Third-party code and assets, if any, retain their respective licenses. Review their attribution and redistribution requirements before adding or publishing them.
