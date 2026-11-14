# Fader Mobil — plan de produs și arhitectură

> Status: **propunere** (v0.1, 12.11.2026). Niciun cod scris încă.
> Context: analiza AIMP a arătat că putem lua doar **idei**, nu cod. Documentul
> de față transformă acele idei în cerințe proprii.

---

## 1. Viziune

Un player de muzică pentru telefon construit **în jurul accesibilității**: se
poate folosi complet cu TalkBack, cu o singură mână și cu taste hardware — nu
doar „funcționează și cu cititorul de ecran", ci e proiectat așa de la început.

Surse: fișiere locale + biblioteca de pe NAS (SMB / SFTP / WebDAV) + stream HTTP.

Punctul de plecare nu e zero: `Fader Android Phone` are deja motorul de redare
(ExoPlayer) și clienții de transport, toate scrise de tine. Le refolosim.

## 2. Principii

1. **Accesibilitatea e cerință tehnică**, cu criterii de acceptare — nu un strat adăugat la final.
2. **Un singur ecran principal.** AIMP are 12 activități; noi avem un ecran + o listă comutabilă + setări.
3. **Zero controale desenate manual.** Orice element interactiv e un widget real, cu semantică.
4. **Fără descărcare completă.** Streaming direct din NAS, cu seek.
5. **O singură temă**, dark by default, contrast verificat. Fără skin-uri.
6. **Reutilizare agresivă** a codului propriu din `Fader Android Phone`.

## 3. Funcționalități

### v0.1 — MVP „pot asculta"

| # | Funcție | Detalii |
|---|---|---|
| 1 | Surse | local (MediaStore) + **WebDAV peste HTTPS** (funcționează din orice rețea) |
| 2 | Navigare | foldere, plus căutare după nume |
| 3 | Redare | play/pause, next/prev, seek, shuffle, repeat unu/tot |
| 4 | Coadă | vizibilă, reordonabilă, „adaugă la coadă" |
| 5 | Now Playing | titlu, artist, album, copertă, progres |
| 6 | Playlist-uri | creare, redenumire, adăugare, ștergere |
| 7 | Favorite | marcaj rapid, listă separată |
| 8 | Fundal | redare în background, notificare media, căști/Bluetooth |
| 9 | Persistență | ultima sursă, poziția, coada |
| 10 | Accesibilitate | TalkBack complet, target 48dp, contrast AA, font 200%, rotire, taste media |
| 11 | **Index local** | foldere + metadate în Room, scanare în fundal; listele nu așteaptă rețeaua |

> Indexul nu e „nice to have": e exact ce lipsește în `Fader Android Phone` și
> ce face AIMP să pară instant pe foldere de 10.000+ fișiere. Vezi §9.

### v1.1 — confort

- sleep timer
- egalizator **cu preset-uri numite** (nu sliders fini de 20 benzi — inaccesabil)
- crossfade, gapless
- import playlist M3U/PLS
- viteză de redare (0.5×–2×)
- continuare redare după reconectare NAS

### v2 — bibliotecă

- filtrare după artist/album/gen
- sincronizare coadă/poziție cu Fader TV (aceeași bibliotecă)

### Deliberat NU

- skin-uri / teme multiple
- visualizer audio
- cloud (Drive, Dropbox) — cel puțin nu în v1
- controale desenate custom, animații decorative

## 4. Stack tehnic

| Strat | Alegere | De ce |
|---|---|---|
| Limbaj | **Kotlin** pentru cod nou, Java păstrat acolo unde e deja | Compose și coroutines cer Kotlin; clasele de transport rămân |
| UI | **Jetpack Compose + Material 3** | semantici de accesibilitate explicite, font scaling corect, mai puțin cod |
| Redare | **Media3 ExoPlayer** (deja folosit în Fader Phone) | matur, suportă FLAC/MP3/AAC/Opus/Vorbis/WAV |
| Media session | **Media3 `MediaSessionService`** | înlocuiește `MediaSession` manual; notificare și lock screen corecte |
| Persistență | **Room** (index, playlist-uri) + **DataStore** (preferințe) | interogări rapide pe biblioteci mari |
| Imagini | **Coil** | copertă în Compose, cache simplu |
| Transport | **WebDAV** peste HTTPS (PROPFIND + Range) | o singură cale, criptată, merge și pe date mobile |
| Async | coroutines + Flow | UI reactiv, fără callback hell |

