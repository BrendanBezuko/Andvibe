#!/usr/bin/env bash
# Build AndVibe on Termux (aarch64).
#
# Installs OpenJDK 17 + Termux aapt2, bootstraps a local Android SDK, then runs
# ./gradlew :app:assembleDebug. Idempotent — safe to re-run.
#
# Usage (from the repo root, inside Termux):
#   chmod +x termux_build.sh
#   ./termux_build.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

if [[ -z "${PREFIX:-}" || ! -d "$PREFIX" ]]; then
  echo "Run this inside Termux (PREFIX is unset)." >&2
  exit 1
fi

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
ANDROID_SDK_ROOT="$ANDROID_HOME"
CMDLINE_ZIP_URL="https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip"
export ANDROID_HOME ANDROID_SDK_ROOT
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "==> Termux packages"
pkg update -y
pkg install -y openjdk-17 wget unzip which aapt aapt2

if ! command -v java >/dev/null; then
  echo "java not found after pkg install openjdk-17" >&2
  exit 1
fi
if [[ ! -x "$PREFIX/bin/aapt2" ]]; then
  echo "Termux aapt2 missing at $PREFIX/bin/aapt2" >&2
  exit 1
fi

echo "==> Android SDK at $ANDROID_HOME"
mkdir -p "$ANDROID_HOME/cmdline-tools"
if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/andvibe-sdk.XXXXXX")"
  trap 'rm -rf "$tmp"' EXIT
  wget -q -O "$tmp/cmdline-tools.zip" "$CMDLINE_ZIP_URL"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp/extract"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/extract/cmdline-tools"/* "$ANDROID_HOME/cmdline-tools/latest/"
  rm -rf "$tmp"
  trap - EXIT
fi

mkdir -p "$HOME/.android"
touch "$HOME/.android/repositories.cfg"

echo "==> SDK packages + licenses"
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true
sdkmanager --sdk_root="$ANDROID_HOME" --install \
  "platform-tools" \
  "platforms;android-37" \
  "platforms;android-36" \
  "build-tools;37.0.0" \
  "build-tools;36.0.0"

echo "==> local.properties"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$ROOT/local.properties"

echo "==> Termux aapt2 override"
mkdir -p "$HOME/.gradle"
props="$HOME/.gradle/gradle.properties"
override="android.aapt2FromMavenOverride=$PREFIX/bin/aapt2"
if [[ -f "$props" ]] && grep -q '^android.aapt2FromMavenOverride=' "$props"; then
  # Keep an existing override; rewrite if the path drifted.
  if ! grep -qxF "$override" "$props"; then
    grep -v '^android.aapt2FromMavenOverride=' "$props" > "$props.tmp" || true
    mv "$props.tmp" "$props"
    echo "$override" >> "$props"
  fi
else
  echo "$override" >> "$props"
fi

# Persist env for interactive Termux sessions (once).
bashrc="$HOME/.bashrc"
marker="# andvibe-termux-sdk"
if [[ -f "$bashrc" ]] && grep -qxF "$marker" "$bashrc"; then
  :
else
  {
    echo ""
    echo "$marker"
    echo "export ANDROID_HOME=\"$ANDROID_HOME\""
    echo "export ANDROID_SDK_ROOT=\"\$ANDROID_HOME\""
    echo "export PATH=\"\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH\""
  } >> "$bashrc"
fi

echo "==> gradlew :app:assembleDebug"
chmod +x "$ROOT/gradlew"
./gradlew :app:assembleDebug

apk="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
echo "Done: $apk"
ls -lh "$apk"
