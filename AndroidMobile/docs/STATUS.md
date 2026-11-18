# Fader 2.0.1 — stare

## Ce s-a schimbat față de 0.38
- **Etapa 4**: playback-ul principal rulează pe motorul propriu (`EngineBackend`),
  nu pe ExoPlayer. UI, notificarea, lock screen-ul și comenzile căștilor trec prin
  `FaderPlayer` → `EngineBackend` → `MixerEngine`.
- **Etapa 3**: Android Auto folosește `EngineAdapter` (motorul ca `Player` Media3):
  browse neschimbat, play/seek/next/prev/favorite/shuffle pe motor.
- **Etapa 5**: `PlayerCore`, `StreamDataSource` și dependența `media3-exoplayer`
  au fost eliminate. APK: 35 MB (de la 37 MB).
- `EngineService` / `EnginePlayActivity` / intrarea beta din meniu au dispărut
  (motorul e calea normală). `EngineDemoActivity` rămâne pentru teste.

## Arhitectură
`FaderPlayer` (fațadă) → `EngineBackend` (coadă, shuffle/repeat, user queue,
restore, stare cache-uită off-main) → `MixerEngine` (decode + mix PCM + AudioTrack)
iar pentru AA: `EngineAdapter` (Media3 `Player`) → `EngineBackend`.

## 0.42 — fix pentru NAS
- citirea NAS mutată pe un thread de rețea cu fereastră de 8 MB
  (`HttpMediaDataSource`); thread-ul audio copiază doar din memorie
- fișierele preluate se redau din cache (disc), nu din rețea
- fără descărcări complete sincrone (durată exactă) pe track-urile NAS
  necache-uite, în timpul redării
- salvarea sesiunii mutată de pe thread-ul audio; buffer AudioTrack mai mare

## 0.43 — pornire rapidă pe NAS (reluare cu căşti BT)
Faţă de Fader 1.0.7 (ExoPlayer pornea imediat pe loader-ul lui), motorul făcea
la fiecare start: deschidea fişierul NAS de două ori (probe + redare), căuta
sfârşitul fişierului de două ori pentru durată, lăsa un downloader deschis după
probe şi pornea prefetch-ul următoarei piese imediat, concurând cu startul.
Acum:
- `AudioDecoder.probe()` citeşte doar header-ul (fără codec, fără seek la final)
- sursa HTTP se închide corect după probe (nu mai rămâne un downloader agăţat)
- durata exactă se măsoară doar pe fişiere locale/cache, nu pe stream
- prefetch-ul remote porneşte cu 4 s întârziere
- dimensiunea fişierului se cere o singură dată pe sesiune (HEAD cache-uit)

## 0.44 — NAS ca la playerele consacrate (AIMP / VLC / ExoPlayer)
Cercetare: AIMP descarcă integral piesa curentă şi preîncarcă următoarea pe la
~90% din piesă / cu un minut înainte de final; Windows Media Player preia parţial
2 piese înainte; VLC foloseşte un cache de reţea; ExoPlayer foloseşte
`CacheDataSource` + `SimpleCache`. Concluzia: redarea nu trebuie să atingă
reţeaua, ci un cache local care creşte în fundal.

Implementat:
- `RemoteCacheSource` — fişierul NAS se descarcă pe un thread propriu într-un
  fişier `.part`; MediaExtractor citeşte de pe disc (seek instant în partea
  descărcată), iar la final fişierul devine cache-ul normal
- probe-ul predă sursa deja pornită redării (o singură deschidere per piesă)
- preîncărcarea următoarei piese porneşte după 15 s (ca să nu concureze startul)
- durata exactă se măsoară pe fişierul cache complet → crossfade automat corect
  şi pe NAS (nu doar la Next/Prev)
- time-out de 30 s fără progres → eroare controlată, nu blocare
- trace „first audio after X ms" în `EngineTrace` (vizibil în Setări → Testează
  motorul nou) pentru diagnostic de pornire

## 0.45 — seek în mini-player + fix „snap back"
- `FaderSeekBar` comun (player + mini-player): poziţia aleasă rămâne pe ecran
  până când motorul raportează noua poziţie (înainte sărea la cea veche şi
  revenea)
- bara de progres din zona de jos a listei (mini-player) e acum trăgătoare:
  tap = salt, drag = seek, cu timpul actualizat în timp real
- prag de siguranţă de 4 s pentru seek-uri foarte lente (NAS), ca bara să nu
  rămână blocată pe poziţia aleasă

