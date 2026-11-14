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
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import org.scidc2go.LocalizedIOException
import org.scidc2go.R
import org.scidc2go.db.Archives
import org.scidc2go.db.ChessDatabase
import org.scidc2go.db.CountingInputStream
import org.scidc2go.db.DbFileSet
import org.scidc2go.db.PgnDatabase
import org.scidc2go.db.Scid4Database
import org.scidc2go.db.Scid5Database
import org.scidc2go.db.ScidbDatabase
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel

/** Datenbankformat; [label] ist der Anzeigename als String-Ressource, [packable]: lässt sich als `.sc7` packen. */
enum class DbFormat(@StringRes val label: Int, val mainExt: String, val exts: List<String>, val packable: Boolean = false) {
    SCID5(R.string.fmt_scid5, "si5", listOf("si5", "sg5", "sn5"), packable = true),
    SCID4(R.string.fmt_scid4, "si4", listOf("si4", "sg4", "sn4"), packable = true),
    SCID3(R.string.fmt_scid3, "si3", listOf("si3", "sg3", "sn3"), packable = true),
    SCIDB(R.string.fmt_scidb, "sci", listOf("sci", "scg", "scn"), packable = true),
    PGN(R.string.fmt_pgn, "pgn", listOf("pgn"), packable = true),
    SCIDB_ARCHIVE(R.string.fmt_scidb_archive, "scv", listOf("scv")),
    PGN_GZ(R.string.fmt_pgn_gz, "pgn.gz", listOf("pgn.gz")),
    PGN_ZIP(R.string.fmt_pgn_zip, "zip", listOf("zip")),
    /** 7z-Archiv mit einer Datenbank, eigenes Format von Scidc2go */
    SC7(R.string.fmt_sc7, "sc7", listOf("sc7")),
}

/**
 * Eine Datenbank in einem Ordner: Basisname + Format + Dokument-URIs ihrer Dateien.
 * [folderId] ist die Dokument-ID des Ordners im freigegebenen Baum (null bei einzeln ausgewählten Dateien).
 */
data class DbEntry(
    val baseName: String,
    val format: DbFormat,
    val files: Map<String, Uri>,
    val size: Long,
    val folderId: String? = null,
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

/**
 * Dateien einer Datenbank im Ordner [folderId] eines per SAF freigegebenen Baums (null = oberster Ordner).
 * Ohne [treeUri] (einzeln ausgewählte Dateien) können keine Dateien angelegt, umbenannt oder gelöscht werden.
 */
class SafFileSet(
    private val ctx: Context,
    private val treeUri: Uri?,
    private val folderId: String?,
    baseName: String,
    files: Map<String, Uri>,
) : DbFileSet {
    override var baseName = baseName
        private set
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

    private fun tree() = treeUri ?: throw LocalizedIOException(R.string.err_grant_folder)

    private fun createFile(ext: String): Uri {
        val tree = treeUri ?: throw LocalizedIOException(R.string.err_missing_file, "$baseName.$ext")
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, folderId ?: DocumentsContract.getTreeDocumentId(tree))
        val uri = DocumentsContract.createDocument(ctx.contentResolver, parent, "application/octet-stream", "$baseName.$ext")
            ?: throw LocalizedIOException(R.string.err_cannot_create, "$baseName.$ext")
        files[ext] = uri
        return uri
    }

    override fun sibling(baseName: String): DbFileSet {
        val tree = tree()
        val folder = folderId ?: DocumentsContract.getTreeDocumentId(tree)
        val existing = SafStorage.listDatabases(ctx, tree, folder).filter { it.baseName == baseName }
            .flatMap { it.files.entries }.associate { it.key to it.value }
        return SafFileSet(ctx, tree, folder, baseName, existing)
    }

    override fun rename(newBase: String, exts: Collection<String>) {
        tree()
        for (ext in exts) {
            val uri = files[ext] ?: continue
            files[ext] = DocumentsContract.renameDocument(ctx.contentResolver, uri, "$newBase.$ext")
                ?: throw LocalizedIOException(R.string.err_cannot_rename, "$baseName.$ext")
        }
        baseName = newBase
    }

    override fun delete(exts: Collection<String>) {
        tree()
        for (ext in exts) {
            val uri = files.remove(ext) ?: continue
            if (!DocumentsContract.deleteDocument(ctx.contentResolver, uri)) throw LocalizedIOException(R.string.err_cannot_delete, "$baseName.$ext")
        }
    }
}

