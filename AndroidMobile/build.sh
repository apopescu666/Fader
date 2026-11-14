#!/usr/bin/env bash
# Build pentru "Fader Android Mobil".
#
# De ce exista acest script: in sandbox-ul DSH scrierea in ~/.gradle si
# ~/.android este blocata, asa ca Gradle si Android si tin datele in
# proiect (.gradle-home / .android-user). In afara sandbox-ului merge
# si ./gradlew direct.
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "Nu gasesc un JDK valid. Seteaza JAVA_HOME." >&2
  exit 1
fi

export JAVA_HOME
export GRADLE_USER_HOME="$PWD/.gradle-home"
export ANDROID_USER_HOME="$PWD/.android-user"

exec ./gradlew "$@"
