/*
 * Scidc2go - Schachdatenbanken unterwegs
 * Copyright (C) 2026  Scidc2go-Autoren
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.scidc2go.db

import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.scidc2go.LocalizedIOException
import org.scidc2go.R
import org.scidc2go.Text
import org.tukaani.xz.LZMA2Options
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Date
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

/**
 * Gepackte Datenbanken, wie Scidb sie kennt: Scidb-Archive (`.scv`), gzip-PGN (`.pgn.gz`) und
 * ZIP-Archive mit PGN-Dateien (`.zip`), außerdem 7z-Archive mit einer Datenbank (`.sc7`, eigenes Format von Scidc2go).
 * Sie werden in ein Arbeitsverzeichnis entpackt und von dort schreibgeschützt geöffnet.
 */
object Archives {

    /**
     * Entpackt ein Scidb-Archiv nach [dir] (wie `archive::unpack` in Scidbs `tcl/utils/archive.tcl`).
     * Aufbau: Zeile `iveArch`, Kopfzeilen `<Attr> Wert`, dann je Datei die Zeile `<-- H E A D -->`,
     * Attribute (FileName, Size, Compression raw|zlib, Checksum = CRC-32 der gespeicherten Bytes),
     * die Zeile `<-- D A T A -->`, [Size] Bytes Daten und ein Zeilenende. Liefert die Dateinamen.
     */
    fun extractScidbArchive(input: InputStream, dir: File): List<String> {
        val inp = input.buffered()
        if (inp.readLine() != "iveArch") throw LocalizedIOException(R.string.err_not_scv)
        var line = inp.readLine()
        while (line != null && line != HEAD) line = inp.readLine()
        val names = ArrayList<String>()
        while (line != null) {
            if (line != HEAD) throw LocalizedIOException(R.string.err_scv_corrupt)
            val attrs = HashMap<String, String>()
            while (true) {
                line = inp.readLine() ?: throw LocalizedIOException(R.string.err_scv_corrupt)
                if (line == DATA) break
                ATTR.matchEntire(line)?.let { attrs[it.groupValues[1]] = it.groupValues[2].trim() }
            }
            val fileName = attrs["FileName"] ?: throw LocalizedIOException(R.string.err_scv_no_name)
            val size = attrs["Size"]?.toLongOrNull() ?: throw LocalizedIOException(R.string.err_scv_no_size)
            // Nur den Dateinamen übernehmen, damit nichts außerhalb von dir landet
            val name = File(fileName.replace('\\', '/')).name
            if (name.isEmpty() || name == "." || name == "..") throw LocalizedIOException(R.string.err_bad_filename, fileName)
            val stored = LimitedInputStream(inp, size)
            val crc = CRC32()
            val checked = CheckedInputStream(stored, crc)
            File(dir, name).outputStream().use { out ->
                when (val c = attrs["Compression"] ?: "raw") {
                    "raw" -> checked.copyTo(out)
                    "zlib" -> InflaterInputStream(checked).copyTo(out)
                    else -> throw LocalizedIOException(R.string.err_unknown_compression, name, c)
                }
            }
            // Rest nach dem zlib-Ende überspringen; er zählt zur Prüfsumme
            checked.skip(Long.MAX_VALUE)
            attrs["Checksum"]?.toLongOrNull()?.let {
                if ((it and 0xFFFFFFFFL) != crc.value) throw LocalizedIOException(R.string.err_checksum_file, name)
            }
            names.add(name)
            inp.readLine()          // Zeilenende nach den Daten
            line = inp.readLine()   // nächste Datei oder Dateiende
        }
        return names
    }

    /** Entpackt eine gzip-Datei nach [out]. */
    fun extractGzip(input: InputStream, out: File) {
        GZIPInputStream(input, 1 shl 16).use { gz -> out.outputStream().use { gz.copyTo(it) } }
    }

