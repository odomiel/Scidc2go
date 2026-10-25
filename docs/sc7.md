# Das Archivformat `.sc7`

*[English version](sc7.en.md)*

`.sc7` ist das Archivformat von Scidc2go für eine einzelne Schachdatenbank. Es ist ein gewöhnliches
**7z-Archiv**, in dem die Dateien einer Scid-3/4/5-, Scidb- oder PGN-Datenbank unverändert liegen. Ein Programm,
das 7z entpacken und eines dieser Datenbankformate lesen kann, kann damit auch `.sc7` lesen; es gibt keine
zusätzlichen Strukturen, Kopfdaten oder Metadateien.

Diese Beschreibung gilt für Scidc2go ab Version 0.10.0.

## Kurzfassung

- Container: 7z (Signatur `37 7A BC AF 27 1C`), Endung `.sc7`
- Inhalt: die Dateien **genau einer** Datenbank, alle mit demselben Basisnamen, z.B. `partien.si5`,
  `partien.sg5`, `partien.sn5`
- Das Format der Datenbank ergibt sich aus den Endungen der enthaltenen Dateien
- Dateien werden ohne Änderung gespeichert; Kompression nur durch 7z

## Container

Es gilt das 7z-Format, wie es 7-Zip definiert (Beschreibung: `DOC/7zFormat.txt` im Quelltext von 7-Zip
bzw. dem LZMA SDK, https://www.7-zip.org/sdk.html).

| Eigenschaft | Wert |
|---|---|
| Endung | `.sc7` (Kleinschreibung; Leser sollten die Endung ohne Rücksicht auf Groß-/Kleinschreibung erkennen) |
| Erkennung am Inhalt | 7z-Signatur `37 7A BC AF 27 1C` am Dateianfang |
| MIME-Typ | kein eigener; `application/x-7z-compressed` oder `application/octet-stream` |

Scidc2go legt neue Archive auf Android als `application/octet-stream` an, weil manche Speicheranbieter
sonst die Endung `.7z` an den Namen anhängen.

## Inhalt

Das Archiv enthält die Dateien genau einer Datenbank. Erlaubt sind folgende Formate; die erste Endung ist
jeweils die Hauptdatei, alle Endungen einer Zeile sind Pflicht:

| Format | Dateien | Beschreibung des Formats |
|---|---|---|
| Scid 5 | `.si5` `.sg5` `.sn5` | Quelltext von Scid 5 (https://github.com/benini/scid) |
| Scid 4 | `.si4` `.sg4` `.sn4` | Quelltext von Scid 4 bzw. 5 |
| Scid 3 | `.si3` `.sg3` `.sn3` | Quelltext von Scid 3 bzw. 4 |
| Scidb | `.sci` `.scg` `.scn` | Quelltext von Scidb (Dateiversion 94) |
| PGN | `.pgn` | PGN-Standard (Portable Game Notation) |

Regeln für **schreibende** Programme:

1. Alle Dateien liegen auf oberster Ebene des Archivs, ohne Ordner.
2. Alle Dateien haben denselben Basisnamen und kleingeschriebene Endungen (`name.si5`, nicht `name.SI5`).
3. Die Dateien werden Byte für Byte so gespeichert, wie das Datenbankprogramm sie schreibt.
4. Keine Verschlüsselung.
5. Empfohlen ist LZMA2, die Voreinstellung von 7-Zip. Andere Verfahren siehe unten.
6. Weitere Dateien (etwa eine Scid-4-Sortierdatei `.ssc` oder eine Textdatei) sind erlaubt, werden aber
   von Scidc2go ignoriert.

Regeln für **lesende** Programme (so verhält sich Scidc2go):

1. Ordnerpfade im Archiv werden ignoriert, es zählt nur der Dateiname. Pfadbestandteile wie `..` dürfen nie
   zu Dateien außerhalb des Zielordners führen.
2. Kommt ein Dateiname mehrfach vor, gilt die erste Datei.
3. Das Format wird an den Hauptdateien erkannt, in dieser Reihenfolge: `.si5`, `.si4`, `.si3`, `.sci`, `.pgn`.
   Die erste gefundene Hauptdatei bestimmt Format und Basisnamen; die übrigen Pflichtdateien müssen denselben
   Basisnamen haben. Fehlt eine, ist das Archiv ungültig.
4. Unbekannte Dateien werden ignoriert. Künftige Erweiterungen des Formats fügen höchstens weitere Dateien
   hinzu, ändern aber die Datenbankdateien nicht.
5. Enthält das Archiv keine Hauptdatei, ist es keine `.sc7`-Datenbank.

## Kompression

Scidc2go schreibt jede Datei als eigenen Block (nicht solid) mit **LZMA2**, Voreinstellung 4
(Wörterbuch 4 MiB). Damit braucht das Packen auf dem Telefon etwa 50 MB Speicher. Als Änderungszeit
steht der Zeitpunkt des Packens im Archiv. Die Kopfdaten des Archivs sind nicht komprimiert.

Scidc2go liest, was Apache Commons Compress unterstützt: Copy (unkomprimiert), LZMA, LZMA2, BZip2, Deflate,
Deflate64 sowie die Filter BCJ (x86, ARM, ARM-Thumb, PowerPC, IA-64, SPARC) und Delta, auch in soliden Archiven.
**Nicht** unterstützt sind PPMd, Zstandard und andere Verfahren, die es nur in Ablegern von 7-Zip gibt, sowie
verschlüsselte Archive. Wer `.sc7`-Dateien für Scidc2go schreibt, sollte deshalb bei LZMA2 oder LZMA bleiben.

## Bearbeiten

Scidc2go öffnet `.sc7`-Archive schreibgeschützt: Es entpackt sie in einen Arbeitsordner und öffnet die
Datenbank von dort. Zum Bearbeiten entpackt man die Datenbank, ändert sie und packt sie neu. Ein Programm,
das `.sc7` direkt bearbeiten will, muss das Archiv beim Speichern vollständig neu schreiben; 7z erlaubt kein
Ändern einzelner Dateien an Ort und Stelle.

## Beispiele

Mit 7-Zip auf der Kommandozeile (getestet mit 7-Zip 23.01 für Linux, `7zz`; unter Windows heißt das Programm
`7z.exe`):

```sh
# Packen; -t7z erzwingt 7z (7-Zip wählt es bei unbekannter Endung aber auch von selbst)
7zz a -t7z -m0=lzma2 -mx=5 partien.sc7 partien.si5 partien.sg5 partien.sn5

# Prüfen und Entpacken: 7-Zip erkennt das Format am Inhalt, Umbenennen ist nicht nötig
7zz t partien.sc7
7zz x partien.sc7
```

Programme, die nach der Endung gehen, öffnen die Datei nach dem Umbenennen in `partien.7z`.

In Kotlin bzw. Java mit Apache Commons Compress und XZ for Java (so macht es Scidc2go in
`app/src/main/java/org/scidc2go/db/Archives.kt`):

```kotlin
import org.apache.commons.compress.archivers.sevenz.*
import org.tukaani.xz.LZMA2Options
import java.io.File

// Packen
SevenZOutputFile(File("partien.sc7")).use { z ->
    z.setContentMethods(listOf(SevenZMethodConfiguration(SevenZMethod.LZMA2, LZMA2Options(4))))
    for (ext in listOf("si5", "sg5", "sn5")) {
        val f = File("partien.$ext")
        z.putArchiveEntry(z.createArchiveEntry(f, f.name))
        f.inputStream().use { z.write(it) }
        z.closeArchiveEntry()
    }
}

// Entpacken (nur Dateinamen, ohne Ordner)
SevenZFile.builder().setFile(File("partien.sc7")).get().use { z ->
    val dir = File("ziel").apply { mkdirs() }
    while (true) {
        val e = z.nextEntry ?: break
        if (e.isDirectory || !e.hasStream()) continue
        val name = File(e.name.replace('\\', '/')).name
        File(dir, name).outputStream().use { out ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = z.read(buf); if (n < 0) break; out.write(buf, 0, n) }
        }
    }
}
```

Für andere Sprachen eignen sich unter anderem das LZMA SDK (C/C++), libarchive (C, liest 7z) und py7zr (Python).

## Testdateien

In `app/src/test/resources` liegen zwei mit 7-Zip 23.01 erzeugte Beispiele:

- `s5.sc7`: Scid-5-Datenbank `s5` (drei Partien), solid gepackt
- `sample.sc7`: PGN-Datei `sample.pgn` in einem Unterordner `partien/`, mit `-mx=9`

Beide müssen sich in einem lesenden Programm öffnen lassen und dieselben Partien liefern wie
`s5.si5`/`.sg5`/`.sn5` bzw. `sample.pgn` im selben Ordner.
