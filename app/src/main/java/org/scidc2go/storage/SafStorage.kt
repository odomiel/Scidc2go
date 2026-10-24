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
package org.scidc2go.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import org.scidc2go.db.ChessDatabase
import org.scidc2go.db.DbFileSet
import org.scidc2go.db.PgnDatabase
import org.scidc2go.db.Scid4Database
import org.scidc2go.db.Scid5Database
import org.scidc2go.db.ScidbDatabase
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel

enum class DbFormat(val label: String, val mainExt: String, val exts: List<String>) {
    SCID5("Scid 5", "si5", listOf("si5", "sg5", "sn5")),
    SCID4("Scid 4", "si4", listOf("si4", "sg4", "sn4")),
    SCID3("Scid 3", "si3", listOf("si3", "sg3", "sn3")),
    SCIDB("Scidb", "sci", listOf("sci", "scg", "scn")),
    PGN("PGN", "pgn", listOf("pgn")),
}

/** Eine Datenbank in einem Ordner: Basisname + Format + Dokument-URIs ihrer Dateien. */
data class DbEntry(
    val baseName: String,
    val format: DbFormat,
    val files: Map<String, Uri>,
    val size: Long,
) {
    val complete get() = format.exts.all { files.containsKey(it) }
}

private fun openChannel(ctx: Context, uri: Uri, write: Boolean): FileChannel {
    val pfd = ctx.contentResolver.openFileDescriptor(uri, if (write) "rw" else "r")
        ?: throw FileNotFoundException(uri.toString())
    return if (write) {
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).channel
    } else {
        ParcelFileDescriptor.AutoCloseInputStream(pfd).channel
    }
}

/** Dateien einer Datenbank in einem per SAF freigegebenen Ordner. */
class SafFileSet(
    private val ctx: Context,
    private val treeUri: Uri,
    override val baseName: String,
    files: Map<String, Uri>,
) : DbFileSet {
    private val files = files.toMutableMap()

    override fun exists(ext: String) = files.containsKey(ext)

    override fun openRead(ext: String): FileChannel {
        val uri = files[ext] ?: throw FileNotFoundException("$baseName.$ext")
        return openChannel(ctx, uri, false)
    }

    override fun openWrite(ext: String): FileChannel {
        val uri = files[ext] ?: createFile(ext)
        return openChannel(ctx, uri, true)
    }

    private fun createFile(ext: String): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val uri = DocumentsContract.createDocument(ctx.contentResolver, parent, "application/octet-stream", "$baseName.$ext")
            ?: throw IOException("Kann $baseName.$ext nicht anlegen")
        files[ext] = uri
        return uri
    }
}

object SafStorage {

    /** Listet alle Datenbanken im Ordner (nicht rekursiv). */
    fun listDatabases(ctx: Context, treeUri: Uri): List<DbEntry> {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val byBase = HashMap<Pair<String, DbFormat>, MutableMap<String, Uri>>()
        val sizes = HashMap<Pair<String, DbFormat>, Long>()
        val extToFormat = HashMap<String, DbFormat>()
        for (f in DbFormat.entries) for (e in f.exts) extToFormat[e] = f
        ctx.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                val size = if (c.isNull(2)) 0L else c.getLong(2)
                val dot = name.lastIndexOf('.')
                if (dot <= 0) continue
                val ext = name.substring(dot + 1).lowercase()
                val format = extToFormat[ext] ?: continue
                val key = name.substring(0, dot) to format
                byBase.getOrPut(key) { HashMap() }[ext] = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                sizes[key] = (sizes[key] ?: 0L) + size
            }
        }
        return byBase.map { (k, files) -> DbEntry(k.first, k.second, files, sizes[k] ?: 0L) }
            .filter { it.complete }
            .sortedWith(compareBy({ it.baseName.lowercase() }, { it.format.ordinal }))
    }

    fun open(ctx: Context, treeUri: Uri, entry: DbEntry, writable: Boolean, progress: (Float) -> Unit): ChessDatabase {
        val fs = SafFileSet(ctx, treeUri, entry.baseName, entry.files)
        return when (entry.format) {
            DbFormat.SCID5 -> Scid5Database.open(fs, writable)
            DbFormat.SCID4 -> Scid4Database(fs)
            DbFormat.SCID3 -> Scid4Database(fs, v3 = true)
            DbFormat.SCIDB -> ScidbDatabase(fs)
            DbFormat.PGN -> PgnDatabase(entry.baseName, fs.openRead("pgn"), progress)
        }
    }

    fun createScid5(ctx: Context, treeUri: Uri, baseName: String): Scid5Database =
        Scid5Database.create(SafFileSet(ctx, treeUri, baseName, emptyMap()))

    /** Öffnet eine einzelne PGN-Datei (z.B. aus "Öffnen mit"). */
    fun openPgn(ctx: Context, uri: Uri, name: String, progress: (Float) -> Unit): ChessDatabase =
        PgnDatabase(name, openChannel(ctx, uri, false), progress)
}
