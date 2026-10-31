# Scidc2go

*[English version](README.en.md)*

Schachdatenbanken unterwegs: eine schlanke Android-App für Smartphone und Tablet.

- **Lesen:** Scid 3 (`.si3`), Scid 4 (`.si4`), Scid 5 (`.si5`), Scidb (`.sci`, Dateiversion 94), PGN,
  außerdem gepackt wie in Scidb: Scidb-Archive (`.scv`), PGN mit gzip (`.pgn.gz`) und ZIP-Archive mit PGN-Dateien (`.zip`),
  sowie das eigene Format `.sc7` (7z-Archiv mit einer Datenbank)
- **Schreiben:** Scid 5. Partien ersetzen, neu anhängen, zum Löschen markieren, neue Datenbanken anlegen;
  außerdem jede Datenbank als `.sc7` packen
- **Analyse:** Stockfish 19 ist eingebaut (NNUE, 1–5 Varianten). Das Netz (98 MB) lädt die App beim ersten
  Einschalten der Analyse einmalig von stockfishchess.org; nur dafür braucht sie die Internet-Berechtigung.
- **Ansichten:** Datenbankliste mit Suche und Brettansicht mit Zugliste, Varianten, Kommentaren, Bewertungszeichen und Eröffnungsnamen.
  Auf Tablets (ab 840 dp Breite) stehen Partieliste und Brett nebeneinander.
- **Sprachen:** Deutsch und Englisch, nach der Systemsprache oder in den Einstellungen wählbar.

Freie Software unter der GNU GPL Version 3 oder später (siehe `COPYING`), ohne proprietäre Bestandteile.

## Installation

