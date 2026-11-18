# Testare Android Auto fără mașină (Desktop Head Unit)

DHU-ul nu se mai instalează comod din SDK Manager (pachetul `extras;google;auto`
poate lipsi din listă). Se poate descărca direct din repository-ul Google:

```bash
mkdir -p tools/dhu && cd tools/dhu
# macOS Apple Silicon:
curl -LO https://dl.google.com/android/repository/desktop-head-unit-darwin-aarch64_r02.1.zip
unzip -o desktop-head-unit-darwin-aarch64_r02.1.zip
xattr -dr com.apple.quarantine .
```

Alternativ din Android Studio: SDK Manager → SDK Tools → „Android Auto Desktop
Head Unit Emulator” (cu „Show Package Details” bifat), apoi fișierul apare în
`$ANDROID_SDK/extras/google/auto/`.

## Rulare

1. Pe telefon: Android Auto → Settings → apasă de ~10 ori pe **Version**
   (developer mode) → meniul ⋮ → **Developer settings** → pornește
   **Start head unit server** (sau folosește modul USB, mai jos).
2. Telefon pe USB, deblocat:
   ```bash
   adb devices
   adb forward tcp:5277 tcp:5277
   cd tools/dhu21        # folderul cu binarul
   ./desktop-head-unit
   ```
   Mod USB direct (fără head unit server):
   ```bash
   ./desktop-head-unit --usb=<ID-ul din adb devices>
   ```

## Log pentru diagnostic

```bash
adb logcat -c
# reproduce bug-ul în DHU
adb logcat -d -v time > fader-log.txt
adb logcat -d -v time | grep -iE "fader|MediaSession|MediaLibrary" > fader-log-scurt.txt
```

Fișierul `fader-log*.txt` e ignorat de git (nu se publică).