Alternativa 2 (dacă vrem zero dependințe noi): Java + Views, ca în proiectele
existente. Merge, dar accesibilitatea cere muncă manuală mai multă și UI-ul va
arăta ca acum. Recomand Compose.

**Disponibilitate offline** (verificat în cache-ul local): Kotlin 2.2.10 +
plugin Compose, Compose Material 3 1.4.0, Media3 1.5.1, Room 2.5.0,
coroutines — toate deja descărcate. Lipsește doar Coil (coperti), care se
poate aduce cu o singură descărcare; alternativa e Glide, deja în cache.

## 5. Arhitectură

```
┌──────────────────────────────────────────────┐
│  UI (Compose)                                │
│  NowPlayingScreen · LibraryScreen · QueueSheet│
│  SettingsScreen · PlaylistScreen             │
└───────────────────┬──────────────────────────┘
                    │ stări / evenimente
┌───────────────────▼──────────────────────────┐
│  ViewModels (PlayerViewModel, LibraryViewModel)│
└───────────────────┬──────────────────────────┘
         ┌──────────┴───────────┐
┌────────▼─────────┐  ┌─────────▼─────────────┐
│ Playback         │  │ Data                  │
│ PlayerCore       │  │ LibraryRepository     │
│ PlaybackService  │  │ SourceRepository      │
│ QueueController  │  │ SettingsRepository    │
│ AudioFocus       │  │ PlaylistDao / TrackDao │
└────────┬─────────┘  └─────────┬─────────────┘
         │                      │
┌────────▼──────────────────────▼─────────────┐
│ Transports: Local (MediaStore) · WebDAV      │
│ (portate din Fader Android Phone)           │
└─────────────────────────────────────────────┘
```

La început **un singur modul Gradle** (`:app`) cu pachete clare:
`ui/`, `playback/`, `data/`, `transport/`. Scoatem module separate
(`:playback`, `:data`) doar dacă build-ul devine lent sau vrem să testăm izolat.

### Componente cheie

- **`PlayerCore`** (preluat, adaptat): singleton peste `ExoPlayer`, cu
  `MediaDataSource` custom pentru streaming NAS fără descărcare. Aici stă și
  logica de crossfade/gapless.
- **`PlaybackService`**: `MediaSessionService`, foreground, notificare cu
  acțiuni, integrare cu căști/Bluetooth/lock screen.
- **`QueueController`**: coada ca sursă de adevăr (nu playlist-ul), cu
  reordonare și „play next".
- **`LibraryRepository`**: navigare pe foldere, **indexul Room ca sursă de adevăr**;
  rețeaua doar completează indexul, în fundal (vezi §8). Coperti pe disc, cu
  limită de dimensiune.
- **`TagReader`**: citire de metadate pe bucăți mici (ID3v2/ID3v1/FLAC), nu
  `MediaMetadataRetriever` peste stream de rețea.
- **`SourceRepository`**: sursele salvate (tip, adresă, credențiale criptate),
  reconectare automată.

## 6. Model de date (Room)

```
Source      id, type(LOCAL|SMB|SFTP|WEBDAV), name, host, path, user, secretRef, lastUsed
Track       id, sourceId, uri, title, artist, album, durationMs, trackNo, year, artworkKey, fileSize, addedAt
Playlist    id, name, createdAt, updatedAt
PlaylistItem playlistId, trackId, position
Favorite    trackId, addedAt
PlayQueue   id, trackId, position   (coada curentă, persistată)
PlayState   trackId, positionMs, updatedAt  (un singur rând)
```

`Track` se populează la navigare (nu scanăm tot NAS-ul). Indexul complet e v2.