## 0.46 — shuffle corect (Next/Prev + ce se afişează)
Trei cauze găsite în cod:
- `previous` ignora shuffle-ul: mergea la `index - 1` din ordinea folderului, nu
  din ordinea amestecată → altă piesă decât cea aşteptată
- `peekNext` (întrebat de UI la fiecare 400 ms şi de bucla audio) **re-amesteca
  toată coada** la fiecare apel când era la finalul ciclului → „Next" afişat se
  schimba aleator, iar Next pornea altă piesă decât cea afişată
- la crossfade, titlul trecea pe piesa care intra, deşi se auzea încă cea veche

Acum: ordine de shuffle fixă pe ciclu (wrap la Repeat All, fără re-amestecare la
citire), Prev/Next parcurg aceeaşi ordine, „Next" afişat = exact ce porneşte,
titlul rămâne pe piesa auzită până la finalul fade-ului. Bonus: Next dublu în
timpul fade-ului nu mai scapă un decoder, iar adăugarea în coadă în timpul
redării nu mai desincronizează indexul.

## 0.47 — crossfade automat pe NAS
Cauza: fade-ul automat are nevoie de durata reală (`duration - poziție <= 5 s`).
Pe NAS durata se măsura doar prin numărarea cadrelor MP3; pentru FLAC/AAC (sau
înainte ca fișierul să fie complet în cache) rămânea 0 → condiția nu se împlinea
niciodată, iar la final motorul făcea doar un fade din silențiu. În plus,
măsurătoarea rula pe thread-ul audio exact în momentul în care trebuia să înceapă
fade-ul → blocaj/gol.

Acum: durata exactă se ia cu `MediaExtractor` pentru orice format, pe un thread
separat (`DurationReader.ensure`), niciodată în bucla audio; fade-ul automat
pornește din durata reală, ca la Next/Prev.

## 0.48 — foldere mari, repeat fals, crossfade NAS
- foldere 10 000+: motorul scria un JSON cu toată coada la fiecare 1,5 s (deși
  aplicația nu îl folosea), iar aplicația rescria sesiunea cu 10 000 de piese la
  fiecare 5 s pe thread-ul principal → jank. Acum motorul nu mai persistă nimic
  pentru aplicație, iar lista se scrie doar când se schimbă (pe IO); poziția
  (2 intregi) se scrie la fiecare tick.
- „repeat 1" fără să fie apăsat: recuperarea de „early end" reia aceeași piesă și
  se re-arma la fiecare reluare → buclă infinită. Acum reîncearcă o singură dată
  per piesă, apoi trece la următoarea.
- crossfade NAS inconsistent: durata se ia acum întâi din decoder (ce se aude
  efectiv), iar piesa următoare e pre-deschisă cu ~6 s înainte de fade, ca
  suprapunerea să nu aștepte NAS-ul; deschiderea e reîncercată o dată, iar
  eșecul se notează în trace.

## 0.49 — titlul din ecranul ambient urmează piesa
- `finishFade` calcula noul index cu `pendingIndex.coerceAtLeast(index)`: când
  tranziţia mergea spre un index mai mic (Prev, shuffle înapoi, tranziţie
  automată în ordine amestecată) indexul rămânea pe piesa veche → se auzea piesa
  nouă, dar titlul/coperta nu se schimbau. Acum se acceptă orice index valid.
- ecranul ambient trece pe piesa nouă imediat ce începe fade-in-ul (înainte
  aştepta terminarea suprapunerii).
- poziţia după crossfade porneşte de la lungimea fade-ului, nu de la 0.

## 0.50 — curățenie de interfață + Back din liste
- scos textul „Works from any network…" din ecranul Add WebDAV
- folderele de serviciu Synology (`#recycle`, `@eaDir`, nume cu `.`) nu mai apar
  în liste
- scos „Engine test (beta)" + „Testează motorul nou" (ecranul de test a fost
  eliminat; motorul e calea principală)
- Back din lista de foldere coboară exact un nivel; intrările duplicate din
  stivă (tap dublu rapid) sunt sărite, ca să nu pară că Back nu face nimic

## 0.51 — foldere mari: cache memorie + index pe disc (stil AIMP)
- `ListingCache` — ultimele 4 listări în memorie (LRU): reintrarea într-un
  folder e instant, fără PROPFIND
- `NasIndexStore` — index pe disc per folder NAS (JSON versionat, scriere
  atomică): la prima vizită se face PROPFIND şi se salvează indexul; la
  următoarele vizite (chiar şi după repornire) lista apare din index, iar
  refresh-ul PROPFIND rulează în fundal şi aplică diferenţele
