# Fader Motor — matrice de test (0.41)

Instalare: `adb install -r Fader-Motor-0.41-debug.apk`
(sau direct pe telefon; applicationId `com.fader.motor`, aplicația Fader veche
`com.fader.mobil` rămâne neatinsă).

## A. Local (This phone)
| # | Pas | Așteptat |
|---|---|---|
| A1 | Library → folder → piesă | pornește imediat, fără pas prin „internal" |
| A2 | Play/Pause din mini-player | oprește/pornește fără salt de poziție |
| A3 | Next / Prev | schimbă piesa; cu fade 5s se suprapun |
| A4 | Seek pe bara de progres | sare la poziția aleasă, continuă |
| A5 | Folder mare (5000+ piese) | lista se deschide instant, playback rapid |
| A6 | Shuffle pornit → Next | ordine fixă; piesa afișată „Next" e cea care pornește |
| A7 | Repeat: All / One / Off | la final: coadă / aceeași piesă / stop |

## B. NAS (WebDAV/HTTPS)
| # | Pas | Așteptat |
|---|---|---|
| B1 | sursă NAS conectată, folder → piesă | pornește (poate dura la prima piesă) |
| B2 | Seek în piesă de pe NAS | sare corect, fără eroare |
| B3 | Next pe NAS | crossfade sau switch, fără „Audio Error" |
| B4 | Ecran blocat 2–3 min pe NAS | playback continuă |

## C. Notificare / lock screen
| # | Pas | Așteptat |
|---|---|---|
| C1 | Trage notificarea | copertă, titlu, artist, 5 butoane |
| C2 | Inimă | comută Favorite (contur ↔ roșu) |
| C3 | Prev / Play-Pause / Next | controlează motorul |
| C4 | Close | oprește playback-ul și notificarea |
| C5 | Lock screen | aceleași butoane funcționale |

## D. Căști / apeluri
| # | Pas | Așteptat |
|---|---|---|
| D1 | Buton căști play cu app închis | pornește din sesiunea salvată |
| D2 | Scoate căștile | pauză automată |
| D3 | Apel primit | pauză; la final reia |

## E. Reluare
| # | Pas | Așteptat |
|---|---|---|
| E1 | Închide app cu piesa la 1:30 → redeschide | continuă de la ~1:30, aceeași coadă |
| E2 | Swipe away → redeschide | revine la ultima piesă/folder |
| E3 | Force stop → buton căști | pornește ultima sesiune |

## F. Android Auto (necesită test pe mașină/AA)
| # | Pas | Așteptat |
|---|---|---|
| F1 | Conectare AA | Fader apare în listă |
| F2 | Phone / NAS / Favorites / Playlists / Queue | liste cu coperți |
| F3 | Tap pe piesă | pornește și continuă cu restul listei |
| F4 | Next/Prev/seek din AA | controlează motorul |
| F5 | Deconectare + reconectare | reluare din coada salvată |
| F6 | Favorite / Shuffle din overflow | se aplică |

## G. Regresii
| # | Pas | Așteptat |
|---|---|---|
| G1 | Setări → fade 0 / 3 / 5 / 8 / 10 s | se aplică la următorul Next |
| G2 | Setări → Testează motorul nou | ecranul de test încă funcționează |
| G3 | Căutare → piesă | pornește și intră în folderul piesei |
| G4 | Playlist / Favorite → play | coada = lista respectivă |