Die APK liegt bei den [Releases](https://erebor.mittelerde.de:3053/forgejouser/scidc2go/releases) (Android 10 oder neuer). Updates lassen sich mit
[Obtainium](https://github.com/ImranR98/Obtainium) beziehen: die Adresse des Repositorys als Forgejo-Quelle eintragen.

Alle Releases sind mit demselben Schlüssel signiert. SHA-256-Fingerabdruck des Zertifikats
(zum Prüfen mit `apksigner verify --print-certs` oder [AppVerifier](https://github.com/soupslurpr/AppVerifier)):

```
org.scidc2go
50:CB:55:32:E4:89:68:80:C4:A0:29:44:71:23:3A:0B:BA:CE:52:51:AC:19:F1:F9:D5:25:AC:E3:AE:94:5A:F1
```

## Bedienung

1. **Ordner wählen**: den Ordner mit den Datenbanken freigeben (Storage Access Framework).
   Scid-Datenbanken bestehen aus drei Dateien, deshalb wird der ganze Ordner freigegeben.
   Alternativ öffnet **Dateien öffnen** einzelne Dateien, etwa aus der Nextcloud-App. Bei Scid und Scidb genügt
   eine Datei der Datenbank: Liegt sie in einem schon freigegebenen Ordner (oder einem Unterordner davon), öffnet
   die App die Datenbank direkt. Sonst fragt sie einmalig nach dem Ordner, denn Android erlaubt den Zugriff auf
   die übrigen Dateien nur mit Ordnerfreigabe. Fehlt eine Datei der Datenbank, meldet die App welche.
   Gepackte Datenbanken (`.scv`, `.pgn.gz`, `.zip`, `.sc7`) entpackt die App beim Öffnen in ihren Cache und öffnet sie
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
   **Als 7z-Archiv packen** (ebenfalls im Menü) legt die Dateien einer Scid-3/4/5-, Scidb- oder PGN-Datenbank
   unverändert in ein 7z-Archiv `name.sc7` im selben Ordner, etwa mit `name.si5`, `name.sg5` und `name.sn5`.
   Es ist ein gewöhnliches 7z-Archiv (LZMA2): 7-Zip erkennt es am Inhalt und entpackt es trotz der eigenen Endung;
   Packer, die nach der Endung gehen, öffnen es nach dem Umbenennen in `.7z`. Umgekehrt öffnet die App jedes 7z-Archiv mit
   der Endung `.sc7`, das die Dateien einer Datenbank enthält; das Format erkennt sie an den Dateien
   (Ordner im Archiv spielen keine Rolle). Das **Teilen**-Symbol der Partieliste gibt die Datenbank an eine
   andere App weiter, etwa Mail oder Nextcloud: PGN und Archive als Datei, Scid und Scidb als `.sc7`.
   Die genaue Beschreibung des Formats für andere Programme steht in
   [docs/sc7.md](docs/sc7.md).
   **Neue Partie** im Menü der Partieliste öffnet ein leeres Brett; **Speichern** hängt die Partie dann an die
   geöffnete Scid-5-Datenbank an (oder legt sie in einer anderen ab).
3. Partie antippen, um die Brettansicht zu öffnen.
   - Züge eingeben: Figur auf das Zielfeld ziehen (oder Figur und Zielfeld nacheinander antippen).
     Weicht der Zug von der Partie ab, entsteht eine Variante.
   - Ein Zug in der Zugliste springt zu dieser Stellung.
   - Unter dem Brett stehen ECO-Code und Name der Eröffnung der aktuellen Stellung (auch nach Zugumstellung).
     Das Pfeilsymbol neben dem Zurück-Pfeil klappt Spieler, Turnier, Datum, Ergebnis und die Eröffnung der
     Partie aus. Die Namen stammen aus der Eröffnungstabelle von Lichess und sind englisch.
   - **Analyse** im Menü (⋮) startet die gewählte Engine. Ihre Varianten stehen über dem Brett (im Querformat
     daneben); ein Antippen einer Variante spielt deren ersten Zug.
   - **Kommentare anzeigen** und **Varianten anzeigen** im Menü blenden Kommentare bzw. Varianten in der Zugliste
     aus (die Einstellung bleibt erhalten). Die Variante mit dem aktuellen Zug bleibt immer sichtbar.
   - Menü: Partiedaten, Kommentar, Variante hochstufen oder löschen, PGN/FEN kopieren, PGN einfügen.
   - **Teilen** (Symbol in der Kopfleiste): die Partie als PGN-Datei und Text oder die Stellung als FEN an
     andere Apps.
   - **Speichern**: in die geöffnete Scid-5-Datenbank (ersetzen oder neu) oder in eine andere bzw. neue Scid-5-Datenbank im Ordner.

4. **Einstellungen** (Zahnrad): Darstellung hell, dunkel oder wie im System, die Sprache (Deutsch, Englisch oder
   wie im System; ab Android 13, davor gilt die Systemsprache) und die Engine für die Analyse.
   Neben dem eingebauten Stockfish erscheinen dort Engines aus installierten Engine-Apps nach dem
   Open-Exchange-Standard (OEX). Weil Android 10 und neuer Apps keine Programme aus ihrem Datenverzeichnis
   starten lässt, kopiert die App solche Engines und startet sie über den System-Linker (`/system/bin/linker64`).
   **Protokoll:** Die App schreibt Aktionen, Meldungen, Fehler und Abstürze (mit Stack-Trace) in ein Protokoll im
   App-Verzeichnis, höchstens zweimal 512 KB. „Ausführlich“ nimmt die Kommunikation mit der Engine dazu.
   Das Protokoll lässt sich anzeigen, als Textdatei speichern (etwa um es einer Fehlermeldung beizulegen) und
   löschen. Es enthält Datei- und Datenbanknamen, aber keine Partien (ausführlich auch die analysierten
   Stellungen), und verlässt das Gerät nur beim Speichern.

## Bauen

Benötigt: JDK 17, Android SDK (Plattform 37) und für Stockfish das Android NDK (r27c oder neuer).

```sh
tools/build-stockfish.sh        # Stockfish für arm64-v8a, armeabi-v7a, x86_64; trägt das NNUE-Netz in StockfishNet.kt ein
tools/build-apk.sh              # aktuelle APK bauen (mit Tests) und nach dist/ kopieren; --debug, --no-tests
./gradlew assembleRelease       # APK: app/build/outputs/apk/release/
./gradlew testDebugUnitTest     # Unit-Tests
tools/check-deps.py             # neuere Versionen und bekannte Sicherheitslücken der Abhängigkeiten
tools/update-openings.sh        # Eröffnungstabelle von Lichess neu holen (assets/openings.tsv)
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

Fehlersuche: Release-Builds werden von R8 verkleinert, aber nicht verschleiert; Klassen- und Methodennamen in den
Stack-Traces des Protokolls sind also lesbar. Genaue Zeilennummern liefert `retrace` aus den Android-cmdline-tools
mit der Zuordnungsdatei des Builds, die `tools/build-apk.sh` als `dist/scidc2go-<Version>-mapping.txt` ablegt und
die bei jedem Release mit hochgeladen wird: `retrace scidc2go-<Version>-mapping.txt protokoll.txt`.

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
- `.sc7`: Die App schreibt LZMA2 und liest, was Apache Commons Compress kann (LZMA, LZMA2, BZip2, Deflate, BCJ-Filter,
  auch solide Archive). PPMd und verschlüsselte Archive werden nicht unterstützt. Getestet mit 7-Zip 23.01 in beide
  Richtungen: Von 7-Zip gepackte Archive (`app/src/test/resources/*.sc7`) lassen sich öffnen, und 7-Zip prüft die
  Archive der App fehlerfrei und entpackt sie byte-gleich.
- Archive: Scidb-Archive werden so erzeugt, wie Scidbs `archive::packFiles` sie schreibt (zlib und unkomprimiert,
  CRC-32-Prüfsumme), gzip- und ZIP-Dateien mit den Java-Bibliotheken. Echte Scidb-Archive sind ungetestet.

## Enthaltene freie Komponenten

| Komponente | Lizenz |
|---|---|
| Stockfish 19 (stockfishchess.org) | GPLv3 |
| Schachfiguren „cburnett“ von Colin M.L. Burnett | GPLv2+ |
| Datenbankformate nach den Quelltexten von Scid und Scidb | GPL |
| AndroidX, Jetpack Compose, Kotlin | Apache 2.0 |
| Apache Commons Compress (mit Commons IO, Lang, Codec) | Apache 2.0 |
| XZ for Java | 0BSD |
| Eröffnungsnamen von Lichess (github.com/lichess-org/chess-openings) | CC0 |