- fără flash gol: lista veche rămâne pe ecran cât se reîncarcă; doar la prima
  vizită (fără cache/index) apare spinner-ul
- refresh automat dacă lista are peste 15 s; pull-to-refresh forţează reţeaua
- cache pentru lista de UI mapată (`Track`), ca folderele de 10 000 să nu fie
  re-mapate la fiecare intrare
- motorul nu a fost atins

## 0.53 — Back duce în folderul părinte
Cauza reală: după reluarea sesiunii (sau când piesa a fost deschisă din
căutare/coadă) stiva de navigare are **un singur nivel** — folderul curent —
aşa că Back o golea şi sărea direct în Library. Acum `goBack()` derivă părintele
din URL-ul folderului când stiva are un singur nivel: `/share/folder/` →
`/share/` → … → rădăcina share-ului, iar doar de acolo Back duce în Library.
Aceeaşi funcţie e folosită şi de săgeata din stânga sus, şi de Back-ul sistemului.

## 0.54 — ținta arată piesa, plus țintă în mini-player
- butonul „țintă" din player deschidea lista, dar nu derula/evidenția piesa;
  acum setează `focusFolderUrl` + `focusTrackUrl`, deci lista sare direct la
  piesa care cântă (și o marchează)
- mini-player: buton „țintă" nou, centrat între timpul scurs și cel total;
  folosește aceeași acțiune ca player-ul

## 0.55 — coperți pentru liste (fără reîncărcare)
Cauza: un singur cache de imagini de 24 MB, cu coperți decodate la 512 px
(~1 MB fiecare) → încăpeau ~24; la derulare se evictau şi reintrarea re-decoda.
Acum:
- cache separat pentru miniaturi (160 px, 24 MB → ~240 coperți) folosit de
  rândurile din liste şi de mini-player;
- cache-ul mare (512 px, 16 MB) rămâne doar pentru player/notificare;
- decodarea se face la dimensiunea necesară (listă vs player), nu la 512 mereu.

## 0.56 — optimizat pentru foldere NAS foarte mari (50.000+)
- lista din Browse nu mai ține o a doua copie a folderului: se păstrează doar
  indici (ordine/sortare/shuffle/filtrare), rândurile se construiesc la afișare;
  pentru 50.000 piese economie de ~25 MB RAM și intrare mai rapidă
- index NAS v2, compact și **streamed** (JsonReader/JsonWriter): ~8 MB pentru
  50.000 piese în loc de ~25 MB, fără arbore JSON uriaș în memorie
- `ListingCache` limitat după numărul total de piese (120.000), ca două foldere
  de 50.000 să nu stea simultan în RAM
- folderele NAS >10.000 piese nu mai fac PROPFIND automat la fiecare 15 s
  (reîmprospătare la 5 min; pull-to-refresh forțează)

## 0.57 — bibliotecă locală SQLite pentru NAS (stil Kodi/Navidrome)
- `NasStore` — bază SQLite per share: foldere, subfoldere și piese; folderul se
  citește o dată de pe NAS și apoi se deschide instant, inclusiv după repornire
- `NasScanner` — worker de fundal cu prioritate mică: după ce deschizi un folder,
  subfolderele lui sunt citite în fundal (max 200, doar dacă nu au fost scanate
  în ultimele 24 h), ca următorul click să fie instant
- navigarea are prioritate: cât încarci un folder, scannerul pauzează
- refresh-ul unui folder face upsert în bază; dacă nimic nu s-a schimbat se
  actualizează doar `scanned_at`
- JSON-urile vechi `nas-index` sunt șterse automat la prima folosire a bazei
- nimic altceva nu s-a atins (local, favorite, playlisturi, notificare, AA, motor)

## 0.58 — stabilitate + raport de crash
- bucla mixerului e învelită în try/catch: o excepție neașteptată pe thread-ul
  audio e consemnată în `EngineTrace` și motorul se recuperează, în loc să
  închidă aplicația
- `MediaCodec.configure/start` sunt protejate: un fișier cu codec neacceptat
  întoarce „nu se poate reda" în loc să arunce
- crash reporter: ultima excepție necapturată e salvată, iar la următoarea
  pornire apare un dialog „Last crash" cu stack trace-ul (pentru diagnostic
  fără adb)

## 0.59 — fără bară de search în folderele fără melodii
- căsuța „Search in this folder" apare doar când folderul are melodii direct;
  dispare în NAS root și în folderele care conțin doar subfoldere (căutarea
  oricum filtra doar nivelul curent)

