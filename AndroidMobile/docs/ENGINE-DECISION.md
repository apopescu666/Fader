# Alegerea motorului audio

| Opțiune | Mixer nativ | Formate | Licență | Greutate | Verdict |
|---|---|---|---|---|---|
| ExoPlayer dublu (actual) | nu (2 ieșiri) | toate | Apache-2 | deja | nu rezolvă fade-ul în AA |
| **MediaCodec + AudioTrack** | **da (propriu)** | MP3/AAC/FLAC/OGG (platformă) | platformă | 0 libs | **ales pentru M1–M3** |
| FFmpeg + Oboe | da | orice | LGPL/GPL | +20–40 MB | fallback pentru formate exotice |
| BASS (AIMP) | da | orice | comercial | +2 MB | respins: licență |
| GStreamer | da (audiomixer) | orice | LGPL | mare, integrare grea | respins |
| libVLC | nu (ieșiri separate) | orice | LGPL | mare | nu aduce mixer |

## Decizie
Motor propriu peste **MediaCodec (decode) + AudioTrack (o singură ieșire) + mixer PCM propriu**.
- fără dependențe native noi, fără probleme de licență
- crossfade sample-accurate, identic local/NAS/AA
- durata și seek-ul sunt controlate de noi
- dacă apare un format neacceptat de platformă, se adaugă FFmpeg ca decodor în spatele `AudioDecoder`

## Stadiu
- M1: `AudioDecoder` (MediaExtractor+MediaCodec), `AudioSink` (AudioTrack), `EnginePlayer` (coadă, play/pause/seek/next/prev)
- M2: mixer 2 surse + envelope equal-power
- M3: shuffle/repeat/restore, HTTP MediaDataSource, prefetch
