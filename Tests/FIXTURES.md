# Synthetic test fixtures

All audio in fixtures/ is a generated 440 Hz sine wave lasting 0.3 seconds.
The cover is a programmatically generated two-color checkerboard, encoded as PNG
inside the audio fixtures and as JPEG for the artwork-cache tests. No music,
album artwork, personal playlists or user-library metadata is used.

The test.m3u8 playlist contains relative references to synthetic test files only.
Unicode titles are intentional parser and search test data.

These generated test assets and their generator are provided under the project's
MIT license. They replace the earlier fixtures for publication; the original
build 57 source and installation archives remain unchanged.

To regenerate, install FFmpeg separately and run:

```sh
python3 Tests/generate-fixtures.py
```

Python 3 and FFmpeg are needed only to regenerate the committed fixtures.
Existing test scripts use the committed fixtures directly.
