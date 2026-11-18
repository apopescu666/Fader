# Fader Motor — arhitectura motorului propriu

## De ce
ExoPlayer redă un singur stream per instanță; crossfade/gapless depind de comutări.
Motorul propriu decodează 2 surse și le mixează într-o singură ieșire (ca BASS/AIMP).

## Componente
- `AudioSource` — local path sau URL NAS + auth headers
- `MediaExtractor + MediaCodec` per sursă (MP3/AAC/FLAC/OGG; PCM 16-bit stereo)
- `Resampler` — orice sample rate → 48000 Hz
- `RingBuffer` per sursă (PCM)
- `Mixer` — sumă ponderată, envelope equal-power, o singură `AudioTrack`
- `PlaybackEngine` — stare, coadă, index, shuffle, repeat, seek, restore
- `HttpMediaDataSource` — random access prin OkHttp (Range + auth) pentru NAS

## Semantica crossfade
- poziția redării = cadre PCM consumate de mixer (sample-accurate)
- fade-ul pornește când `remaining <= fadeMs`, calculat din durata reală
- gain A = cos(p·π/2), gain B = sin(p·π/2); p din poziție, nu din timer
- Next/Back = același mixer, doar sursa B devine A după rampă

## Etape
1. M1: decode + play local, play/pause/seek (implementat: AudioDecoder, AudioSink, EnginePlayer)
2. M2: mixer + crossfade equal-power (MixerEngine, PcmStream, resampling liniar)
3. M3: coadă, shuffle/repeat, restore, durată exactă
4. M4: HTTP/NAS (MediaExtractor cu headers), durată exactă, prefetch — parțial
5. M5: integrare UI Fader, MediaSession, notificare, Android Auto (adaptor Media3 implementat: EngineAdapter)
