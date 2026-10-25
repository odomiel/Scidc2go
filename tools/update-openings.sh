#!/bin/bash
# Holt die Eröffnungstabelle von Lichess (https://github.com/lichess-org/chess-openings, CC0) und legt sie als
# app/src/main/assets/openings.tsv ab (Spalten eco, name, pgn; eine Kopfzeile).
set -euo pipefail
cd "$(dirname "$0")/.."
URL=https://raw.githubusercontent.com/lichess-org/chess-openings/master
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
for x in a b c d e; do curl -sfL "$URL/$x.tsv" -o "$TMP/$x.tsv"; done
mkdir -p app/src/main/assets
{ head -1 "$TMP/a.tsv"; for x in a b c d e; do tail -n +2 "$TMP/$x.tsv"; done; } > app/src/main/assets/openings.tsv
echo "$(($(wc -l < app/src/main/assets/openings.tsv) - 1)) Eröffnungen in app/src/main/assets/openings.tsv"
