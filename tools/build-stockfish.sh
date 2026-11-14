#!/bin/sh
# Baut Stockfish (GPLv3) mit dem Android-NDK fuer alle unterstuetzten ABIs
# und legt die Binaries als libstockfish.so in app/src/main/jniLibs ab.
# Das NNUE-Netz wird nicht eingebettet; die App lädt es beim ersten Start der Engine.
# Name, Größe und SHA-256 des Netzes werden in StockfishNet.kt eingetragen.
#
# Aufruf: tools/build-stockfish.sh [Stockfish-Quellverzeichnis]
set -e

SF_TAG=sf_19
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK=${ANDROID_NDK_HOME:-$(ls -d "$HOME"/Android/Sdk/ndk/* | sort -V | tail -1)}
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
SRC=${1:-"$ROOT/build/stockfish-src"}

if [ ! -d "$SRC/src" ]; then
    git clone --depth 1 --branch "$SF_TAG" https://github.com/official-stockfish/Stockfish.git "$SRC"
fi

export PATH="$TOOLCHAIN:$PATH"
cd "$SRC/src"
make net

for pair in armv8:arm64-v8a armv7:armeabi-v7a x86-64:x86_64; do
    arch=${pair%%:*}
    abi=${pair##*:}
    make clean >/dev/null
    make -j"$(nproc)" build ARCH="$arch" COMP=ndk EXTRACXXFLAGS="-DNNUE_EMBEDDING_OFF"
    llvm-strip stockfish
    mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
    cp stockfish "$ROOT/app/src/main/jniLibs/$abi/libstockfish.so"
done

NET=$(grep '#define EvalFileDefaultName' evaluate.h | sed 's/.*"\(.*\)".*/\1/')
SIZE=$(stat -c %s "$NET")
SHA=$(sha256sum "$NET" | cut -d' ' -f1)
# Der Name des Netzes enthält den Anfang seiner Prüfsumme
case "$NET" in nn-"$(echo "$SHA" | cut -c1-12)".nnue) ;; *) echo "Prüfsumme passt nicht zu $NET" >&2; exit 1 ;; esac
KT="$ROOT/app/src/main/java/org/scidc2go/engine/StockfishNet.kt"
sed -i -e "s/const val NAME = \".*\"/const val NAME = \"$NET\"/" \
    -e "s/const val SIZE = .*L/const val SIZE = ${SIZE}L/" \
    -e "s/const val SHA256 = \".*\"/const val SHA256 = \"$SHA\"/" "$KT"
echo "Fertig: Netz $NET ($SIZE Bytes, SHA-256 $SHA) in StockfishNet.kt eingetragen, Binaries in app/src/main/jniLibs"