## 7. Accesibilitate — criterii de acceptare

Fiecare ecran trece prin lista asta înainte să fie „gata":

- [ ] TalkBack citește fiecare control, în ordine logică, cu rol corect (buton, slider, listă)
- [ ] niciun element interactiv sub 48×48dp; țintă preferată 56dp
- [ ] contrast text/fundal ≥ 4.5:1 (verificat, nu estimat)
- [ ] text până la 200% fără trunchiere sau suprapunere
- [ ] informația nu depinde doar de culoare (ex. „redare" are și iconiță + text)
- [ ] funcționează la rotire și pe ecrane mici (minSdk 29, ~360dp lățime)
- [ ] toate acțiunile au echivalent pe tastatură/taste media
- [ ] feedback haptic la acțiuni fără feedback vizual evident
- [ ] `Accessibility Scanner`: zero erori, zero avertismente justificate
- [ ] test real cu TalkBack pe fiecare flux: conectare NAS → navigare → redare → coadă

## 8. Biblioteci mari (10.000+ fișiere)

Diagnostic pe `Fader Android Phone` (cod actual, 12.11.2026) — de ce folderele
mari „se încarcă" greu:

| Cauză | Unde | Efect |
|---|---|---|
| Metadate pe rând, prin rețea | `MainActivity.rowView` → `ArtworkStore.request`: fiecare titlu necunoscut deschide fișierul pe SMB și citește până la 32 MB prin `MediaMetadataRetriever` | prima vizită a unui folder de 10k = 10k citiri de rețea |
| Extracție serializată | `ArtworkStore.RETRIEVER_LOCK` — o singură extracție odată | coada crește liniar, lista „se încarcă" minute |
| Un singur fir pentru tot | `MainActivity.io` = `newSingleThreadExecutor` | listare, căutare și coadă se blochează reciproc |
| Căutare = walk recursiv | `doSearch` → `indexLibrary(rootUrl())` | caută prin tot share-ul, pe firul acela |
| Fără index persistent | fiecare vizită re-listă directorul | 10k intrări SMB de fiecare dată |
| O(n²) pe surse locale | `LocalClient.build()` → `ensureFolder`/`addFolderIfMissing` scanează frații pentru fiecare fișier | startup lent la biblioteci locale mari |

De ce AIMP nu are problema: are **bibliotecă indexată** (DB local) și cache de
metadate — listele vin din DB, nu din rețea, iar etichetele se citesc o singură
dată, pe bucăți mici.

Reguli obligatorii pentru Fader Mobil:

1. **Indexul e sursa de adevăr** pentru liste: UI-ul citește din Room, rețeaua doar completează indexul.
2. **Progressive disclosure**: numele apar imediat după listarea directorului; titlu/artist/coperta se completează în fundal, cu prioritate pe rândurile vizibile.
3. **Fără `MediaMetadataRetriever` pe stream de rețea.** Se citesc doar capetele fișierului: ID3v2 header (~64 KB), ID3v1/APEv2 tail (~8 KB), FLAC STREAMINFO + VORBIS_COMMENT. KB, nu MB.
4. **Cache de director** cu TTL, invalidat pe mtime — reintrarea într-un folder e instant.
5. **Căutare pe index**, niciodată walk recursiv la fiecare căutare.
6. **Conccurență limitată** (2–4 pe sursă) și anulare când rândul iese din ecran.
7. **Paging** (Paging 3 / RemoteMediator) pentru liste lungi.

Bugete de performanță (criterii de acceptare):

- deschidere folder de 10k: **< 300 ms** cald, **< 3 s** rece
- listă utilizabilă la scroll în **< 500 ms** de la tap, chiar dacă metadatele lipsesc
- metadate complete pentru 100 rânduri vizibile: **< 2 s** pe SMB
- căutare în 50k piste: **< 1 s**, fără I/O de rețea
- memorie: **< 200 MB** cu 10k piste în listă

## 9. Riscuri și necunoscute

| Risc | Impact | Mitigare |
|---|---|---|
| Formate: Media3 **nu** suportă ALAC, APE, WavPack, MPC, TTA, DSD | bibliotecă incompletă | verificăm ce formate ai efectiv pe NAS; dacă e nevoie, extensia FFmpeg (atenție la licență LGPL/GPL) |
| Seek pe stream NAS | redare instabilă | deja rezolvat în Fader Phone cu `MediaDataSource`; re-testăm pe surse noi |
| Compose + TalkBack cer semantici scrise manual | a11y incompletă | criterii de acceptare pe ecran + test cu scanner |
| Build offline în sandbox | blocaj la dependințe noi | `.gradle-home` local, verificăm ce e în cache înainte de a adăuga librării |
| Un singur ecran poate deveni aglomerat | UX prost | separăm clar: Now Playing / Bibliotecă / Coadă (sheet) |
| Bibliotecă de 50k+ piste | liste lente, memorie | index Room + paging + metadate pe bucăți mici (§8) |

## 10. Etape

| Etapă | Livrabil | Criteriu de „gata" |
|---|---|---|
| **M0** Fundație | proiect, temă, navigare, ecran gol accesibil | TalkBack citește ecranul, 0 erori scanner |
| **M1** Local + index | ✅ livrat: MediaStore indexat în fundal, foldere, redare ExoPlayer, mini player, ecran de redare | listă instant din memorie, redare reală |
| **M1.5** Coperti | ✅ livrat: album art din MediaStore, cache LRU pe album, încărcare asincronă | listele și playerul arată coperțile reale |
| **M2** NAS | ✅ WebDAV/HTTPS: conectare, navigare, streaming cu seek | album redat din WebDAV, din orice rețea |
| **M3** Organizare | playlist-uri, favorite, persistență | coada supraviețuiește restartului |
| **M4** Sistem | notificare, lock screen, căști, sleep timer | control din căști funcțional |
| **M5** Audio | EQ cu preset-uri, gapless/crossfade | EQ utilizabil cu TalkBack |
| **M6** Polish | audit a11y complet, import M3U | scanner: zero probleme |

## 11. Reutilizare din `Fader Android Phone`

| Clasă existentă | Rol în proiectul nou | Efort |
|---|---|---|
| `PlayerCore` | motorul de redare | mic — adaptare la Kotlin/Flow |
| `NasClient`, `SftpClient`, `WebDavClient`, `LocalClient` | transport | mediu — interfață unificată |
| `StreamDataSource`, `HttpSource` | streaming fără descărcare | mic |
| `MetaParser` | titlu/artist din nume fișier | mic |
| `ArtworkStore` | cache de coperti pe disc | **nu** integral — extracția prin rețea e cauza lentorii (§8) |
| `FavoritesStore`, `QueuePolicy`, `CrossfadePolicy` | logică | mic |
| `PlaybackService` | înlocuit cu `MediaSessionService` | rescris |
| `MainActivity` (1006 linii) | **nu** se refolosește | UI nou de la zero |

## 12. Decizii luate (12.11.2026)

1. **Kotlin + Compose** — da. Java vechi rămâne doar în stratul de transport, temporar.
2. **Surse** — local + **WebDAV/HTTPS**. SMB și SFTP au fost scoase: WebDAV e singura cale care merge și din exterior, criptat.
3. **Formate** — doar cele comune: FLAC, MP3, AAC/M4A, Opus/OGG, WAV. Fără ALAC, APE, DSD în v1.
4. **Proiect nou curat** în `Fader Android Mobil/`; codul util din folderele vechi se mută aici, iar folderele vechi se șterg.
5. **Limba interfeței** — engleză.
6. **Sincronizare cu Fader TV** — nu, în afara scopului.

## 13. Limită legală

Niciun cod, bibliotecă `.so` sau skin `.acsm` din AIMP nu intră în proiect.
AIMP a furnizat doar lista de funcționalități și lecții de UX (inclusiv ce
**nu** facem: controale desenate manual, zero semantică pentru TalkBack).
