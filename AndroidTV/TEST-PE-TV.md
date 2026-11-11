# Test Fader TV 0.6.8.2 „UI” pe TCL

Proiect separat de Fader pentru Mac. Această versiune se conectează **direct la un share de rețea (SMB)**, fără căutare/colecție NAS și fără Local Demo. Față de 0.6.7, interfața principală și ecranul ambient au fost refăcute, au apărut **Favorites**, căutarea globală, spectrul audio real și setările de temă/sunet. Față de 0.6.8.1, 0.6.8.2 adaugă protecții pentru biblioteci mari: `largeHeap`, cache-uri limitate după numărul total de piese, index de metadate ținut doar pentru un folder, extragere serializată a metadatelor, eliberare corectă a playerului/spectrului și jurnal de crash în Settings. Lista de piese rămâne vizibilă cu **LOADING…** până când folderul nou este citit, în loc să se golească.

1. Copiază `Fader-TV-0.6.8.2-UI-Test.apk` pe USB și instalează-l pe TV cu managerul de fișiere. Actualizează versiunea de test existentă. În setările Android, versiunea trebuie să apară ca **0.6.8.2-ui**.
2. Deschide aplicația. În panoul din stânga scrie **NET SHARE**. Apasă **+ Add share**.
3. Introdu adresa completă a share-ului, de exemplu `smb://192.168.158.10/music`, utilizatorul, domeniul (poate rămâne gol) și parola. Folosește un cont cu drept de citire. Aplicația încearcă întâi SMB2/3 cu semnare, apoi modul de compatibilitate SMB2/3 și, dacă serverul cere, SMB1. Bifează **Remember username & password (encrypted on this TV)** dacă vrei ca aplicația să se reconecteze automat la următoarea pornire; parola este stocată criptat cu Android Keystore. Lasă bifa liberă dacă preferi să introduci parola de fiecare dată.
4. După conectare, adresa apare ca un **card de share** în panoul NET SHARE. Dacă ai credențiale salvate, apasă cardul și aplicația se reconectează direct, fără formular. Ține apăsat pe card (sau apasă-l fără credențiale salvate) pentru a deschide formularul și a schimba adresa/parola. Sub lista de foldere apare modul folosit, de exemplu **SMB2/3 signed** sau **SMB1 compat**.
5. Dacă te conectezi la rădăcina serverului (`smb://server/`), panoul afișează share-urile ca butoane. Intră într-un share. La rădăcina share-ului:
   - folderele apar ca butoane, fiecare cu **numărul de piese** (contorul se completează în fundal);
   - fișierele audio din rădăcină apar direct în lista din dreapta;
   - **nu** se pornește o scanare recursivă a întregului share;
   - săgeata **↑** din antet este dezactivată, iar în lista de foldere nu apare rândul **Up one level**.
6. Intră într-un folder. Folderele apar în stânga, iar în dreapta piesele găsite recursiv, în ordine naturală. Apasă un folder pentru a naviga. Când nu ești la rădăcină, folosește butonul **↑** din antet pentru a urca; în lista de foldere nu există un rând separat **Up one level**. Butonul **▶ Play all** pornește lista afișată.
7. Apasă **Search**:
   - în folder, lista se filtrează instant după titlu, artist, album și numele fișierului;
   - butonul de scop comută între **Folder: …** și **Whole share**;
   - la prima căutare în **Whole share** apare **Indexing the share…** și numărul de piese găsite; după indexare, căutarea potrivește numele fișierului și calea folderului;
   - căutarea din **Favorites** folosește automat întregul share;
   - **Clear** readuce lista completă.
8. Alege o piesă. Verifică noul layout:
   - coperta este **fixă în centru**, fără efect de glow în jurul ei;
   - sub copertă apar **titlul, artistul și albumul**;
   - bara de progres și timpul sunt sub text;
   - butoanele **|◀ / Play-Pause / ▶|** sunt sub progres;
   - sub ele apare **NEXT UP** pe o singură linie, cu piesa următoare și durata;
   - dedesubt este rândul **Shuffle: On/Off** și **Crossfade: 0/3/5/8/10 s**;
   - în partea de jos se animă **spectrul** cu 48 de bare, în ritmul muzicii;
   - cardul **Now playing** apare în panoul din dreapta, sub lista de piese, nu în panoul stâng;
   - inima **♡/♥** de lângă titlu adaugă sau scoate piesa curentă din favorite.
9. Verifică **Favorites**:
   - apasă inima de pe piesa curentă; contorul de lângă **♥ Favorites** din panoul stâng crește;
   - apasă inima de pe un rând din listă; rândul se marchează;
   - intră în **♥ Favorites**; piesele salvate apar în listă;
   - scoate o piesă din favorite și verifică faptul că dispare din listă;
   - închide complet aplicația și redeschide-o; favoritele trebuie să rămână.
