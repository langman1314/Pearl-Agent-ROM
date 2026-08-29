#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$ROOT/.tools"
JDK_ARCHIVE="$TOOLS/jdk17.zip"
SDK_ARCHIVE="$TOOLS/commandlinetools-win-13114758_latest.zip"
SDK_ROOT="$TOOLS/android-sdk"

mkdir -p "$TOOLS" "$SDK_ROOT/cmdline-tools"

if ! find "$TOOLS/jdk-17" -type f -path '*/bin/java.exe' -print -quit 2>/dev/null | grep -q .; then
  echo '[bootstrap] Downloading Eclipse Temurin JDK 17...'
  curl --fail --location --retry 3 --ssl-no-revoke \
    'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk' \
    --output "$JDK_ARCHIVE"
  rm -rf "$TOOLS/jdk-17"
  mkdir -p "$TOOLS/jdk-17"
  unzip -q "$JDK_ARCHIVE" -d "$TOOLS/jdk-17"
fi

JAVA_EXE="$(find "$TOOLS/jdk-17" -type f -path '*/bin/java.exe' -print -quit)"
if [[ -z "$JAVA_EXE" ]]; then
  echo '[bootstrap] JDK extraction did not produce java.exe' >&2
  exit 1
fi
export JAVA_HOME="$(cygpath -w "$(dirname "$(dirname "$JAVA_EXE")")")"
export PATH="$(cygpath -u "$JAVA_HOME")/bin:$PATH"

if [[ ! -f "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager.bat" ]]; then
  echo '[bootstrap] Downloading Android command-line tools 13114758...'
  curl --fail --location --retry 3 --ssl-no-revoke \
    'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' \
    --output "$SDK_ARCHIVE"
  rm -rf "$TOOLS/cmdline-tools-unpack" "$SDK_ROOT/cmdline-tools/latest"
  mkdir -p "$TOOLS/cmdline-tools-unpack" "$SDK_ROOT/cmdline-tools/latest"
  unzip -q "$SDK_ARCHIVE" -d "$TOOLS/cmdline-tools-unpack"
  cp -a "$TOOLS/cmdline-tools-unpack/cmdline-tools/." "$SDK_ROOT/cmdline-tools/latest/"
fi

powershell.exe -NoProfile -ExecutionPolicy Bypass \
  -File "$(cygpath -w "$ROOT/scripts/build-nexus.ps1")"

echo '[bootstrap] Build completed.'
find app/build/outputs/apk/debug -maxdepth 1 -type f -name '*.apk' -print
