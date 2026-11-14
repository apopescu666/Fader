# Fader Mobil — direcție vizuală

Mockup-uri statice (HTML/CSS randate cu Chrome). Sursa designului, nu cod de
aplicație. Se regenerează cu `bash shots.sh` (sau manual, vezi mai jos).

## Fișiere

| Fișier | Ecran |
|---|---|
| `library.html` | Bibliotecă — surse, căutare, redate recent, playlist-uri |
| `songs.html` | Listă de melodii (folder de 10.284 piste) |
| `player.html` | Acum se redă |
| `settings.html` | Setări, cu accesibilitatea în prim-plan |
| `fader-mockups.png` | Planșa cu toate patru + etichete |

## Reguli

- **iOS ca limbaj**: titluri mari (34 pt), carduri grupate, separatoare inset,
  bare translucide, tab bar jos, Dynamic Island.
- **O singură culoare de accent**: teal `#4FD1C5` (identitatea Fader). Restul
  e alb/negru/gri — nimic decorativ care să concureze cu conținutul.
- **Ținte de atingere ≥ 44 pt**, rânduri de listă 56–64 pt.
- **Contrast**: text secundar minim `rgba(235,235,245,0.58)`; nu există text
  informativ sub 0.46.
- **Fără informație doar prin culoare**: starea „se redă" are iconiță + text,
  conectarea are punct + etichetă „Conectat".
- **Text scalabil**: interfața e pe un singur nivel de coloană, fără lățimi
  fixe pe etichete, deci merge până la 200% fără trunchiere.

## Paletă

| Rol | Valoare |
|---|---|
| Fundal | `#0A0A0C` |
| Card / suprafață | `#1C1C1E` |
| Suprafață secundară | `#2C2C2E` |
| Text principal | `#FFFFFF` |
| Text secundar | `rgba(235,235,245,.62)` |
| Accent | `#4FD1C5` |
| Accent pe accent | `#062321` |

## Regenerare

```bash
cd design
./shots.sh        # 4 capturi, 2x
python3 compose.py  # planșa cu etichete
```
