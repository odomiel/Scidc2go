# Scidc2go

*[English version](README.en.md)*

Schachdatenbanken unterwegs: eine schlanke Android-App für Smartphone und Tablet.

- **Lesen:** Scid 3 (`.si3`), Scid 4 (`.si4`), Scid 5 (`.si5`), Scidb (`.sci`, Dateiversion 94), PGN,
  außerdem gepackt wie in Scidb: Scidb-Archive (`.scv`), PGN mit gzip (`.pgn.gz`) und ZIP-Archive mit PGN-Dateien (`.zip`)
- **Schreiben:** Scid 5. Partien ersetzen, neu anhängen, zum Löschen markieren, neue Datenbanken anlegen
- **Analyse:** Stockfish 19 ist eingebaut (NNUE, 1–5 Varianten). Das Netz (98 MB) lädt die App beim ersten
  Einschalten der Analyse einmalig von stockfishchess.org; nur dafür braucht sie die Internet-Berechtigung.
- **Ansichten:** Datenbankliste mit Suche und Brettansicht mit Zugliste, Varianten, Kommentaren und Bewertungszeichen.
  Auf Tablets (ab 840 dp Breite) stehen Partieliste und Brett nebeneinander.
- **Sprachen:** Deutsch und Englisch, nach der Systemsprache oder in den Einstellungen wählbar.

Freie Software unter der GNU GPL Version 3 oder später (siehe `COPYING`), ohne proprietäre Bestandteile.

## Bedienung

1. **Ordner wählen**: den Ordner mit den Datenbanken freigeben (Storage Access Framework).
   Scid-Datenbanken bestehen aus drei Dateien, deshalb wird der ganze Ordner freigegeben.
   Alternativ öffnet **Dateien öffnen** einzelne Dateien, etwa aus der Nextcloud-App. Bei Scid und Scidb genügt
   eine Datei der Datenbank: Liegt sie in einem schon freigegebenen Ordner (oder einem Unterordner davon), öffnet
   die App die Datenbank direkt. Sonst fragt sie einmalig nach dem Ordner, denn Android erlaubt den Zugriff auf
   die übrigen Dateien nur mit Ordnerfreigabe. Fehlt eine Datei der Datenbank, meldet die App welche.
   Gepackte Datenbanken (`.scv`, `.pgn.gz`, `.zip`) entpackt die App beim Öffnen in ihren Cache und öffnet sie
   schreibgeschützt; beim Schließen wird die entpackte Kopie gelöscht. Ein Scidb-Archiv darf wie in Scidb eine
   Scidb-, Scid-3/4- oder PGN-Datenbank enthalten. Aus einem ZIP-Archiv werden alle
   PGN-Dateien hintereinander gelesen, wie in Scidb. Zum Bearbeiten lassen sie sich in Scid 5 konvertieren.
2. Datenbank antippen, um die Partieliste zu sehen. Das Suchfeld durchsucht Spieler, Turnier, Ort, Datum, ECO und Runde.
   Langes Drücken auf eine Partie öffnet ein Menü: Löschmarkierung setzen oder entfernen, oder die Partie
   endgültig löschen (nur Scid 5). Das Menü (⋮) der Partieliste löscht alle markierten Partien auf einmal.
   Gelöschte Partien verschwinden aus dem Index; ihre Daten bleiben bis zum Komprimieren ungenutzt in der
   `.sg5`-Datei. **Datenbank komprimieren** (ebenfalls im Menü) entfernt die markierten Partien, ungenutzten
   Platz und nicht mehr verwendete Namen. Dabei entsteht zuerst eine vollständige Kopie (`name.neu`); erst
   danach wird das Original ersetzt, so dass bei einem Abbruch immer eine vollständige Datenbank übrig bleibt.
   **In Scid 5 konvertieren** (Menü der Partieliste) schreibt eine Scid-3/4-, Scidb- oder PGN-Datenbank in eine
   neue Scid-5-Datenbank im selben Ordner; das Original bleibt unverändert. Partien, die sich nicht übernehmen
   lassen (defekt oder größer als 128 KB), werden übersprungen und gemeldet.
3. Partie antippen, um die Brettansicht zu öffnen.
   - Züge eingeben: Figur auf das Zielfeld ziehen (oder Figur und Zielfeld nacheinander antippen).
     Weicht der Zug von der Partie ab, entsteht eine Variante.
   - Ein Zug in der Zugliste springt zu dieser Stellung.
   - **Analyse** im Menü (⋮) startet die gewählte Engine. Ihre Varianten stehen über dem Brett (im Querformat
     daneben); ein Antippen einer Variante spielt deren ersten Zug.
   - **Kommentare anzeigen** und **Varianten anzeigen** im Menü blenden Kommentare bzw. Varianten in der Zugliste
     aus (die Einstellung bleibt erhalten). Die Variante mit dem aktuellen Zug bleibt immer sichtbar.
   - Menü: Partiedaten, Kommentar, Variante hochstufen oder löschen, PGN/FEN kopieren, PGN einfügen.
   - **Speichern**: in die geöffnete Scid-5-Datenbank (ersetzen oder neu) oder in eine andere bzw. neue Scid-5-Datenbank im Ordner.