10. Ține apăsat **OK** pe un rând. Meniul trebuie să conțină exact:
    - **Play now**
    - **Add to favorites** sau **Remove from favorites**
    - **Details**
    Dialogul **Details** trebuie să arate titlul, artistul, albumul, durata, formatul și calea fișierului. Nu există tab **Queue** și nici **Recently played**.
11. Verifică redarea: Play/Pause, Previous/Next, seek, **Shuffle On/Off** și **Crossfade** la **0 / 3 / 5 / 8 / 10 s**. **NEXT UP** trebuie să urmeze ordinea reală, inclusiv când Shuffle este pornit; pentru o coadă de o singură piesă trebuie să apară **NEXT UP · —**.
12. Lasă playerul fără comenzi timp de 45 de secunde (sau valoarea aleasă în Settings). Trebuie să apară ecranul ambient cu:
    - coperta mare, titlul, artistul și albumul;
    - bara de progres și timpul;
    - butoanele **|◀ / Play-Pause / ▶|** și **♪ List**;
    - **NEXT UP**;
    - spectrul lat, cu 64 de bare și marginile estompate, fără glow în jurul copertei.
    Butoanele trebuie să funcționeze din ecranul ambient. **♪ List** readuce folderul din care cântă și evidențiază piesa curentă. Orice tastă revine la ecranul principal; **Back** nu trebuie să închidă aplicația.
13. Deschide **⚙ Settings**:
    - **Theme: Dark / Light / Auto** — Auto folosește tema întunecată între 19:00 și 07:00;
    - **Ambient screen: 30/45/60/90 s / Never**;
    - **Sleep timer: 15/30/60/90 min** — la expirare redarea se oprește;
    - **Spectrum: On/Off**;
    - **Audio sync: On — real levels / Off — rhythmic effect** (implicit **On**);
    - **Cover cache: … • Clear** — arată spațiul folosit și golește coperțile salvate;
    - **Memory: … MB used of … MB** — memoria Java folosită; `largeHeap` este activat;
    - **Last crash: view** — apare doar dacă aplicația a înregistrat o excepție Java; arată data, dispozitivul, memoria și stack trace-ul, cu butonul **Clear**;
    - acțiunile pentru share: **Change login**, **Forget saved password**, **Remove share**.
    La deschidere, focusul trebuie să fie pe **Theme**, în partea de sus.
14. **Audio sync** este pornit implicit. La prima pornire, Android cere o singură dată permisiunea pentru microfon; acceptă **While using the app**. Permisiunea este folosită doar pentru nivelurile audio afișate în spectru; nimic nu este înregistrat sau trimis. Dacă refuzi, spectrul rămâne pe efectul ritmic, setarea apare **Off — rhythmic effect** și nu mai apare promptul automat; o poți reactiva din Settings. Dacă TV-ul nu oferă date audio, spectrul revine automat la efectul ritmic după 1,2 s.
15. Deschide un folder mare, cu **10.000 sau mai multe piese**. Lista trebuie să continue peste 4.000 de fișiere; limita acestei versiuni este **250.000 de piese / 100.000 de foldere**. La deschidere, folderele și piesele imediate trebuie să apară **imediat**, apoi piesele găsite recursiv se adaugă progresiv, fără să blocheze lista de foldere. În timpul scanării, bara de stare arată câte foldere și piese au fost găsite. Dacă deschizi alt folder între timp, scanarea veche este anulată. Revenirea într-un folder deschis recent trebuie să fie aproape instantă (ultimele două foldere sunt păstrate în cache, cu o limită totală de 24.000 de piese; un folder uriaș nu este ținut în memorie). Indexul de metadate ține în memorie un singur folder, cu maximum 8.000 de intrări, dar rămâne pe disc pentru redeschidere. Dacă apare mesajul **partial**, folderul a depășit limita sau unele subfoldere nu au putut fi citite.
    - **Coperți și metadate**: parcurge un folder cu multe piese cu copertă încorporată. Toate rândurile vizibile trebuie să primească treptat copertă, titlu, artist, album și durată. Dacă o copertă lipsește temporar, aplicația reîncearcă; nu trebuie să rămână goală doar pentru că prima citire SMB a eșuat.
    - **Revenire instantă**: ieși din folder cu **↑** din antet și intră din nou în același folder. Rândurile deja încărcate trebuie să apară direct cu copertă, titlu, artist, album și durată, fără o nouă încărcare de la NAS. Aceasta trebuie să funcționeze și după închiderea și redeschiderea aplicației, pentru piesele care au fost deja încărcate o dată.
