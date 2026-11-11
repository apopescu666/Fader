# Fader for Android TV — Version 1.0

Fader for Android TV is a remote-friendly music player for streaming audio directly from shared folders on a local network. It is a separate Android TV application alongside **Fader for macOS**; the two versions have independent source code.

> **Documentation note:** This README has been updated to identify the project as **1.0**, based on the supplied earlier Android TV documentation. Confirm the exact feature set, supported Android versions, package ID, and release APK against the 1.0 source before publishing a release.

## Features

- **Network-share playback:** Connect directly to SMB shares using an address such as `smb://server/share` and browse music without downloading the entire library.
- **SMB compatibility:** Prefer signed SMB2/3 connections, with compatibility fallbacks for older servers. The interface identifies the active connection mode.
- **Music library browsing:** Navigate folders using a TV remote, view tracks, and recursively index nested music folders. Large folder listings load progressively.
- **Playback controls:** Play/pause, previous/next track, seeking, shuffle, and adjustable crossfade.
- **Favorites:** Save favorite tracks locally across application restarts.
- **Search:** Filter tracks within the current folder or search across the connected share.
- **Now Playing:** View album art, title, artist, album, playback progress, and the next track in a TV-friendly layout.
- **Ambient listening screen:** Display playback information and a music spectrum after a configurable period of inactivity.
- **Audio spectrum:** Use Android's audio visualization capability when supported, with an animated fallback if audio data is unavailable.
- **Personalization:** Dark, light, and automatic themes; sleep timer; spectrum and ambient screen settings.
- **Playback resume:** Restore the previously browsed folder and playback position after reconnecting.
- **Optional saved credentials:** Store share login details using Android Keystore encryption and remove them from Settings.
- **Artwork and metadata:** Read embedded or folder cover images and infer basic metadata from filenames and folder names when tags are missing.

## Requirements

- An Android TV device with a compatible Android version (the earlier documentation targeted Android 12+; confirm for version 1.0).
- Access to a trusted local network with an SMB file share containing music.
- Android Studio, Android SDK, and the project's Gradle wrapper to build from source.

## Connect to a network share

1. Open **NET SHARE** and choose **Add share**.
2. Enter a complete SMB address, for example `smb://server/music`.
3. Enter the username, password, and optional domain required by your server.
4. Connect and select a music folder to browse or play its contents.

For better security, use a dedicated read-only account on your NAS or file server. Saving login details is optional.

## Build from source

Open the `AndroidTV` directory in Android Studio and allow Gradle to sync. Configure the Android SDK through your local environment or an **untracked** `local.properties` file.

From the Android TV project directory:

```sh
./gradlew :app:assembleDebug
```

For a release build, when release signing is configured:

```sh
./gradlew :app:assembleRelease
```

Depending on the project's configuration, generated APKs can be found under `app/build/outputs/apk/`.

The earlier project documentation also includes a test runner:

```sh
sh tests/run-tests.sh
```

Check the script's availability and compatibility with version 1.0 before running it.

## Security and privacy

- The application connects **outbound** to network shares; it does not require router port forwarding.
- SMB traffic should remain on a trusted local network. Never expose SMB directly to the internet.
- Prefer SMB2/3 with signing where supported. Older compatibility modes may offer weaker protection.
- When enabled, saved credentials are encrypted using Android Keystore.
- Android audio visualization may require `RECORD_AUDIO` permission to analyze playback levels. According to the earlier implementation documentation, these levels are used locally for display and are not recorded or transmitted.

## Testing

Test the final APK on a physical Android TV device, including navigation with the remote, SMB compatibility, audio playback, crossfade, artwork, sleep behavior, and reconnect/resume functionality. Emulator tests alone cannot confirm all device-specific behavior.

See `TEST-PE-TV.md`, `VERIFICATION.md`, and `THIRD-PARTY.md` (where present) for project-specific testing notes and third-party notices.

## Repository layout

```text
Fader/
├── Packaging/         # macOS project
├── Resources/         # macOS project
├── Sources/           # macOS project
├── Tests/             # macOS project
├── Tools/             # macOS project
└── AndroidTV/         # Android TV project
    ├── app/
    ├── gradle/
    ├── tests/
    ├── build.gradle
    ├── settings.gradle
    ├── gradlew
    ├── gradlew.bat
    └── README.md
```

Do not commit local SDK configuration, passwords, signing keys, IDE caches, or generated build output. Keep the project's `.gitignore` in version control.

## Version

**Fader for Android TV 1.0**

This README documents the Android TV source project and is separate from the macOS release history.