4. **Einstellungen** (Zahnrad): Darstellung hell, dunkel oder wie im System, die Sprache (Deutsch, Englisch oder
   wie im System; ab Android 13, davor gilt die Systemsprache) und die Engine für die Analyse.
   Neben dem eingebauten Stockfish erscheinen dort Engines aus installierten Engine-Apps nach dem
   Open-Exchange-Standard (OEX). Weil Android 10 und neuer Apps keine Programme aus ihrem Datenverzeichnis
   starten lässt, kopiert die App solche Engines und startet sie über den System-Linker (`/system/bin/linker64`).

## Bauen

Benötigt: JDK 17, Android SDK (Plattform 35) und für Stockfish das Android NDK (r27c oder neuer).

```sh
tools/build-stockfish.sh        # Stockfish für arm64-v8a, armeabi-v7a, x86_64; trägt das NNUE-Netz in StockfishNet.kt ein
tools/build-apk.sh              # aktuelle APK bauen (mit Tests) und nach dist/ kopieren; --debug, --no-tests
./gradlew assembleRelease       # APK: app/build/outputs/apk/release/
./gradlew testDebugUnitTest     # Unit-Tests
tools/check-deps.py             # neuere Versionen und bekannte Sicherheitslücken der Abhängigkeiten
```

Vor dem ersten Release eines Tages werden mit `tools/check-deps.py` die externen Abhängigkeiten geprüft
(Gradle-Plugins, Bibliotheken, Gradle-Wrapper, Stockfish; Sicherheitslücken über die OSV-Datenbank).

Für signierte Release-Builds legt man eine `keystore.properties` an. Sie gehört nicht ins Repository:

```
storeFile=/pfad/zum/keystore.jks
storePassword=…
keyAlias=…
keyPassword=…
```

`tools/svg2vector.py` wandelt die Figuren-SVGs in Android-Vektorgrafiken um.

Die Texte der Oberfläche stehen in `app/src/main/res/values/strings.xml` (Englisch, die Vorgabe) und
`app/src/main/res/values-de/strings.xml` (Deutsch). Quelltext-Kommentare und Commit-Nachrichten sind deutsch.

## Kompatibilität und Tests

- Zuggenerator: Perft-Tests mit Standardstellungen und Chess960-Stellungen (Sollwerte von Stockfish 19).
- Chess960: Rochaden mit beliebigen Turm- und Königsfeldern, FEN als X-FEN oder Shredder-FEN; die Engine
  wird dafür mit `UCI_Chess960` umgestellt. Andere Schachvarianten (Crazyhouse, Antichess usw.) gibt es nicht.
- Scid 4/5 lesen: Datenbanken, die mit Scid 5.2 erzeugt wurden, liefern Partie für Partie dieselben Züge,
  Varianten, Kommentare und Tags wie Scids eigener PGN-Export (`app/src/test/resources`).
- Scid 5 schreiben: über 300 Zufallspartien mit Varianten, Kommentaren, NAGs, Umwandlungen,
  FEN-Startstellungen und Nullzügen. Scid 5.2 liest sie identisch zurück.
- Scidb: Der Leser folgt dem Scidb-Quelltext. Getestet ist er nur mit von Hand nach Scidbs Encoder-Regeln
  erzeugten Daten, nicht mit echten Scidb-Dateien. Ältere Scidb-Dateiversionen (91–93) und
  Spielvarianten (Crazyhouse, Antichess usw.) werden nicht unterstützt.
- Archive: Scidb-Archive werden so erzeugt, wie Scidbs `archive::packFiles` sie schreibt (zlib und unkomprimiert,
  CRC-32-Prüfsumme), gzip- und ZIP-Dateien mit den Java-Bibliotheken. Echte Scidb-Archive sind ungetestet.

## Enthaltene freie Komponenten

| Komponente | Lizenz |
|---|---|
| Stockfish 19 (stockfishchess.org) | GPLv3 |
| Schachfiguren „cburnett“ von Colin M.L. Burnett | GPLv2+ |
| Datenbankformate nach den Quelltexten von Scid und Scidb | GPL |
| AndroidX, Jetpack Compose, Kotlin | Apache 2.0 |
