# Fader Mobil

Player de muzică pentru telefon, construit în jurul accesibilității: se poate
folosi complet cu TalkBack, cu o singură mână, cu taste hardware și cu text
mărit până la 200%.

**Stare:** M2 — bibliotecă locală + WebDAV/HTTPS. Aplicația scanează
MediaStore, se conectează la un share WebDAV (merge și din alte rețele),
navighează, redă prin streaming cu seek și extrage coperta din fiecare fișier.

## Ce există acum

- Kotlin + Jetpack Compose (Material 3), o singură activitate
- Ecrane: **Library**, **Browse** (foldere + melodii), **Now Playing**, **Queue**, **Settings**
- Bibliotecă locală din MediaStore, indexată o dată în fundal; listările sunt citiri din memorie
- Redare reală prin ExoPlayer: play/pause, next/prev, progres, mini player
- Coperti reale: album art din MediaStore, cache în memorie pe album, încărcare asincronă
- **WebDAV/HTTPS**: adaugi adresa share-ului + credențiale, navighezi și asculți prin streaming cu seek — merge și pe date mobile
- Coperti extrase per piesă din ID3 (MP3), FLAC PICTURE sau atomul `covr` (M4A), cu cache pe disc
- Credențialele WebDAV se salvează criptat (Android Keystore) și se reconectează automat la pornire
- Design inspirat iOS: titluri mari, carduri grupate, tab bar jos, mini player
- Accent unic teal, ținte de atingere ≥ 44 pt, contrast ridicat
- Iconițe desenate pe canvas — zero dependință de iconițe
- Strat de transport portat din aplicația anterioară: SMB, SFTP, WebDAV, local
- `core/PlayerCore.java` — motorul de redare, legat la UI prin `core/FaderPlayer.kt`

## Structura

```
Fader Android Mobil/
├── app/src/main/java/com/fader/mobil/
│   ├── MainActivity.kt
│   ├── ui/            # FaderApp, theme, screens, components
│   ├── data/          # modele + date demonstrative
│   └── core/          # cod Java portat: player + clienți de transport
├── app/src/main/res/  # manifest, temă, iconiță
├── design/            # mockup-uri HTML + capturi + planșă
├── docs/PLAN.md       # plan de produs și arhitectură
├── build.sh           # wrapper peste ./gradlew (vezi mai jos)
└── gradle/            # Gradle 9.6.0, AGP 9.4.1, Kotlin 2.2.10
```

## Build

```bash
./build.sh assembleDebug     # APK în app/build/outputs/apk/debug/
# APK gata de instalat: Fader-Mobil-0.1-debug.apk (în rădăcina proiectului)
./build.sh installDebug      # pe dispozitiv conectat
./build.sh lintDebug
```

`build.sh` setează `JAVA_HOME` (JDK-ul din Android Studio), `GRADLE_USER_HOME`
și `ANDROID_USER_HOME` în interiorul proiectului, pentru că sandbox-ul agentului
nu permite scrierea în `~/.gradle` și `~/.android`. În afara sandbox-ului merge
și `./gradlew` direct, iar Android Studio deschide proiectul normal.

## Dezvoltare

- `docs/PLAN.md` — viziune, funcționalități, arhitectură, bugete de performanță
- `design/README.md` — regulile vizuale și modul de regenerare a mockup-urilor

## Limită legală

Niciun cod din AIMP nu e folosit aici. AIMP a furnizat doar lista de
funcționalități și lecțiile de UX.
