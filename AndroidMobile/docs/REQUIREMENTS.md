# Fader Motor — cerințe consolidate

## Surse
- local (MediaStore) + WebDAV/HTTPS (NAS cu user/parolă)
- navigare pe foldere, sursa afișată sub titlu (This phone / host NAS)
- căutare în folder; după redare, folderul se deschide cu piesa marcată
- fără „album" fals = numele folderului codat

## Redare
- play/pause/next/prev/seek; coada = folderul/listă din care ai pornit
- shuffle: ordine fixă, Next afișat = piesa care pornește
- repeat: implicit All; Off/All/One; Off oprește la finalul listei
- gapless + crossfade equal-power, setare 0/3/5/8/10 s, aplicată uniform
- fade la Next/Back, la tranziție automată, pe telefon și în AA
- durată exactă pentru MP3 fără Xing (numărare cadre), cache
- restaurare completă (coadă + index + poziție) la: deschidere app, buton căști, AA
- pornire rapidă: playback întâi, rețea/index în fundal
- închidere app = oprire totală; redeschidere = continuă de unde era

## Notificare / lock screen
- 5 acțiuni: Favorite · Prev · Play/Pause · Next · Close
- inimă contur → plină; roșu în aplicație
- copertă asincronă (embedded)
- buton căști pornește Fader chiar și închis (media button receiver înregistrat)

## Android Auto
- browse Phone / NAS / Favorites / Playlists / Queue
- coperți în liste (ContentProvider)
- butoane Favorite/Shuffle în overflow (dacă host-ul permite)
- reluare din coada salvată; volum/focus resetat
- fade în listele AA (fade-out → salt → fade-in)

## UI
- ecran ambient (player), mini-player cu progres + timp
- Favorite, playlisturi, coadă persistentă
- inimile roșii; țintă/inimă mari în player
- fără Diagnostics/Close în Settings (ascunse)

## Principii
- schimbările de playback se aplică uniform în toate intrările
- nimic nu se blochează pe rețea înainte de redare
- motor propriu cu mixer = crossfade nativ, fără compromisuri