    /**
     * Schreibt alle PGN-Dateien eines ZIP-Archivs hintereinander nach [out] (wie Scidb).
     * Liefert ihre Anzahl; 0 heißt, das Archiv enthält keine PGN-Datei.
     */
    fun extractZipPgn(input: InputStream, out: File): Int {
        var count = 0
        ZipInputStream(input).use { zip ->
            out.outputStream().buffered().use { o ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory || !e.name.lowercase().endsWith(".pgn")) continue
                    if (count++ > 0) o.write('\n'.code)
                    zip.copyTo(o)
                }
            }
        }
        return count
    }

    /** Öffnet die entpackte Datenbank in [dir] schreibgeschützt. Wie in Scidb: Scidb, Scid 3/4 oder PGN (auch gzip-gepackt). */
    fun openExtracted(dir: File, name: String, archiveFormat: String, progress: (Float) -> Unit): ChessDatabase {
        val files = dir.listFiles()?.map { it.name } ?: emptyList()
        fun find(ext: String) = files.firstOrNull { it.lowercase().endsWith(".$ext") }
        fun base(ext: String) = find(ext)?.dropLast(ext.length + 1)
        fun pgn(f: File) = PgnDatabase(name, java.io.RandomAccessFile(f, "r").channel, progress)
        val inner = base("sci")?.let { ScidbDatabase(JavaFileSet(dir, it)) }
            ?: base("si4")?.let { Scid4Database(JavaFileSet(dir, it)) }
            ?: base("si3")?.let { Scid4Database(JavaFileSet(dir, it), v3 = true) }
            ?: find("pgn.gz")?.let { gz ->
                val out = File(dir, "entpackt-$name.pgn")
                File(dir, gz).inputStream().use { extractGzip(it, out) }
                pgn(out)
            }
            ?: find("pgn")?.let { pgn(File(dir, it)) }
            ?: throw LocalizedIOException(R.string.err_archive_no_db)
        return ArchiveDatabase(inner, dir, name, archiveFormat)
    }

    /**
     * Entpackt alle Dateien des 7z-Archivs [archive] nach [dir]. Ordner im Archiv werden ignoriert, nur der
     * Dateiname zählt (bei gleichen Namen gilt die erste Datei). [progress] erhält den Anteil von 0 bis 1.
     */
    fun extract7z(archive: File, dir: File, progress: (Float) -> Unit = {}): List<String> = sevenZ {
        val names = ArrayList<String>()
        SevenZFile.builder().setFile(archive).get().use { z ->
            val total = z.entries.filter { !it.isDirectory && it.hasStream() }.sumOf { it.size }.coerceAtLeast(1)
            var done = 0L
            val buf = ByteArray(1 shl 16)
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory || !e.hasStream() || e.isAntiItem) continue
                val name = File((e.name ?: continue).replace('\\', '/')).name
                if (name.isEmpty() || name == "." || name == ".." || name in names) continue
                File(dir, name).outputStream().use { out ->
                    while (true) {
                        val n = z.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
                names.add(name)
            }
        }
        names
    }

    /**
     * Schreibt [files] (Name im Archiv → Quelle) als 7z-Archiv nach [out], LZMA2 wie bei 7-Zip.
     * Voreinstellung 4 (4 MB Wörterbuch) braucht beim Packen etwa 50 MB Speicher statt 100 MB bei Stufe 6.
     * [progress] erhält die Zahl der bisher gelesenen Bytes.
     */
    fun write7z(out: File, files: List<Pair<String, () -> InputStream>>, progress: (Long) -> Unit = {}) = sevenZ {
        val now = Date()
        var done = 0L
        val buf = ByteArray(1 shl 16)
        SevenZOutputFile(out).use { z ->
            z.setContentMethods(listOf(SevenZMethodConfiguration(SevenZMethod.LZMA2, LZMA2Options(4))))
            for ((name, open) in files) {
                z.putArchiveEntry(SevenZArchiveEntry().apply {
                    this.name = name
                    lastModifiedDate = now
                })
                open().use { inp ->
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        z.write(buf, 0, n)
                        done += n
                        progress(done)
                    }
                }
                z.closeArchiveEntry()
            }
        }
    }

    /** Fehler von Commons Compress in übersetzbare Meldungen fassen. */
    private fun <T> sevenZ(block: () -> T): T = try {
        block()
    } catch (e: LocalizedIOException) {
        throw e
    } catch (e: PasswordRequiredException) {
        throw LocalizedIOException(Text(R.string.err_7z_encrypted), e)
    } catch (e: OutOfMemoryError) {
        throw LocalizedIOException(Text(R.string.err_7z_memory), e)
    } catch (e: IOException) {
        throw LocalizedIOException(Text(R.string.err_7z, e.message ?: e.javaClass.simpleName), e)
    } catch (e: RuntimeException) {
        // beschädigte Archive führen in Commons Compress teils zu Laufzeitfehlern
        throw LocalizedIOException(Text(R.string.err_7z, e.message ?: e.javaClass.simpleName), e)
    }

    /** Dateien der Formate, die ein `.sc7`-Archiv enthalten kann; die erste ist jeweils die Hauptdatei. */
    private val SC7_FORMATS = listOf(
        listOf("si5", "sg5", "sn5"),
        listOf("si4", "sg4", "sn4"),
        listOf("si3", "sg3", "sn3"),
        listOf("sci", "scg", "scn"),
        listOf("pgn"),
    )

    /**
     * Öffnet die aus einem `.sc7`-Archiv entpackte Datenbank in [dir] schreibgeschützt. Das Format ergibt sich aus
     * den Dateien (Scid 5, 4, 3, Scidb oder PGN; bei mehreren gilt diese Reihenfolge).
     */
    fun open7zExtracted(dir: File, name: String, progress: (Float) -> Unit): ChessDatabase {
        val files = dir.listFiles()?.map { it.name } ?: emptyList()
        val (exts, base) = SC7_FORMATS.firstNotNullOfOrNull { exts ->
            files.firstOrNull { it.lowercase().endsWith(".${exts[0]}") }?.let { exts to it.dropLast(exts[0].length + 1) }
        } ?: throw LocalizedIOException(R.string.err_archive_no_db)
        val main = exts[0]
        val missing = exts.filter { "$base.$it" !in files }
        if (missing.isNotEmpty()) throw LocalizedIOException(R.string.err_missing_files, missing.joinToString(", ") { "$base.$it" })
        val fs = JavaFileSet(dir, base)
        val inner = when (main) {
            "si5" -> Scid5Database.open(fs, writable = false)
            "si4" -> Scid4Database(fs)
            "si3" -> Scid4Database(fs, v3 = true)
            "sci" -> ScidbDatabase(fs)
            else -> PgnDatabase(name, RandomAccessFile(File(dir, "$base.$main"), "r").channel, progress)
        }
        return ArchiveDatabase(inner, dir, name, "7z")
    }

    private const val HEAD = "<-- H E A D -->"
    private const val DATA = "<-- D A T A -->"
    private val ATTR = Regex("<([A-Za-z]+)>[ \t]*(.*)")

    /** Liest eine Zeile bis `\n` (ohne Zeilenende); null am Dateiende. */
    private fun InputStream.readLine(): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = read()
            if (b < 0) return if (buf.size() == 0) null else decodeText(buf.toByteArray())
            if (b == '\n'.code) return decodeText(buf.toByteArray())
            buf.write(b)
        }
    }
}