16. Închide complet aplicația și redeschide-o. Dacă ai bifat **Remember username & password**, aplicația trebuie să se reconecteze singură la share-ul salvat și să afișeze ultimul folder, fără să completezi nimic. Dacă nu ai bifat, apasă cardul share-ului și introdu din nou parola. Butonul **Forget saved** șterge credențialele salvate.
17. Adaugă un folder/fișier pe server, apoi apasă **Refresh** în folderul curent. Refresh golește cache-ul de foldere, invalidează indexul de căutare globală și recitește folderul; navigarea/Refresh nu înlocuiește lista care cântă.
18. Cât timp o piesă cântă, intră în alt folder, apoi pornește o piesă din folderul nou. Aplicația nu trebuie să se închidă și redarea trebuie să treacă pe piesa nouă. Repetă schimbarea între două foldere de 3–4 ori, cu piese pornite din fiecare. În timpul deschiderii unui folder, lista veche de piese trebuie să rămână vizibilă până când folderul nou este citit; dacă folderul nou are doar subfoldere, lista veche rămâne cu **LOADING…** până apar piesele recursive. Apoi apasă cardul **Now playing** din panoul dreapta sau **♪ List** din ecranul ambient: aplicația trebuie să revină în folderul din care cântă și să evidențieze/centreze piesa curentă.
    - Dacă aplicația totuși se închide, redeschide-o și intră în **⚙ Settings**: dacă apare **Last crash: view**, apasă-l și trimite textul. Dacă nu apare, crash-ul este nativ sau procesul a fost oprit de sistem din lipsă de memorie; în acest caz este nevoie de un `logcat` în momentul schimbării folderului.
19. Verifică **focusul și butoanele** cu telecomanda:
    - intră într-un folder mare, apoi apasă **↑** din antet. Focusul trebuie să rămână în **lista de foldere**, pe folderul din care ai urcat (nu trebuie să sară la lista de piese sau la butoanele de redare);
    - apasă **Refresh** de două ori la rând. Butonul trebuie să rămână activ și **evidențiat cu contur albastru** după fiecare apăsare;
    - schimbă tema de două ori la rând din Settings. Tema trebuie să se schimbe de fiecare dată, iar focusul și conturul albastru să rămână pe rândul de setări;
    - apasă de mai multe ori orice buton (foldere, Play/Pause, Next, Shuffle, Crossfade, inima, butoanele din ecranul ambient). Butoanele nu trebuie să devină inactive după prima apăsare și conturul albastru nu trebuie să dispară;
    - din lista de piese, apasă **←**; focusul trebuie să treacă în lista de foldere.
20. Verifică **sincronizarea spectrului** pe o piesă cu ritm clar (bass/kick): barele din stânga trebuie să pulseze pe fiecare bătaie, iar cele înalte să urmeze hi-hat-ul/percusia, nu să rămână într-o rampă fixă. În timpul pauzei, barele trebuie să coboare la linia de mijloc. Dacă spectrul e plat sau nu urmează muzica, notează în raport dacă permisiunea microfonului a fost acceptată și dacă setarea arată **Audio sync: On — real levels**.
21. **Artists / Albums / Genres** nu sunt incluse în 0.6.8.2; această navigare după metadate este planificată pentru 0.7.

## Dacă nu se conectează

- Verifică adresa: `smb://server/share` (nu `\\server\share`).
- Verifică utilizatorul/parola și permisiunea de citire pe share.
- Preferabil activează SMB 2/3 pe server. Dacă serverul este vechi și oferă doar SMB1, aplicația încearcă automat modul de compatibilitate; modul folosit apare sub lista de foldere.
- TV-ul și serverul trebuie să fie în aceeași rețea locală.
- Dacă listarea share-urilor este restricționată, conectează-te direct la share-ul complet (`smb://server/music`).
- Apasă **Details** în mesajul de eroare: arată lanțul exact de excepții pentru fiecare mod SMB încercat.

Nu deschide porturi pe router. Aplicația inițiază conexiunea către server pe TCP 445, iar fallback-ul SMB1 poate folosi TCP 139; nu pornește server TCP și nu cere conexiuni din internet. Nu există descoperire automată în această versiune. Parola rămâne doar în memorie dacă nu bifezi salvarea.

Aplicația cere permisiunea pentru microfon o singură dată, la prima pornire, pentru **Audio sync** (pornit implicit). Dacă refuzi, spectrul folosește efectul ritmic și nu mai apare promptul automat; poți reactiva opțiunea din Settings. Credențialele salvate sunt criptate cu o cheie AES-GCM păstrată de Android Keystore pe TV; **Forget saved** le șterge.

Această versiune a fost compilată și verificată cu teste unitare și în emulator, dar autentificarea reală, redarea de pe NAS, spectrul audio real și telecomanda TCL necesită test pe TV. Nu este promisă compatibilitatea cu toate codecurile, coperțile sau setările de economisire ale TV-ului.
