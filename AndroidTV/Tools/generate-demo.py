#!/usr/bin/env python3
"""Generate synthetic test audio and its 10 ms amplitude envelopes. No input music."""
from pathlib import Path
import math
import struct
import wave
assets = Path(__file__).resolve().parents[1] / 'app/src/main/assets'
assets.mkdir(parents=True, exist_ok=True)
rate = 22050
for track, frequency in enumerate((220, 261.63, 196)):
    samples = []
    for i in range(rate * 24):
        t = i / rate
        beat = (t * 2) % 1
        chord = sum(math.sin(2 * math.pi * frequency * r * t) * .09 for r in (1, 1.25, 1.5))
        value = (chord * (.35 + .65 * math.exp(-beat * 6)) + .2 * math.sin(2 * math.pi * 55 * t) * math.exp(-beat * 15)) * min(t * 3, 1) * min((24-t) * 3, 1)
        samples.append(int(max(-1, min(1, value)) * 32767))
    with wave.open(str(assets / f'demo{track}.wav'), 'wb') as output:
        output.setparams((1, 2, rate, 0, 'NONE', 'not compressed'))
        output.writeframes(struct.pack('<' + 'h' * len(samples), *samples))
    envelope = []
    for frame in range(2400):
        chunk = samples[round(frame * rate / 100):round((frame + 1) * rate / 100)]
        envelope.append(math.sqrt(sum((x / 32768) ** 2 for x in chunk) / len(chunk)))
    (assets / f'demo{track}.env').write_bytes(struct.pack('>i', len(envelope)) + struct.pack('>' + 'f' * len(envelope), *envelope))
print('Generated three 24-second WAVs and amplitude envelopes.')
