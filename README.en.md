# Scidc2go

*[Deutsche Fassung](README.md)*

Chess databases on the go: a lean Android app for phones and tablets.

- **Reads:** Scid 3 (`.si3`), Scid 4 (`.si4`), Scid 5 (`.si5`), Scidb (`.sci`, file version 94), PGN,
  and compressed formats as in Scidb: Scidb archives (`.scv`), gzipped PGN (`.pgn.gz`) and ZIP archives with PGN files (`.zip`),
  as well as the app's own format `.sc7` (a 7z archive containing one database)
- **Writes:** Scid 5. Replace games, append new ones, mark them for deletion, create new databases;
  any database can also be packed as `.sc7`
- **Analysis:** Stockfish 19 is built in (NNUE, 1–5 lines). The app downloads the network (98 MB) once from
  stockfishchess.org when analysis is first switched on; that is the only reason it needs internet permission.
- **Views:** database list with search, and a board view with move list, variations, comments and annotation symbols.
  On tablets (840 dp wide and more) the game list and the board are shown side by side.
- **Languages:** English and German, following the system language or chosen in the settings.

Free software under the GNU GPL version 3 or later (see `COPYING`), with no proprietary components.

## Usage

1. **Choose folder**: grant access to the folder containing the databases (Storage Access Framework).
   Scid databases consist of three files, which is why access is granted to the whole folder.
   Alternatively, **Open files** opens individual files, for example from the Nextcloud app. For Scid and Scidb,
   one file of the database is enough: if it lies in a folder that has already been granted (or a subfolder of it),
   the app opens the database directly. Otherwise it asks for the folder once, because Android only allows access
   to the other files with a folder grant. If a file of the database is missing, the app says which one.
   The app unpacks compressed databases (`.scv`, `.pgn.gz`, `.zip`, `.sc7`) into its cache when opening them and opens them
   read-only; the unpacked copy is deleted when the database is closed. As in Scidb, a Scidb archive may contain a
   Scidb, Scid 3/4 or PGN database. All PGN files in a ZIP archive are read one after the other, as in Scidb.
   To edit them, convert them to Scid 5.
2. Tap a database to see its game list. The search field searches players, event, site, date, ECO and round.
   A long press on a game opens a menu: set or remove the deletion mark, or delete the game permanently
   (Scid 5 only). The menu (⋮) of the game list deletes all marked games at once.
   Deleted games disappear from the index; their data stays unused in the `.sg5` file until the database is
   compacted. **Compact database** (also in the menu) removes the marked games, unused space and names no longer
   in use. A complete copy (`name.neu`) is written first, and only then is the original replaced, so a complete
   database is always left if the process is interrupted.
   **Convert to Scid 5** (game list menu) writes a Scid 3/4, Scidb or PGN database into a new Scid 5 database in
   the same folder; the original stays unchanged. Games that cannot be transferred (corrupt or larger than 128 KB)
   are skipped and reported.
   **Pack as 7z archive** (also in the menu) puts the files of a Scid 3/4/5, Scidb or PGN database unchanged into a
   7z archive `name.sc7` in the same folder, for example with `name.si5`, `name.sg5` and `name.sn5`. It is an
   ordinary 7z archive (LZMA2): 7-Zip recognizes it by its content and unpacks it despite the custom extension;
   archivers that go by the extension open it after renaming it to `.7z`. Conversely, the app opens any 7z archive with the
   extension `.sc7` that contains the files of a database; it recognizes the format by the files
   (folders inside the archive do not matter). **Share database** (also in the menu) passes the database to another
   app, such as mail or Nextcloud: PGN and archives as a file, Scid and Scidb as `.sc7`.
   The detailed description of the format for other programs is in
   [docs/sc7.en.md](docs/sc7.en.md).
3. Tap a game to open the board view.
   - Entering moves: drag a piece to its target square (or tap the piece and then the target square).
     If the move differs from the game, a variation is created.
   - Tapping a move in the move list jumps to that position.
   - **Analysis** in the menu (⋮) starts the selected engine. Its lines are shown above the board (beside it in
     landscape); tapping a line plays its first move.
   - **Show comments** and **Show variations** in the menu hide comments or variations in the move list
     (the setting is remembered). The variation containing the current move always stays visible.
   - Menu: game data, comment, promote or delete a variation, copy PGN/FEN, paste PGN, **share game** (as a PGN file
     and text) and **share position** (FEN) with other apps.
   - **Save**: into the open Scid 5 database (replace or as a new game) or into another or a new Scid 5 database
     in the folder.

