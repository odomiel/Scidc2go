# The `.sc7` archive format

*[Deutsche Fassung](sc7.md)*

`.sc7` is Scidc2go's archive format for a single chess database. It is an ordinary **7z archive** that holds the
files of a Scid 3/4/5, Scidb or PGN database unchanged. A program that can unpack 7z and read one of these
database formats can therefore read `.sc7` as well; there are no additional structures, headers or metadata files.

This description applies to Scidc2go from version 0.10.0.

## Summary

- Container: 7z (signature `37 7A BC AF 27 1C`), extension `.sc7`
- Content: the files of **exactly one** database, all with the same base name, e.g. `games.si5`,
  `games.sg5`, `games.sn5`
- The database format follows from the extensions of the contained files
- Files are stored unchanged; compression comes from 7z only

## Container

The 7z format as defined by 7-Zip applies (description: `DOC/7zFormat.txt` in the 7-Zip source code or the
LZMA SDK, https://www.7-zip.org/sdk.html).

| Property | Value |
|---|---|
| Extension | `.sc7` (lower case; readers should recognize the extension regardless of case) |
| Detection by content | 7z signature `37 7A BC AF 27 1C` at the start of the file |
| MIME type | none of its own; `application/x-7z-compressed` or `application/octet-stream` |

On Android, Scidc2go creates new archives as `application/octet-stream`, because some storage providers would
otherwise append the extension `.7z` to the name.

## Content

The archive contains the files of exactly one database. The following formats are allowed; the first extension
of each row is the main file, and all extensions of a row are mandatory:

| Format | Files | Format description |
|---|---|---|
| Scid 5 | `.si5` `.sg5` `.sn5` | Scid 5 source code (https://github.com/benini/scid) |
| Scid 4 | `.si4` `.sg4` `.sn4` | Scid 4 or 5 source code |
| Scid 3 | `.si3` `.sg3` `.sn3` | Scid 3 or 4 source code |
| Scidb | `.sci` `.scg` `.scn` | Scidb source code (file version 94) |
| PGN | `.pgn` | PGN standard (Portable Game Notation) |

Rules for programs that **write** `.sc7`:

1. All files are at the top level of the archive, without folders.
2. All files have the same base name and lower-case extensions (`name.si5`, not `name.SI5`).
3. The files are stored byte for byte as the database program writes them.
4. No encryption.
5. LZMA2, the 7-Zip default, is recommended. For other methods see below.
6. Further files (such as a Scid 4 sort file `.ssc` or a text file) are allowed, but Scidc2go ignores them.

Rules for programs that **read** `.sc7` (this is how Scidc2go behaves):

1. Folder paths inside the archive are ignored; only the file name counts. Path components such as `..` must
   never lead to files outside the target folder.
2. If a file name occurs more than once, the first file wins.
3. The format is recognized by the main files, in this order: `.si5`, `.si4`, `.si3`, `.sci`, `.pgn`. The first
   main file found determines the format and the base name; the other mandatory files must have the same base
   name. If one is missing, the archive is invalid.
4. Unknown files are ignored. Future extensions of the format will at most add further files and will not change
   the database files.
5. If the archive contains no main file, it is not an `.sc7` database.

## Compression

Scidc2go writes each file as a separate block (not solid) with **LZMA2**, preset 4 (4 MiB dictionary). Packing
on a phone thus needs about 50 MB of memory. The archive records the time of packing as the modification time.
The archive headers are not compressed.

Scidc2go reads what Apache Commons Compress supports: Copy (uncompressed), LZMA, LZMA2, BZip2, Deflate,
Deflate64 and the filters BCJ (x86, ARM, ARM Thumb, PowerPC, IA-64, SPARC) and Delta, in solid archives as well.
**Not** supported are PPMd, Zstandard and other methods that exist only in 7-Zip forks, as well as encrypted
archives. Programs that write `.sc7` files for Scidc2go should therefore stick to LZMA2 or LZMA.

## Editing

Scidc2go opens `.sc7` archives read-only: it unpacks them into a working folder and opens the database from
there. To edit one, unpack the database, change it and pack it again. A program that wants to edit `.sc7`
directly has to rewrite the whole archive when saving; 7z does not allow changing individual files in place.

## Examples

With 7-Zip on the command line (tested with 7-Zip 23.01 for Linux, `7zz`; on Windows the program is called
`7z.exe`):

```sh
# Pack; -t7z forces 7z (7-Zip also picks it on its own for an unknown extension)
7zz a -t7z -m0=lzma2 -mx=5 games.sc7 games.si5 games.sg5 games.sn5

# Test and unpack: 7-Zip recognizes the format by its content, no renaming needed
7zz t games.sc7
7zz x games.sc7
```

Programs that go by the extension open the file after renaming it to `games.7z`.

In Kotlin or Java with Apache Commons Compress and XZ for Java (this is how Scidc2go does it in
`app/src/main/java/org/scidc2go/db/Archives.kt`):

```kotlin
import org.apache.commons.compress.archivers.sevenz.*
import org.tukaani.xz.LZMA2Options
import java.io.File

// Pack
SevenZOutputFile(File("games.sc7")).use { z ->
    z.setContentMethods(listOf(SevenZMethodConfiguration(SevenZMethod.LZMA2, LZMA2Options(4))))
    for (ext in listOf("si5", "sg5", "sn5")) {
        val f = File("games.$ext")
        z.putArchiveEntry(z.createArchiveEntry(f, f.name))
        f.inputStream().use { z.write(it) }
        z.closeArchiveEntry()
    }
}

// Unpack (file names only, without folders)
SevenZFile.builder().setFile(File("games.sc7")).get().use { z ->
    val dir = File("target").apply { mkdirs() }
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

For other languages, options include the LZMA SDK (C/C++), libarchive (C, reads 7z) and py7zr (Python).

## Test files

`app/src/test/resources` contains two examples created with 7-Zip 23.01:

- `s5.sc7`: Scid 5 database `s5` (three games), packed solid
- `sample.sc7`: PGN file `sample.pgn` in a subfolder `partien/`, with `-mx=9`

A reading program must be able to open both and yield the same games as `s5.si5`/`.sg5`/`.sn5` and
`sample.pgn` in the same folder.
