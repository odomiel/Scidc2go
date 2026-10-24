#!/bin/sh
# Baut eine aktuelle APK von Scidc2go und legt sie in dist/ ab: dist/scidc2go-<Version>.apk, wenn der Stand
# genau dem Release-Tag v<Version> entspricht, sonst dist/scidc2go-<Version>-<Commit>[-geaendert].apk.
#
# Aufruf: tools/build-apk.sh [--debug] [--no-tests]
#   --debug     Debug-APK statt signierter Release-APK
#   --no-tests  Unit-Tests überspringen
set -e

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

TYPE=release
TESTS=yes
for arg in "$@"; do
    case "$arg" in
        --debug) TYPE=debug ;;
        --no-tests) TESTS=no ;;
        *) echo "Unbekannte Option: $arg" >&2; exit 2 ;;
    esac
done

# Java: JAVA_HOME, sonst java im PATH, sonst ein JDK 17+ unter ~/opt
if [ -z "$JAVA_HOME" ] && ! command -v java >/dev/null 2>&1; then
    JAVA_HOME=$(ls -d "$HOME"/opt/jdk-* 2>/dev/null | sort -V | tail -1)
    [ -n "$JAVA_HOME" ] || { echo "Kein JDK gefunden: JAVA_HOME setzen" >&2; exit 1; }
    export JAVA_HOME
fi

if [ ! -f app/src/main/jniLibs/arm64-v8a/libstockfish.so ]; then
    echo "Stockfish fehlt: zuerst tools/build-stockfish.sh ausführen" >&2
    exit 1
fi
if [ "$TYPE" = release ] && [ ! -f keystore.properties ]; then
    echo "keystore.properties fehlt: Release-APK wäre unsigniert (oder --debug verwenden)" >&2
    exit 1
fi

VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
if [ "$TYPE" = release ]; then TASK=assembleRelease; else TASK=assembleDebug; fi
if [ "$TESTS" = yes ]; then
    ./gradlew "$TASK" testDebugUnitTest
else
    ./gradlew "$TASK"
fi

# Nur ein unveränderter Stand des Release-Tags heißt wie das Release; sonst kommt der Commit dazu,
# damit keine veröffentlichte APK überschrieben wird.
NAME="scidc2go-$VERSION"
if [ "$(git rev-parse -q --verify "refs/tags/v$VERSION^{commit}")" != "$(git rev-parse HEAD)" ] ||
    [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    NAME="$NAME-$(git rev-parse --short HEAD)"
    [ -n "$(git status --porcelain --untracked-files=no)" ] && NAME="$NAME-geaendert"
fi
[ "$TYPE" = debug ] && NAME="$NAME-debug"
SRC="app/build/outputs/apk/$TYPE/app-$TYPE.apk"
OUT="dist/$NAME.apk"
mkdir -p dist
cp "$SRC" "$OUT"
echo "Fertig: $OUT ($(du -h "$OUT" | cut -f1), Version $VERSION)"