## 0.60 — Android Auto: liste instant, coperți, fade la schimbarea listei
- folderele din AA se servesc din cache-ul de memorie → baza SQLite → rețea;
  doar prima vizită plătește PROPFIND-ul, restul paginilor vin instant, cu
  refresh în fundal
- coperți în AA: LRU de 16 MB în serviciul Media3, ca derularea listelor să nu
  mai re-decodeze aceeași copertă
- fade la schimbarea listei în mașină: 600 ms fade-out → schimbare coadă →
  fade-in (Next/Prev/auto rămân pe mixerul equal-power)
- durata pieselor apare în listele AA (extras `fader.durationMs` citit de adaptor)
- când mașina pornește redarea, se pornește și serviciul de notificare al
  telefonului (redarea supraviețuiește cu ecranul stins)
- spațiu mai mare între rândul Shuffle/Play/Sort și lista de foldere (18 dp)
- vocea în AA: API-ul Media3 1.5.1 nu expune onPlayFromSearch în callback, deci
  rămâne disponibilă doar pe telefon

## 0.61 — spațierea din header-ul de folder
- rândul Shuffle/Play/Sort urcă mai sus (8 dp față de copertă, era 12)
- distanțe egale și mici în jurul căsuței de search: butoane → search 10 dp,
  search → listă 10 dp (înainte search → listă era ~28)
- în folderele fără search rămâne separarea de 18 dp față de listă

## 0.62 — reluare rapidă la butonul căștilor (cache-first)
- dacă piesa din sesiune e deja în cache (sau are o descărcare parțială peste
  512 KB), redarea pornește **înainte** de connect-ul WebDAV și de indexul
  local; acelea rulează în fundal
- descărcările NAS întrerupte **nu mai sunt șterse**: la următoarea redare se
  reiau cu cerere `Range` de la lungimea existentă (serverul care ignoră Range
  repornește curat)
- după connect-ul tardiv, cozii restaurate i se completează header-ele de auth
  (`refreshRemoteHeaders`), ca Next/Prev să funcționeze
- poziția se salvează imediat la `onTaskRemoved` și `onDestroy` (cu protecție
  să nu suprascrie cu 0 după `stop()`)

## 0.63 — Android Auto: coadă corectă, coperți în player, nume decodate
- cauza buclei și a butoanelor Next/Prev moarte: când controller-ul nu trimitea
  (sau pierdea) extra-ul `fader.container`, AA seta o coadă de **o singură
  piesă**; motorul repeta aceeași piesă (Repeat All) și Next/Prev nu avea unde
  merge. Acum:
  - `resolve()` păstrează extra-ul container când reconstruiește item-ul;
  - dacă extra-ul lipsește, containerul se derivă din folderul fișierului
    (`folder|<remote>|<url părinte>/|<nume>`), deci coada = folderul întreg;
  - `jumpTo` reconstruiește coada motorului dacă acesta încă rulează altă listă
    (protecție pentru Next/Prev când AA și motorul sunt desincronizate).