4. **Settings** (gear icon): light, dark or system appearance, the language (English, German or system default;
   from Android 13, before that the system language applies) and the engine used for analysis.
   Besides the built-in Stockfish, engines from installed engine apps following the Open Exchange standard (OEX)
   appear there. Since Android 10 and later do not allow apps to run programs from their data directory, the app
   copies such engines and starts them through the system linker (`/system/bin/linker64`).
   **Log:** the app writes actions, messages, errors and crashes (with stack trace) to a log in its app directory,
   at most twice 512 KB. “Detailed” adds the communication with the engine. The log can be shown, saved as a
   text file (for example to attach it to a bug report) and cleared. It contains file and database names but no
   games (in detailed mode also the analysed positions), and it only leaves the device when you save it.

## Building

Requirements: JDK 17, Android SDK (platform 35) and, for Stockfish, the Android NDK (r27c or later).

```sh
tools/build-stockfish.sh        # Stockfish for arm64-v8a, armeabi-v7a, x86_64; enters the NNUE network in StockfishNet.kt
tools/build-apk.sh              # build the current APK (with tests) and copy it to dist/; --debug, --no-tests
./gradlew assembleRelease       # APK: app/build/outputs/apk/release/
./gradlew testDebugUnitTest     # unit tests
tools/check-deps.py             # newer versions and known vulnerabilities of the dependencies
```

Before the first release of a day, `tools/check-deps.py` checks the external dependencies
(Gradle plugins, libraries, Gradle wrapper, Stockfish; vulnerabilities via the OSV database).

For signed release builds, create a `keystore.properties` file. It does not belong in the repository:

```
storeFile=/path/to/keystore.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Debugging: release builds are shrunk by R8 but not obfuscated, so class and method names in the log's stack traces
are readable. Exact line numbers come from `retrace` in the Android cmdline-tools with the build's mapping file,
which `tools/build-apk.sh` stores as `dist/scidc2go-<version>-mapping.txt` and which is uploaded with every release:
`retrace scidc2go-<version>-mapping.txt log.txt`.

`tools/svg2vector.py` converts the piece SVGs into Android vector drawables.

The user interface texts are in `app/src/main/res/values/strings.xml` (English, the default) and
`app/src/main/res/values-de/strings.xml` (German). Source code comments and commit messages are in German.

## Compatibility and tests

- Move generator: perft tests with standard positions and Chess960 positions (reference values from Stockfish 19).
- Chess960: castling with any rook and king squares, FEN as X-FEN or Shredder-FEN; the engine is switched to
  `UCI_Chess960` for such games. Other chess variants (Crazyhouse, Antichess etc.) are not supported.
- Reading Scid 4/5: databases created with Scid 5.2 yield, game by game, the same moves, variations, comments and
  tags as Scid's own PGN export (`app/src/test/resources`).
- Writing Scid 5: more than 300 random games with variations, comments, NAGs, promotions, FEN start positions and
  null moves. Scid 5.2 reads them back identically.
- Scidb: the reader follows the Scidb source code. It has only been tested with data generated by hand according to
  Scidb's encoder rules, not with real Scidb files. Older Scidb file versions (91–93) and chess variants
  (Crazyhouse, Antichess etc.) are not supported.
- `.sc7`: the app writes LZMA2 and reads what Apache Commons Compress supports (LZMA, LZMA2, BZip2, Deflate, BCJ filters,
  solid archives too). PPMd and encrypted archives are not supported. Tested with 7-Zip 23.01 in both directions:
  archives packed by 7-Zip (`app/src/test/resources/*.sc7`) open, and 7-Zip tests the app's archives without errors
  and unpacks them byte for byte.
- Archives: Scidb archives are read the way Scidb's `archive::unpack` does (zlib and uncompressed, CRC-32 checksum)
  and tested with archives generated by the rules of `archive::packFiles`; real Scidb archives are untested.
  gzip and ZIP files are read with the Java libraries.

## Included free components

| Component | License |
|---|---|
| Stockfish 19 (stockfishchess.org) | GPLv3 |
| Chess pieces “cburnett” by Colin M.L. Burnett | GPLv2+ |
| Database formats based on the source code of Scid and Scidb | GPL |
| AndroidX, Jetpack Compose, Kotlin | Apache 2.0 |
| Apache Commons Compress (with Commons IO, Lang, Codec) | Apache 2.0 |
| XZ for Java | 0BSD |