object SafStorage {

    /** Listet alle Datenbanken im Ordner [docId] des freigegebenen Baums (nicht rekursiv). */
    fun listDatabases(
        ctx: Context,
        treeUri: Uri,
        docId: String = DocumentsContract.getTreeDocumentId(treeUri),
    ): List<DbEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val docs = ArrayList<Triple<String, Long, Uri>>()
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
                val name = c.getString(1) ?: continue
                val size = if (c.isNull(2)) 0L else c.getLong(2)
                docs.add(Triple(name, size, DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))))
            }
        }
        return group(docs).map { it.copy(folderId = docId) }
    }

    /**
     * Sucht die Datenbank, zu der das einzeln ausgewählte Dokument [doc] gehört, in den bereits
     * freigegebenen Ordnern [trees] (auch in Unterordnern). Liefert Ordner und Datenbank, auch unvollständig.
     */
    fun findInTrees(ctx: Context, doc: Uri, trees: List<Uri>): Pair<Uri, DbEntry>? {
        val docId = DocumentsContract.getDocumentId(doc)
        for (tree in trees) {
            if (tree.authority != doc.authority) continue
            val parentId = parentInTree(ctx, tree, docId) ?: continue
            val entry = listDatabases(ctx, tree, parentId).firstOrNull { e ->
                e.files.values.any { DocumentsContract.getDocumentId(it) == docId }
            } ?: continue
            return tree to entry
        }
        return null
    }

    /** Ordner, in dem [docId] innerhalb von [tree] liegt, oder null wenn es nicht dazugehört. */
    private fun parentInTree(ctx: Context, tree: Uri, docId: String): String? {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        try {
            val path = DocumentsContract.findDocumentPath(
                ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, docId),
            )?.path
            if (path != null && path.size >= 2) return path[path.size - 2]
        } catch (_: Exception) {
            // Anbieter kennt keine Pfade oder Dokument liegt nicht im Ordner
        }
        // Ersatz: nur direkt im freigegebenen Ordner suchen
        return rootId
    }

    /** Fasst einzeln ausgewählte Dokumente zu Datenbanken zusammen (auch unvollständige). */
    fun groupDocuments(ctx: Context, uris: List<Uri>): List<DbEntry> {
        val docs = uris.mapNotNull { uri ->
            ctx.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val name = c.getString(0) ?: return@use null
                Triple(name, if (c.isNull(1)) 0L else c.getLong(1), uri)
            }
        }
        return group(docs)
    }

    /** Gruppiert (Name, Größe, URI) nach Basisname und Format. */
    private fun group(docs: List<Triple<String, Long, Uri>>): List<DbEntry> {
        // Längste Endung zuerst, damit name.pgn.gz nicht als name.pgn + gz zählt
        val exts = DbFormat.entries.flatMap { f -> f.exts.map { it to f } }.sortedByDescending { it.first.length }
        val byBase = HashMap<Pair<String, DbFormat>, MutableMap<String, Uri>>()
        val sizes = HashMap<Pair<String, DbFormat>, Long>()
        for ((name, size, uri) in docs) {
            val lower = name.lowercase()
            val (ext, format) = exts.firstOrNull { (e, _) -> lower.length > e.length + 1 && lower.endsWith(".$e") } ?: continue
            val key = name.dropLast(ext.length + 1) to format
            byBase.getOrPut(key) { HashMap() }[ext] = uri
            sizes[key] = (sizes[key] ?: 0L) + size
        }
        return byBase.map { (k, files) -> DbEntry(k.first, k.second, files, sizes[k] ?: 0L) }
            .sortedWith(compareBy({ it.baseName.lowercase() }, { it.format.ordinal }))
    }

    fun open(ctx: Context, treeUri: Uri?, entry: DbEntry, writable: Boolean, progress: (Float) -> Unit): ChessDatabase {
        val fs = SafFileSet(ctx, treeUri, entry.folderId, entry.baseName, entry.files)
        return when (entry.format) {
            DbFormat.SCID5 -> Scid5Database.open(fs, writable)
            DbFormat.SCID4 -> Scid4Database(fs)
            DbFormat.SCID3 -> Scid4Database(fs, v3 = true)
            DbFormat.SCIDB -> ScidbDatabase(fs)
            DbFormat.PGN -> PgnDatabase(entry.baseName, fs.openRead("pgn"), progress)
            DbFormat.SCIDB_ARCHIVE, DbFormat.PGN_GZ, DbFormat.PGN_ZIP, DbFormat.SC7 -> openArchive(ctx, entry, progress)
        }
    }

    /** Arbeitsverzeichnis für entpackte Archive. */
    private fun archiveDir(ctx: Context) = File(ctx.cacheDir, "archive")

    /** Löscht übrig gebliebene entpackte Archive (z.B. nach einem Absturz); nur aufrufen, solange keins offen ist. */
    fun cleanArchives(ctx: Context) {
        archiveDir(ctx).deleteRecursively()
    }

    /** Entpackt das Archiv [entry] in ein eigenes Verzeichnis im Cache und öffnet die Datenbank darin. */
    private fun openArchive(ctx: Context, entry: DbEntry, progress: (Float) -> Unit): ChessDatabase {
        val ext = entry.format.mainExt
        val uri = entry.files[ext] ?: throw FileNotFoundException("${entry.baseName}.$ext")
        val parent = archiveDir(ctx).apply { mkdirs() }
        val dir = java.nio.file.Files.createTempDirectory(parent.toPath(), "db").toFile()
        try {
            val total = entry.size.coerceAtLeast(1)
            val raw = ctx.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("${entry.baseName}.$ext")
            if (entry.format == DbFormat.SC7) {
                // 7z braucht wahlfreien Zugriff; SAF liefert nicht immer eine durchsuchbare Datei
                val tmp = File.createTempFile("archiv", ".7z", parent)
                try {
                    CountingInputStream(raw) { progress((it.toFloat() / total / 2).coerceAtMost(0.5f)) }.use { inp ->
                        tmp.outputStream().use { inp.copyTo(it, 1 shl 16) }
                    }
                    Archives.extract7z(tmp, dir) { progress(0.5f + it / 2) }
                } finally {
                    tmp.delete()
                }
                return Archives.open7zExtracted(dir, entry.baseName, progress)
            }
            CountingInputStream(raw.buffered(1 shl 16)) { progress((it.toFloat() / total).coerceAtMost(1f)) }.use { inp ->
                when (entry.format) {
                    DbFormat.SCIDB_ARCHIVE -> Archives.extractScidbArchive(inp, dir)
                    // Der Basisname stammt vom Dokumenten-Anbieter (DISPLAY_NAME) und wird hier zum lokalen Pfad
                    DbFormat.PGN_GZ -> Archives.extractGzip(inp, Archives.fileIn(dir, "${entry.baseName}.pgn"))
                    else -> if (Archives.extractZipPgn(inp, Archives.fileIn(dir, "${entry.baseName}.pgn")) == 0) {
                        throw LocalizedIOException(R.string.err_zip_no_pgn, "${entry.baseName}.zip")
                    }
                }
            }
            return Archives.openExtracted(dir, entry.baseName, ctx.getString(entry.format.label), progress)
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    fun createScid5(ctx: Context, treeUri: Uri, baseName: String): Scid5Database =
        Scid5Database.create(SafFileSet(ctx, treeUri, null, baseName, emptyMap()))

    /**
     * Komprimiert die Scid-5-Datenbank [entry] (siehe [Scid5Database.compact]) und liefert die
     * Indizes der entfernten Partien sowie den neuen Eintrag der Datenbank.
     */
    fun compactScid5(ctx: Context, treeUri: Uri, entry: DbEntry): Pair<List<Int>, DbEntry> {
        val removed = Scid5Database.compact(SafFileSet(ctx, treeUri, entry.folderId, entry.baseName, entry.files))
        val folder = entry.folderId ?: DocumentsContract.getTreeDocumentId(treeUri)
        val fresh = listDatabases(ctx, treeUri, folder).firstOrNull { it.baseName == entry.baseName && it.format == entry.format }
            ?: throw LocalizedIOException(R.string.err_not_found_after_compact, entry.baseName)
        return removed to fresh
    }

    /** Gibt es im Ordner [folderId] (null = oberster Ordner) schon Dateien der Datenbank [baseName] im Format [format]? */
    fun dbExists(ctx: Context, treeUri: Uri, folderId: String?, baseName: String, format: DbFormat): Boolean =
        listDatabases(ctx, treeUri, folderId ?: DocumentsContract.getTreeDocumentId(treeUri))
            .any { it.baseName == baseName && it.format == format }

    /**
     * Packt die Dateien der Datenbank [entry] unverändert in das 7z-Archiv `[baseName].sc7` im Ordner [folderId];
     * im Archiv heißen sie `[baseName].si5` usw. Liefert die Größe des Archivs.
     */
    fun pack7z(
        ctx: Context, treeUri: Uri, folderId: String?, entry: DbEntry, baseName: String, progress: (Float) -> Unit,
    ): Long {
        val name = "$baseName.sc7"
        // Erst im Cache packen, damit im Ordner kein halbes Archiv liegen bleibt
        val tmp = File.createTempFile("pack", ".7z", archiveDir(ctx).apply { mkdirs() })
        try {
            pack7zTo(ctx, entry, baseName, tmp, progress)
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, folderId ?: DocumentsContract.getTreeDocumentId(treeUri))
            // Nicht application/x-7z-compressed: dann hängen manche Anbieter ".7z" an den Namen an
            val uri = DocumentsContract.createDocument(ctx.contentResolver, parent, "application/octet-stream", name)
                ?: throw LocalizedIOException(R.string.err_cannot_create, name)
            try {
                val out = ctx.contentResolver.openOutputStream(uri) ?: throw LocalizedIOException(R.string.err_cannot_create, name)
                out.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }
            } catch (e: Throwable) {
                try {
                    DocumentsContract.deleteDocument(ctx.contentResolver, uri)
                } catch (_: Exception) {
                    // bleibt als unvollständige Datei liegen
                }
                throw e
            }
            return tmp.length()
        } finally {
            tmp.delete()
        }
    }

    /**
     * Konvertiert [src] in die neue Scid-5-Datenbank [baseName] im Ordner [folderId] (siehe [Scid5Database.convert]).
     * Liefert die Indizes der nicht übernommenen Partien.
     */
    fun convertToScid5(
        ctx: Context, treeUri: Uri, folderId: String?, baseName: String, src: ChessDatabase, progress: (Int) -> Unit,
    ): List<Int> = Scid5Database.convert(src, SafFileSet(ctx, treeUri, folderId, "", emptyMap()).sibling(baseName), progress)

    /** Packt die Dateien der Datenbank [entry] als `.sc7` nach [out]; im Archiv heißen sie `[baseName].si5` usw. */
    fun pack7zTo(ctx: Context, entry: DbEntry, baseName: String, out: File, progress: (Float) -> Unit) {
        require(entry.format.packable) { entry.format.name }
        val total = entry.size.coerceAtLeast(1)
        Archives.write7z(out, entry.format.exts.map { ext ->
            "$baseName.$ext" to {
                val uri = entry.files[ext] ?: throw FileNotFoundException("${entry.baseName}.$ext")
                ctx.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("${entry.baseName}.$ext")
            }
        }) { progress((it.toFloat() / total).coerceAtMost(1f)) }
    }

    /** Kopiert die Hauptdatei der Datenbank [entry] nach [out] (für Formate aus einer einzigen Datei). */
    fun copyMainFile(ctx: Context, entry: DbEntry, out: File) {
        val ext = entry.format.mainExt
        val uri = entry.files[ext] ?: throw FileNotFoundException("${entry.baseName}.$ext")
        (ctx.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("${entry.baseName}.$ext")).use { inp ->
            out.outputStream().use { inp.copyTo(it, 1 shl 16) }
        }
    }

    /** Öffnet eine einzelne PGN-Datei (z.B. aus "Öffnen mit"). */
    fun openPgn(ctx: Context, uri: Uri, name: String, progress: (Float) -> Unit): ChessDatabase =
        PgnDatabase(name, openChannel(ctx, uri, false), progress)
}
