# Installer packaging

From the project root, build the app with `bash build.sh`, then run:

```sh
bash Packaging/build-dmg.sh /path/to/output-directory
```

The script packages only Fader.app, an Applications link, installation instructions, and the license. It neither reads user libraries nor installs or launches the application.
