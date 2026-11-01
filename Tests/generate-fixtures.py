#!/usr/bin/env python3
"""Generate synthetic test audio and artwork. Requires Python 3 and FFmpeg."""
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import zlib

OUTPUT = Path(__file__).resolve().parent / "fixtures"
FFMPEG = shutil.which("ffmpeg")
if not FFMPEG:
    raise SystemExit("FFmpeg is required to regenerate fixtures.")
OUTPUT.mkdir(exist_ok=True)

def run(*args):
    subprocess.run([FFMPEG, "-hide_banner", "-loglevel", "error", "-y", *args], check=True)

def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)

with tempfile.TemporaryDirectory(prefix="fader-synthetic-fixtures-") as temp:
    png = Path(temp) / "cover.png"
    size = 128
    pixels = b"".join(b"\x00" + bytes(channel for x in range(size) for channel in
        ((20, 110, 200) if (x // 16 + y // 16) % 2 else (230, 180, 40))) for y in range(size))
    png.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b""))
    jpeg = OUTPUT / "cover.jpg"
    run("-i", str(png), "-frames:v", "1", str(jpeg))
    tone = ["-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=0.3"]
    def audio(name, codec, tags=(), cover=None):
        args = list(tone)
        if cover:
            args += ["-i", str(cover), "-map", "0:a", "-map", "1:v", "-c:v", "copy",
                     "-disposition:v:0", "attached_pic", "-metadata:s:v:0", "title=Cover",
                     "-metadata:s:v:0", "comment=Cover (front)"]
        args += ["-c:a", codec]
        for key, value in tags:
            args += ["-metadata", key + "=" + value]
        run(*args, str(OUTPUT / name))
    audio("tagged.mp3", "libmp3lame", (("title", "  Titlu Știință  "), ("artist", "Artist Test"), ("album", "Album Test")))
    audio("tagged.m4a", "aac", (("title", "M4A Title"), ("artist", "M4A Artist"), ("album", "M4A Album")))
    audio("tagged.flac", "flac", (("title", "FLAC Știință"), ("artist", "FLAC Artist"), ("album", "FLAC Album")), png)
    audio("jpeg-cover.flac", "flac", (("title", "JPEG FLAC"),), jpeg)
    audio("no-cover.flac", "flac")
    audio("covered.mp3", "libmp3lame", cover=png)
    audio("Fallback Artist - Fallback Title.wav", "pcm_s16le")
(OUTPUT / "test.m3u8").write_text("#EXTM3U\ntagged.mp3\ntagged.m4a\ntagged.mp3\n", encoding="utf-8")
print("Generated seven 0.3-second sine-wave files, geometric artwork, and a relative test playlist.")