/** Liest höchstens [limit] Bytes aus [inp], ohne ihn zu schließen. */
private class LimitedInputStream(inp: InputStream, private var limit: Long) : FilterInputStream(inp) {
    override fun read(): Int {
        if (limit <= 0) return -1
        val b = super.read()
        if (b < 0) throw LocalizedIOException(R.string.err_eof)
        limit--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (limit <= 0) return -1
        val n = super.read(b, off, minOf(len.toLong(), limit).toInt())
        if (n < 0) throw LocalizedIOException(R.string.err_eof)
        limit -= n
        return n
    }

    override fun close() {}
}

/** Zählt die gelesenen Bytes, z.B. für eine Fortschrittsanzeige beim Entpacken. */
class CountingInputStream(inp: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(inp) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) onRead(++count) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) { count += it; onRead(count) } }
}

/** Eine aus einem Archiv entpackte Datenbank; beim Schließen wird das Arbeitsverzeichnis gelöscht. */
class ArchiveDatabase(
    private val inner: ChessDatabase,
    private val dir: File,
    override val name: String,
    archiveFormat: String,
) : ChessDatabase by inner {
    override val formatName = "${inner.formatName}, $archiveFormat"
    override val writable get() = false

    override fun close() {
        try {
            inner.close()
        } finally {
            dir.deleteRecursively()
        }
    }
}
