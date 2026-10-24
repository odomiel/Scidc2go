# Scidc2go

Schachdatenbanken unterwegs: eine schlanke Android-App für Smartphone und Tablet.

- **Lesen:** Scid 3 (`.si3`), Scid 4 (`.si4`), Scid 5 (`.si5`), Scidb (`.sci`, Dateiversion 94), PGN
- **Schreiben:** Scid 5. Partien ersetzen, neu anhängen, zum Löschen markieren, neue Datenbanken anlegen
- **Analyse:** Stockfish 19 ist eingebaut (NNUE, 1–5 Varianten). Das Netz (98 MB) lädt die App beim ersten
  Einschalten der Analyse einmalig von stockfishchess.org; nur dafür braucht sie die Internet-Berechtigung.
- **Ansichten:** Datenbankliste mit Suche und Brettansicht mit Zugliste, Varianten, Kommentaren und Bewertungszeichen.
  Auf Tablets (ab 840 dp Breite) stehen Partieliste und Brett nebeneinander.

Freie Software unter der GNU GPL Version 3 oder später (siehe `COPYING`), ohne proprietäre Bestandteile.

## Bedienung

1. **Ordner wählen**: den Ordner mit den Datenbanken freigeben (Storage Access Framework).
   Scid-Datenbanken bestehen aus drei Dateien, deshalb wird der ganze Ordner freigegeben.
   Alternativ öffnet **Dateien öffnen** einzelne Dateien, etwa aus der Nextcloud-App. Bei Scid und Scidb genügt
   eine Datei der Datenbank; die App fragt dann nach dem Ordner, sucht die übrigen Dateien dort und übernimmt
   den Ordner in die Liste, in der jede Datenbank als ein Eintrag erscheint.
2. Datenbank antippen, um die Partieliste zu sehen. Das Suchfeld durchsucht Spieler, Turnier, Ort, Datum, ECO und Runde.
   Langes Drücken auf eine Partie setzt oder entfernt die Löschmarkierung (nur Scid 5).
3. Partie antippen, um die Brettansicht zu öffnen.
   - Züge eingeben: Figur antippen, dann das Zielfeld antippen. Weicht der Zug von der Partie ab, entsteht eine Variante.
   - Ein Zug in der Zugliste springt zu dieser Stellung.
   - Schalter **Analyse** startet Stockfish. Ein Antippen einer Engine-Variante spielt deren ersten Zug.
   - Menü: Partiedaten, Kommentar, Variante hochstufen oder löschen, PGN/FEN kopieren, PGN einfügen.
   - **Speichern**: in die geöffnete Scid-5-Datenbank (ersetzen oder neu) oder in eine andere bzw. neue Scid-5-Datenbank im Ordner.

## Bauen

Benötigt: JDK 17, Android SDK (Plattform 35) und für Stockfish das Android NDK (r27c oder neuer).

```sh
tools/build-stockfish.sh        # Stockfish für arm64-v8a, armeabi-v7a, x86_64; trägt das NNUE-Netz in StockfishNet.kt ein
./gradlew assembleRelease       # APK: app/build/outputs/apk/release/
./gradlew testDebugUnitTest     # Unit-Tests
```

Für signierte Release-Builds legt man eine `keystore.properties` an. Sie gehört nicht ins Repository:

```
storeFile=/pfad/zum/keystore.jks
storePassword=…
keyAlias=…
keyPassword=…
```

`tools/svg2vector.py` wandelt die Figuren-SVGs in Android-Vektorgrafiken um.

## Kompatibilität und Tests

- Zuggenerator: Perft-Tests mit Standardstellungen.
- Scid 4/5 lesen: Datenbanken, die mit Scid 5.2 erzeugt wurden, liefern Partie für Partie dieselben Züge,
  Varianten, Kommentare und Tags wie Scids eigener PGN-Export (`app/src/test/resources`).
- Scid 5 schreiben: über 300 Zufallspartien mit Varianten, Kommentaren, NAGs, Umwandlungen,
  FEN-Startstellungen und Nullzügen. Scid 5.2 liest sie identisch zurück.
- Scidb: Der Leser folgt dem Scidb-Quelltext. Getestet ist er nur mit von Hand nach Scidbs Encoder-Regeln
  erzeugten Daten, nicht mit echten Scidb-Dateien. Ältere Scidb-Dateiversionen (91–93) und
  Spielvarianten (Crazyhouse, Antichess usw.) werden nicht unterstützt.

## Enthaltene freie Komponenten

| Komponente | Lizenz |
|---|---|
| Stockfish 19 (stockfishchess.org) | GPLv3 |
| Schachfiguren „cburnett“ von Colin M.L. Burnett | GPLv2+ |
| Datenbankformate nach den Quelltexten von Scid und Scidb | GPL |
| AndroidX, Jetpack Compose, Kotlin | Apache 2.0 |
