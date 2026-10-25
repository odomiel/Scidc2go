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

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

/**
 * Gepackte Datenbanken, wie Scidb sie kennt: Scidb-Archive (`.scv`), gzip-PGN (`.pgn.gz`) und
 * ZIP-Archive mit PGN-Dateien (`.zip`). Sie werden in ein Arbeitsverzeichnis entpackt und von dort
 * schreibgeschützt geöffnet.
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
        if (inp.readLine() != "iveArch") throw IOException("Kein Scidb-Archiv")
        var line = inp.readLine()
        while (line != null && line != HEAD) line = inp.readLine()
        val names = ArrayList<String>()
        while (line != null) {
            if (line != HEAD) throw IOException("Scidb-Archiv ist beschädigt")
            val attrs = HashMap<String, String>()
            while (true) {
                line = inp.readLine() ?: throw IOException("Scidb-Archiv ist beschädigt")
                if (line == DATA) break
                ATTR.matchEntire(line)?.let { attrs[it.groupValues[1]] = it.groupValues[2].trim() }
            }
            val fileName = attrs["FileName"] ?: throw IOException("Scidb-Archiv: Dateiname fehlt")
            val size = attrs["Size"]?.toLongOrNull() ?: throw IOException("Scidb-Archiv: Größe fehlt")
            // Nur den Dateinamen übernehmen, damit nichts außerhalb von dir landet
            val name = File(fileName.replace('\\', '/')).name
            if (name.isEmpty() || name == "." || name == "..") throw IOException("Ungültiger Dateiname: $fileName")
            val stored = LimitedInputStream(inp, size)
            val crc = CRC32()
            val checked = CheckedInputStream(stored, crc)
            File(dir, name).outputStream().use { out ->
                when (val c = attrs["Compression"] ?: "raw") {
                    "raw" -> checked.copyTo(out)
                    "zlib" -> InflaterInputStream(checked).copyTo(out)
                    else -> throw IOException("$name: unbekannte Kompression $c")
                }
            }
            // Rest nach dem zlib-Ende überspringen; er zählt zur Prüfsumme
            checked.skip(Long.MAX_VALUE)
            attrs["Checksum"]?.toLongOrNull()?.let {
                if ((it and 0xFFFFFFFFL) != crc.value) throw IOException("$name: Prüfsumme falsch")
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
            ?: throw IOException("Das Archiv enthält keine lesbare Datenbank")
        return ArchiveDatabase(inner, dir, name, archiveFormat)
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
        if (b < 0) throw IOException("Unerwartetes Dateiende")
        limit--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (limit <= 0) return -1
        val n = super.read(b, off, minOf(len.toLong(), limit).toInt())
        if (n < 0) throw IOException("Unerwartetes Dateiende")
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
