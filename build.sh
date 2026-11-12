#!/usr/bin/env bash
# Shared offline APK build for every game on the cards framework: no Gradle, no downloads.
# Needs JDK 17+ and the Android SDK (platform android-35, build-tools 35.0.0).
#
# Run it from a game folder (the game's own build.sh does `exec framework/build.sh "$@"`).
# The game folder holds game.conf:
#   GAME=scopa          # id: Java package app.$GAME, build/$GAME.apk
#   KS_NAME=Scopa       # optional: CN of a key made by "newkey"
#
# Signing keys never live in a repository. A game's key is $CARDS_KEYS/$GAME.jks (PKCS12, alias
# $GAME) and its password is the first line of $CARDS_KEYS/$GAME.pass. CARDS_KEYS defaults to
# ~/claude/.keys/cards. Keep both private (chmod 600) and backed up: Android only installs an
# update that is signed with the same key, so the build never makes a new key by itself.
#
#   ./build.sh            -> build/$GAME.apk, signed with the game's key
#   ./build.sh newkey     -> create the key for a brand-new game (never overwrites one)
#   ./build.sh desktop    -> run the server on this computer for testing (port 8080)
#   ./build.sh test       -> run rule tests + bot simulations
#   ./build.sh relaytest  -> test the Bluetooth relay over TCP ("relaytest serve" keeps it running)
set -euo pipefail

FW="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f game.conf ] || { echo "run this from a game folder (no game.conf in $PWD)"; exit 1; }
# shellcheck disable=SC1091
. ./game.conf
: "${GAME:?game.conf must set GAME}"
KS_NAME="${KS_NAME:-$GAME}"

KEYS="${CARDS_KEYS:-$HOME/claude/.keys/cards}"
KS="$KEYS/$GAME.jks"
KS_PASS_FILE="$KEYS/$GAME.pass"   # passwords are passed as file names, never on a command line

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"

# Pick a JDK >= 17 (Arch: /usr/lib/jvm/java-17-openjdk).
if [ -z "${JAVA_HOME:-}" ]; then
  for j in /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-2*-openjdk; do
    [ -x "$j/bin/javac" ] && JAVA_HOME="$j" && break
  done
fi
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ] || { echo "JDK 17+ not found (install jdk17-openjdk)"; exit 1; }
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

OUT=build
# Framework sources first, then the game's own.
CORE=("$FW/core" app/core)
ANDROID_SRC=("$FW/android" app/src)
DESKTOP=("$FW/desktop" desktop)

desktop_classes() {
  rm -rf "$OUT/desktop" && mkdir -p "$OUT/desktop"
  javac -encoding UTF-8 --release 8 -nowarn -d "$OUT/desktop" $(find "${CORE[@]}" "${DESKTOP[@]}" -name '*.java')
}

check_key() {
  if [ ! -f "$KS" ] || [ ! -f "$KS_PASS_FILE" ]; then
    echo "no signing key: need $KS and $KS_PASS_FILE (or set CARDS_KEYS)." >&2
    echo "An APK signed with another key cannot update the installed app, so none is made here." >&2
    echo "Only for a brand-new game: ./build.sh newkey" >&2
    exit 1
  fi
  local f
  for f in "$KEYS" "$KS" "$KS_PASS_FILE"; do
    case "$(stat -c %a "$f" 2>/dev/null || echo 600)" in
      *00) ;;
      *) echo "warning: $f can be read by other users (chmod go-rwx)" >&2 ;;
    esac
  done
}

case "${1:-apk}" in
  desktop)
    desktop_classes
    exec java -cp "$OUT/desktop" "app.$GAME.desktop.DesktopMain" app/assets/web "${2:-8080}" skins
    ;;
  test)
    desktop_classes
    exec java -cp "$OUT/desktop" "app.$GAME.desktop.SimTest"
    ;;
  relaytest)
    desktop_classes
    exec java -Djava.net.preferIPv6Addresses=true -cp "$OUT/desktop" "app.$GAME.desktop.RelayTest" "${2:-}"
    ;;
  newkey)
    [ -e "$KS" ] && { echo "$KS already exists; not touching it" >&2; exit 1; }
    umask 077
    mkdir -p "$KEYS"
    [ -s "$KS_PASS_FILE" ] || { head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'; echo; } > "$KS_PASS_FILE"
    keytool -genkeypair -keystore "$KS" -storetype PKCS12 -alias "$GAME" \
      -storepass:file "$KS_PASS_FILE" -keypass:file "$KS_PASS_FILE" \
      -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=$KS_NAME, O=Local" >/dev/null
    echo "created $KS (password in $KS_PASS_FILE). Back up both, and never commit them."
    exit 0
    ;;
  apk) check_key ;;
  *) echo "usage: ./build.sh [apk|desktop|test|relaytest|newkey]"; exit 1 ;;
esac

rm -rf "$OUT/apk" && mkdir -p "$OUT/apk/res" "$OUT/apk/gen" "$OUT/apk/classes" "$OUT/apk/dex"

echo "== assets (web client + card skins)"
cp -r app/assets "$OUT/apk/assets"
for skin in skins/*/; do
  name=$(basename "$skin")
  mkdir -p "$OUT/apk/assets/web/skins/$name"
  cp "$skin"*.png "$skin"*.svg "$OUT/apk/assets/web/skins/$name/" 2>/dev/null || true
done

echo "== aapt2 compile/link"
"$BT/aapt2" compile --dir app/res -o "$OUT/apk/res/res.zip"
"$BT/aapt2" link -o "$OUT/apk/unsigned.apk" \
  -I "$PLATFORM" \
  --manifest app/AndroidManifest.xml \
  --min-sdk-version 24 --target-sdk-version 35 \
  --java "$OUT/apk/gen" \
  -A "$OUT/apk/assets" \
  "$OUT/apk/res/res.zip"

echo "== javac"
javac -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:-options \
  -bootclasspath "$PLATFORM" -classpath "$PLATFORM" \
  -d "$OUT/apk/classes" \
  $(find "${ANDROID_SRC[@]}" "${CORE[@]}" "$OUT/apk/gen" -name '*.java')

echo "== d8"
"$BT/d8" --release --min-api 24 --lib "$PLATFORM" --output "$OUT/apk/dex" \
  $(find "$OUT/apk/classes" -name '*.class')

echo "== package"
cp "$OUT/apk/unsigned.apk" "$OUT/apk/with-dex.apk"
(cd "$OUT/apk/dex" && zip -q -j ../with-dex.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/apk/with-dex.apk" "$OUT/apk/aligned.apk"

echo "== sign with $KS"
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$GAME" --ks-pass "file:$KS_PASS_FILE" \
  --out "$OUT/$GAME.apk" "$OUT/apk/aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT/$GAME.apk" | grep 'SHA-256'
echo "== done: $OUT/$GAME.apk ($(du -h "$OUT/$GAME.apk" | cut -f1))"
