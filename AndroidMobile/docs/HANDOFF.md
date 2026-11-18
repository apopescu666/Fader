# Handoff — Fader Motor (0.41)

## Arhitectură
- `core/FaderPlayer` — faţadă stabilă pentru UI/servicii (API-ul vechi păstrat)
- `core/EngineBackend` — coada, shuffle/repeat, user queue, restore; citeşte starea
  motorului printr-un cache actualizat pe alt thread (fără blocaje pe main)
- `engine/MixerEngine` + `AudioDecoder` + `PcmStream` + `AudioSink` — motorul propriu
- `engine/EngineAdapter` — `Player` Media3 peste motor (AA/MediaSession)
- `playback/PlaybackService` — notificare + lock screen (neschimbat)
- `playback/FaderMediaService` — bibliotecă Media3 pentru Android Auto

## Verificare
- `./build.sh assembleDebug`, `./build.sh lintDebug` — verzi
- `docs/TEST-MATRIX.md` — testele manuale pe device
- APK-uri: 0.39 (etapa 4+3), 0.40 (etapa 5), 0.41 (final)

## Dacă apare o regresie
Revinzi la `Fader-Motor-0.38-debug.apk` (checkpoint stabil, ExoPlayer).
