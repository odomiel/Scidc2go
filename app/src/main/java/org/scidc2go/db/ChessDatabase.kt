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

import org.scidc2go.LocalizedIOException
import org.scidc2go.R
import org.scidc2go.chess.Game
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Kopfdaten einer Partie für die Listenansicht. */
data class GameHeader(
    val white: String,
    val black: String,
    val event: String,
    val site: String,
    val round: String,
    val date: String,
    val result: String,
    val whiteElo: Int,
    val blackElo: Int,
    val eco: String,
    val plies: Int,
    val deleted: Boolean = false,
)

interface ChessDatabase : Closeable {
    val name: String
    val formatName: String
    val gameCount: Int
    val writable: Boolean get() = false

    fun header(index: Int): GameHeader
    fun loadGame(index: Int): Game

    /** Fügt eine Partie an; liefert ihren Index. */
    fun addGame(game: Game): Int = throw LocalizedIOException(R.string.err_read_only)

    /** Ersetzt die Partie [index]. */
    fun replaceGame(index: Int, game: Game): Unit = throw LocalizedIOException(R.string.err_read_only)

    /** Setzt oder löscht die Löschmarkierung. */
    fun setDeleted(index: Int, deleted: Boolean): Unit = throw LocalizedIOException(R.string.err_read_only)

    /** Entfernt die Partien [indices] endgültig; die folgenden Partien rücken nach vorn. */
    fun deleteGames(indices: Collection<Int>): Unit = throw LocalizedIOException(R.string.err_read_only)
}

/**
 * Zugriff auf die Dateien einer Datenbank (z.B. name.si5, name.sg5, name.sn5).
 * Auf Android über das Storage Access Framework implementiert, in Tests über java.io.File.
 */
interface DbFileSet {
    val baseName: String
    fun exists(ext: String): Boolean
    fun openRead(ext: String): FileChannel
    /** Öffnet zum Schreiben ohne Kürzen; legt die Datei bei Bedarf an. */
    fun openWrite(ext: String): FileChannel

    /** Dateien einer anderen Datenbank [baseName] im selben Ordner. */
    fun sibling(baseName: String): DbFileSet = throw LocalizedIOException(R.string.err_no_folder_access)
    /** Benennt die vorhandenen Dateien mit den Endungen [exts] auf den Basisnamen [newBase] um. */
    fun rename(newBase: String, exts: Collection<String>): Unit = throw LocalizedIOException(R.string.err_no_folder_access)
    /** Löscht die vorhandenen Dateien mit den Endungen [exts]. */
    fun delete(exts: Collection<String>): Unit = throw LocalizedIOException(R.string.err_no_folder_access)
}

class JavaFileSet(private val dir: java.io.File, baseName: String) : DbFileSet {
    override var baseName = baseName
        private set
    private fun file(ext: String) = java.io.File(dir, "$baseName.$ext")
    override fun exists(ext: String) = file(ext).exists()
    override fun openRead(ext: String): FileChannel =
        java.io.RandomAccessFile(file(ext), "r").channel
    override fun openWrite(ext: String): FileChannel =
        java.io.RandomAccessFile(file(ext), "rw").channel
    override fun sibling(baseName: String) = JavaFileSet(dir, baseName)
    override fun rename(newBase: String, exts: Collection<String>) {
        for (ext in exts) {
            val f = file(ext)
            if (f.exists() && !f.renameTo(java.io.File(dir, "$newBase.$ext"))) throw LocalizedIOException(R.string.err_cannot_rename, f.name)
        }
        baseName = newBase
    }
    override fun delete(exts: Collection<String>) {
        for (ext in exts) file(ext).let { if (it.exists() && !it.delete()) throw LocalizedIOException(R.string.err_cannot_delete, it.name) }
    }
}

internal fun FileChannel.readFully(offset: Long, length: Int): ByteArray {
    val buf = ByteBuffer.allocate(length)
    var pos = offset
    while (buf.hasRemaining()) {
        val n = read(buf, pos)
        if (n < 0) throw LocalizedIOException(R.string.err_eof)
        pos += n
    }
    return buf.array()
}

internal fun FileChannel.writeFully(offset: Long, data: ByteArray, off: Int = 0, len: Int = data.size) {
    val buf = ByteBuffer.wrap(data, off, len)
    var pos = offset
    while (buf.hasRemaining()) pos += write(buf, pos)
}

internal fun FileChannel.readAll(): ByteArray {
    val sz = size()
    if (sz > Int.MAX_VALUE - 16) throw LocalizedIOException(R.string.err_file_too_large)
    return readFully(0, sz.toInt())
}

/** Dekodiert Text als UTF-8, bei ungültigen Sequenzen als ISO-8859-1. */
fun decodeText(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off): String {
    var ascii = true
    for (i in off until off + len) if (bytes[i] < 0) { ascii = false; break }
    if (ascii) return String(bytes, off, len, Charsets.ISO_8859_1)
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, off, len)).toString()
    } catch (e: CharacterCodingException) {
        String(bytes, off, len, Charsets.ISO_8859_1)
    }
}
