# Contributing to Fader

Thank you for helping improve Fader. The current baseline is version 1.0, build 57, for Apple Silicon and macOS 14 or later.

## Before starting

For a substantial change, open an issue or discussion describing the problem and proposed behavior. Keep each pull request focused on one problem. Maintainers decide which contributions are included in official releases.

## Development

Follow README.md to build the app. Use a separate macOS test account or back up your Fader data: development builds may share the installed app’s profile.

Do not commit music, personal library data, sessions, playlists, caches, credentials, or local build outputs. Include only fixtures you have the right to redistribute.

## Compatibility

Preserve existing playback, search matching, crossfade, Finder opening, Smart Refresh, and session behavior unless the proposed change explicitly addresses them. Data migrations require compatibility planning, backup, and rollback support. Visual changes should not alter the audio engine or search semantics.

## Pull requests

Describe the problem, resulting behavior, and how you tested the change. Include relevant automated checks and manual verification. UI changes should include before/after images without personal information. Performance changes should report the library size, test scenario, and measurements, rather than unsupported speed claims.

A build should pass with compiler warnings treated as errors. Run the tests relevant to the affected code and check for regressions in playback and session restoration.

## Licensing

Before contributing, review the repository’s LICENSE and any third-party notices. Do not submit code or assets without the necessary rights. If no project license is present, ask the maintainer to clarify licensing before submitting a contribution.