- coperțile din ecranele de play AA: item-urile de redare primeau `remote:`/`file:`
  ca artwork, pe care AA nu le poate încărca; acum pointerul e mereu
  `CoverProvider` (content://), ca în liste.
- numele cu `%20` (href-uri WebDAV encodate) sunt decodate pentru afișare, atât
  la listarea live, cât și la încărcarea din SQLite.

## 0.64 — AA: fade la Next/Prev, stare Shuffle, titluri curate
Logul DHU a arătat că:
- shuffle/favorite **chiar se executau** (starea sesiunii a trecut pe „Shuffle on",
  „Remove from favorites" a apărut de 10 ori); butonul nu se „aprindea" pentru că
  ambele stări foloseau aceeaşi iconiţă albă
- Next din AA **schimba** piesa, dar fără fade: mergea prin `jumpTo` (switch
  direct), nu prin calea de fade a motorului
- coada din AA e mare (item activ 188), deci expansiunea containerului din 0.63
  funcţionează; bucla pe o singură piesă era de dinainte

Modificări:
- `MixerEngine.jumpTo` face crossfade când redarea e activă, nu se cere poziţie
  explicită şi nu e deja un fade → Next/Prev din AA se suprapun ca pe telefon
- iconiţă nouă `ic_shuffle_on` (teal) pentru starea activă a shuffle-ului
- titlurile/artistul/albumul sunt decodate şi în adaptorul AA şi în accesoriile
  motorului, deci numele cu `%20` din date vechi nu mai apar

## 0.65 — `%20` curăţat peste tot
Decodare aplicată la toate locurile care construiesc nume afişabile din URL-uri:
- numele folderelor (listare WebDAV + SQLite) şi al pistelor din listări
- item-urile Media3 pentru AA (`SessionItems`): titlu/artist/album + fallback din URL
- cozile telefonului: favorite, adăugare în coadă, redare folder NAS (`RemoteLibrary.play`)
- căutarea (SearchHit), restaurarea sesiunii, `currentName`, notificare
- adaptorul AA + accesoriile motorului (0.64)

## 0.66 — buton Repeat în Android Auto
- al treilea buton custom în AA: **Favorite · Shuffle · Repeat**
- starea e vizibilă prin iconiță: `ic_repeat` (alb, off), `ic_repeat_on` (teal,
  toate), `ic_repeat_one` (teal, una) şi prin etichetă (Repeat off/all/one)
- comanda `fader.repeat` apelează `FaderPlayer.cycleRepeat()` — acelaşi ciclu ca
  butonul din ecranul ambient al telefonului (Off → All → One), deci starea e
  comună în toate zonele (telefon, notificare, AA)
- layout-ul se reîmprospătează imediat după apăsare (cheia include modul repeat)

## 0.67 — stare clară pentru Shuffle/Repeat în AA
- etichete explicite în meniul AA: `Shuffle: off` / `Shuffle: on`, `Repeat: off` /
  `Repeat: all` / `Repeat: one`, `Favorite: add` / `Favorite: remove`
- iconiţe distincte prin formă (nu doar culoare, ca să se vadă chiar dacă AA
  aplică tint): shuffle-on are un punct plin, repeat-off are o bară diagonală,
  repeat-one are „1", iar starea activă e teal

## 0.68 — iconiţe standard Media3 pentru Shuffle/Repeat
AA aplică tint alb şi micşorează iconiţele custom, deci punctul/teal-ul nu se
vedeau. Acum folosim iconiţele standard Media3, gândite pentru AA:
`ICON_SHUFFLE_OFF/ON`, `ICON_REPEAT_OFF/ALL/ONE` — AA le randează cu formă
distinctă pentru stare. Etichetele rămân explicite în meniu.

## 0.69 — ținta în modul AA duce la folderul piesei curente
- cauza: coada venită din AA nu avea extra-ul `fader.container`, deci
  `externalContainer` rămânea null şi ținta cădea pe `playingPath` — ultimul
  folder de pe telefon
- acum adaptorul derivă containerul din URL-ul piesei (`folder|remote|parent/|nume`),
  iar ținta deschide folderul piesei din maşină şi derulează la ea
  (`focusTrackUrl`), chiar dacă extra-ul lipseşte

## 2.0 — devine update pentru Fader
- `applicationId` = `com.fader.mobil` (era `com.fader.motor`), `versionCode` 208,
  `versionName` 2.0, numele aplicaţiei „Fader"
- autoritatea `CoverProvider` = `com.fader.mobil.covers`
- semnat cu acelaşi certificat debug ca Fader 1.0.7 → se instalează ca **update**,
  fără dezinstalare, cu **datele păstrate** (favorite, playlisturi, sesiune,
  setări — aceleaşi chei de preferinţe)
- aplicaţia veche `com.fader.motor` rămâne instalată separat până e dezinstalată;
  prima deschidere după update rescanează folderul NAS în SQLite

## 2.0.1 — curăţenie de cod (fără schimbări funcţionale)
Şterse, după ce nu mai aveau niciun consumator:
- `engine/EnginePlayer.kt` (treapta M1)
- `engine/EngineSessionStore.kt` + `MixerEngine.persistSession/saveState/restore` + executorul de salvare
- `RemoteLibrary.durationFor/ensureDuration` + stocarea de durate MP3 (`skipId3`, `scanMp3*`)
- `remember2` din BrowseScreen
- drawable-urile `ic_shuffle`, `ic_shuffle_on`, `ic_repeat`, `ic_repeat_on`, `ic_repeat_one`
Build + lint verzi; identitate `com.fader.mobil`, versionCode 209, versionName 2.0.1.

## Verificat
- `./build.sh assembleDebug` ✅
- `./build.sh lintDebug` ✅
- APK: `Fader-2.0.1-update.apk`

## Rămas / de urmărit
- test pe device conform `TEST-MATRIX.md` (AA netestat încă pe mașină)
- gapless pur (fără fade) are o mică pauză la switch, inerentă decodării secvențiale
- prefetch agresiv și gapless explicit — candidat pentru 0.43
