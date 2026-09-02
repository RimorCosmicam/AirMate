#!/bin/sh
# Build the AirMate host server into a dex jar that `app_process` can run.
#
# Not a Gradle build: the output is not an app. It is a bare dex with no manifest, no resources and
# no Android package around it, launched by the shell the way scrcpy's server is, because the one
# thing it must do — create a trusted display — is permitted to uid 2000 and to nobody else.
set -e

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
PLATFORM="$SDK/platforms/android-35/android.jar"
D8="$(ls "$SDK"/build-tools/*/d8 | sort -V | tail -1)"
OUT="build"

[ -f "$PLATFORM" ] || { echo "android.jar not found at $PLATFORM" >&2; exit 1; }
[ -x "$D8" ] || { echo "d8 not found under $SDK/build-tools" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "javac ($(basename "$(dirname "$D8")"))"
# android.jar on the classpath rather than as the bootclasspath: lambdas need the JDK's own
# LambdaMetafactory to compile, and d8 desugars them for the runtime that has none.
javac -source 8 -target 8 -nowarn \
      -classpath "$PLATFORM" \
      -d "$OUT/classes" \
      $(find src -name '*.java')

echo "d8"
"$D8" --min-api 30 --lib "$PLATFORM" --output "$OUT/dex" \
      $(find "$OUT/classes" -name '*.class')

echo "jar"
(cd "$OUT/dex" && zip -q ../../airmate-server.jar classes.dex)
ls -la airmate-server.jar
