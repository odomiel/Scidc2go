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
package org.scidc2go.ui

import android.app.Application
import android.app.LocaleManager
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.scidc2go.AppLog
import org.scidc2go.LocalizedIOException
import org.scidc2go.R
import org.scidc2go.chess.Game
import org.scidc2go.chess.Move
import org.scidc2go.chess.Node
import org.scidc2go.chess.Openings
import org.scidc2go.chess.PAWN
import org.scidc2go.chess.Pgn
import org.scidc2go.chess.Position
import org.scidc2go.chess.WHITE
import org.scidc2go.chess.parseSquare
import org.scidc2go.chess.rankOf
import org.scidc2go.chess.typeOf
import org.scidc2go.db.ChessDatabase
import org.scidc2go.db.Scid5Database
import org.scidc2go.engine.EngineLine
import org.scidc2go.engine.OexEngine
import org.scidc2go.engine.OexEngines
import org.scidc2go.engine.StockfishNet
import org.scidc2go.engine.UciEngine
import org.scidc2go.storage.DbEntry
import org.scidc2go.storage.DbFormat
import org.scidc2go.storage.SafStorage
import org.scidc2go.userMessage
import java.io.File

enum class Screen { FILES, GAMES, BOARD }

enum class ThemeMode(@StringRes val label: Int) { SYSTEM(R.string.theme_system), LIGHT(R.string.theme_light), DARK(R.string.theme_dark) }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val prefs = app.getSharedPreferences("scidc2go", 0)

    private fun str(@StringRes id: Int, vararg args: Any): String = ctx.getString(id, *args)
    private fun plural(@PluralsRes id: Int, n: Int, vararg args: Any): String =
        ctx.resources.getQuantityString(id, n, *args)

    var screen by mutableStateOf(Screen.FILES)
    var themeMode by mutableStateOf(
        ThemeMode.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: ThemeMode.SYSTEM,
    )
        private set
    var folderUri by mutableStateOf<Uri?>(prefs.getString("folder", null)?.let(Uri::parse))
        private set
    var dbEntries by mutableStateOf<List<DbEntry>>(emptyList())
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    /** Meldung für die Oberfläche; jede Meldung kommt auch ins Protokoll. */
    var message: String?
        get() = messageState.value
        set(value) {
            if (value != null) AppLog.i("Meldung", value)
            messageState.value = value
        }
    private val messageState = mutableStateOf<String?>(null)

    // Datenbank
    var db by mutableStateOf<ChessDatabase?>(null)
        private set
    private var dbEntry: DbEntry? = null
    /** Dateien der offenen Datenbank zum Teilen; anders als [dbEntry] auch bei einzeln geöffneten Dateien. */
    private var dbSource: DbEntry? = null
    /** Freigegebener Ordner, aus dem [dbEntry] stammt. */
    private var dbTree: Uri? = null
    var filterText by mutableStateOf("")
        private set
    var filtered by mutableStateOf(IntArray(0))
        private set
    var listVersion by mutableIntStateOf(0)
        private set
    private var searchJob: Job? = null

    // Partie
    var game by mutableStateOf<Game?>(null)
        private set
    var gameIndex by mutableIntStateOf(-1)
        private set
    var node by mutableStateOf<Node?>(null)
        private set
    var treeVersion by mutableIntStateOf(0)
        private set
    var dirty by mutableStateOf(false)
        private set
    var flipped by mutableStateOf(false)
    /** Eröffnungstabelle, im Hintergrund geladen; bis dahin null. */
    var openings by mutableStateOf<Openings?>(null)
        private set

    // Engine
    var engineOn by mutableStateOf(false)
        private set
    var engineLines by mutableStateOf<List<EngineLine>>(emptyList())
        private set
    var engineName by mutableStateOf("Stockfish")
        private set
    var multiPv by mutableIntStateOf(prefs.getInt("multipv", 2))
        private set
    private var engine: UciEngine? = null
    /** Gewählte Engine: leer für das mitgelieferte Stockfish, sonst [OexEngine.id]. */
    var engineId by mutableStateOf(prefs.getString("engine", "") ?: "")
        private set
    /** Engines aus installierten Engine-Apps, aktualisiert mit [refreshEngines]. */
    var oexEngines by mutableStateOf<List<OexEngine>>(emptyList())
        private set

    init {
        // logDetailed ist hier noch nicht initialisiert (steht weiter unten)
        AppLog.detailed = prefs.getBoolean("logDetailed", false)
        SafStorage.cleanArchives(ctx)
        refreshEngines()
        loadOpenings()
        folderUri?.let { refreshFolder() }
        newGame()
    }

    private fun loadOpenings() {
        viewModelScope.launch {
            try {
                openings = withContext(Dispatchers.IO) { ctx.assets.open("openings.tsv").use { Openings.load(it) } }
            } catch (e: Exception) {
                AppLog.e("Eröffnungen", "Tabelle nicht lesbar", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Ordner und Datenbanken

    fun setFolder(uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
        folderUri = uri
        prefs.edit { putString("folder", uri.toString()) }
        refreshFolder()
    }

    fun refreshFolder() {
        val uri = folderUri ?: return
        viewModelScope.launch {
            try {
                dbEntries = withContext(Dispatchers.IO) { SafStorage.listDatabases(ctx, uri).filter { it.complete } }
            } catch (e: Exception) {
                AppLog.e("Ordner", "Liste nicht lesbar", e)
                message = str(R.string.err_folder_unreadable, e.userMessage(ctx))
            }
        }
    }

    private fun runBusy(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = label
            AppLog.i("Aktion", label)
            try {
                block()
            } catch (e: Exception) {
                AppLog.e("Fehler", label, e)
                message = e.userMessage(ctx)
            } catch (e: OutOfMemoryError) {
                // z.B. eine beschädigte oder riesige Datei; die App läuft weiter, der Speicher ist wieder frei
                AppLog.e("Fehler", label, e)
                message = str(R.string.err_out_of_memory)
            } finally {
                busy = null
            }
        }
    }

    fun openDatabase(entry: DbEntry) {
        val uri = folderUri ?: return
        runBusy(str(R.string.busy_opening, entry.baseName)) {
            val newDb = withContext(Dispatchers.IO) {
                SafStorage.open(ctx, uri, entry, writable = entry.format == DbFormat.SCID5) { p ->
                    busy = str(R.string.busy_reading, entry.baseName, (p * 100).toInt())
                }
            }
            setDatabase(newDb, entry, uri)
        }
    }

    fun openPgnUri(uri: Uri) {
        runBusy(str(R.string.busy_opening_pgn)) {
            val name = queryName(uri) ?: "PGN"
            val newDb = withContext(Dispatchers.IO) {
                SafStorage.openPgn(ctx, uri, name.removeSuffix(".pgn")) { p ->
                    busy = str(R.string.busy_reading, name, (p * 100).toInt())
                }
            }
            setDatabase(newDb, null, null)
        }
    }

    /**
     * Scid-/Scidb-Datei aus einem noch nicht freigegebenen Ordner: Die Oberfläche fragt dann einmal
     * nach dem Ordner, damit die übrigen Dateien gelesen werden können.
     */
    var siblingRequest by mutableStateOf<DbEntry?>(null)
        private set

    /**
     * Öffnet einzeln ausgewählte Dateien. Bei Scid und Scidb genügt eine Datei der Datenbank;
     * die übrigen werden in den bereits freigegebenen Ordnern gesucht.
     */
    fun openDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        runBusy(str(R.string.busy_opening_generic)) {
            val entries = withContext(Dispatchers.IO) { SafStorage.groupDocuments(ctx, uris) }
            entries.firstOrNull { it.complete }?.let { openEntry(null, it); return@runBusy }
            val e = entries.firstOrNull() ?: run { message = str(R.string.err_unknown_format); return@runBusy }
            val found = withContext(Dispatchers.IO) {
                SafStorage.findInTrees(ctx, e.files.values.first(), grantedTrees())
            }
            if (found == null) siblingRequest = e else openFound(found.first, found.second)
        }
    }

    fun cancelSiblingRequest() { siblingRequest = null }

    /** Übernimmt [tree] als Datenbank-Ordner und öffnet die zuvor ausgewählte Datenbank daraus. */
    fun openFromFolder(tree: Uri?) {
        val want = siblingRequest ?: return
        siblingRequest = null
        if (tree == null) return
        setFolder(tree)
        runBusy(str(R.string.busy_opening, want.baseName)) {
            val found = withContext(Dispatchers.IO) {
                SafStorage.findInTrees(ctx, want.files.values.first(), listOf(tree))
            }
            if (found == null) {
                message = str(R.string.err_not_in_folder, want.baseName)
                return@runBusy
            }
            openFound(found.first, found.second)
        }
    }

    /** Öffnet eine im Ordner gefundene Datenbank oder meldet, welche Dateien fehlen. */
    private suspend fun openFound(tree: Uri, entry: DbEntry) {
        if (!entry.complete) {
            message = str(R.string.err_missing_files,
                entry.format.exts.filter { it !in entry.files }.joinToString(", ") { "${entry.baseName}.$it" })
            return
        }
        openEntry(tree, entry)
    }

    /** Ordner, für die die App eine dauerhafte Lesefreigabe hat. */
    private fun grantedTrees(): List<Uri> =
        ctx.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .map { it.uri }

    private suspend fun openEntry(tree: Uri?, entry: DbEntry) {
        val newDb = withContext(Dispatchers.IO) {
            SafStorage.open(ctx, tree, entry, writable = entry.format == DbFormat.SCID5) { p ->
                busy = str(R.string.busy_reading, entry.baseName, (p * 100).toInt())
            }
        }
        setDatabase(newDb, if (tree != null) entry else null, tree, source = entry)
    }

    private fun queryName(uri: Uri): String? =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun setDatabase(newDb: ChessDatabase, entry: DbEntry?, tree: Uri?, source: DbEntry? = entry) {
        db?.close()
        db = newDb
        dbEntry = entry
        dbSource = source
        dbTree = tree
        filterText = ""
        filtered = IntArray(newDb.gameCount) { it }
        listVersion++
        screen = Screen.GAMES
    }

    fun closeDatabase() {
        db?.close()
        db = null
        dbEntry = null
        dbSource = null
        dbTree = null
        filtered = IntArray(0)
        screen = Screen.FILES
    }

    fun createScid5(name: String) {
        val uri = folderUri ?: run { message = str(R.string.err_choose_folder_first); return }
        val clean = name.trim().removeSuffix(".si5")
        if (clean.isEmpty()) return
        runBusy(str(R.string.busy_creating, clean)) {
            withContext(Dispatchers.IO) { SafStorage.createScid5(ctx, uri, clean).close() }
            dbEntries = withContext(Dispatchers.IO) { SafStorage.listDatabases(ctx, uri).filter { it.complete } }
            dbEntries.firstOrNull { it.baseName == clean && it.format == DbFormat.SCID5 }?.let { openDatabase(it) }
        }
    }

    fun search(text: String) {
        filterText = text
        val d = db ?: return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val q = text.trim().lowercase()
            val res = withContext(Dispatchers.Default) {
                if (q.isEmpty()) IntArray(d.gameCount) { it } else {
                    val terms = q.split(Regex("\\s+"))
                    val out = ArrayList<Int>()
                    for (i in 0 until d.gameCount) {
                        if (i % 4096 == 0) kotlinx.coroutines.yield()
                        val h = d.header(i)
                        val hay = "${h.white} ${h.black} ${h.event} ${h.site} ${h.date} ${h.eco} ${h.round}".lowercase()
                        if (terms.all { hay.contains(it) }) out.add(i)
                    }
                    out.toIntArray()
                }
            }
            filtered = res
            listVersion++
        }
    }

    // ------------------------------------------------------------------
    // Partie

    fun newGame() {
        val g = Game()
        game = g
        gameIndex = -1
        node = g.root
        dirty = false
        treeVersion++
        analyzeCurrent()
    }

    fun openGame(index: Int) {
        val d = db ?: return
        runBusy(str(R.string.busy_loading_game)) {
            val g = withContext(Dispatchers.IO) { d.loadGame(index) }
            game = g
            gameIndex = index
            node = g.root
            dirty = false
            treeVersion++
            screen = Screen.BOARD
            analyzeCurrent()
        }
    }

    fun goTo(n: Node) {
        node = n
        analyzeCurrent()
    }

    fun back() { node?.parent?.let { goTo(it) } }
    fun forward() { node?.children?.firstOrNull()?.let { goTo(it) } }
    fun toStart() { game?.let { goTo(it.root) } }
    fun toEnd() {
        var n = node ?: return
        while (n.children.isNotEmpty()) n = n.children[0]
        goTo(n)
    }

    /** Liefert die möglichen Züge vom Feld [from] nach [to] (mehrere bei Umwandlungen). */
    fun candidateMoves(from: Int, to: Int): List<Move> {
        val pos = node?.pos ?: return emptyList()
        return pos.legalMoves().filter {
            (it.from == from && it.to == to) ||
                (it.castle != 0 && it.from == from && pos.castleRook.getOrNull(pos.toMove * 2 + if (it.castle > 0) 1 else 0) == to)
        }
    }

    fun legalTargets(from: Int): Set<Int> {
        val pos = node?.pos ?: return emptySet()
        return pos.legalMoves().filter { it.from == from }.map { it.to }.toSet()
    }

    fun playMove(m: Move) {
        val n = node ?: return
        val existing = n.children.firstOrNull { it.move == m }
        if (existing != null) {
            goTo(existing)
            return
        }
        val child = n.addChild(m)
        dirty = true
        treeVersion++
        goTo(child)
    }

    /** Spielt einen Zug in UCI-Notation (z.B. aus der Engine-Variante). */
    fun playUci(uci: String) {
        val pos = node?.pos ?: return
        uciToMove(pos, uci)?.let { playMove(it) }
    }

    /**
     * Fügt die Engine-Variante [line] ab dem aktuellen Knoten in die Partie ein; Bewertung und Tiefe kommen als
     * Kommentar an ihr Ende. Der aktuelle Knoten bleibt, damit die Analyse weiterläuft.
     */
    fun addEngineLine(line: EngineLine) {
        val n = node ?: return
        var pos = n.pos
        val moves = ArrayList<Move>()
        for (u in line.pv) {
            val m = uciToMove(pos, u) ?: break
            moves.add(m)
            pos = pos.copy().also { it.play(m) }
        }
        if (moves.isEmpty()) return
        val (last, created) = n.addLine(moves)
        if (!created) { message = str(R.string.err_line_exists); return }
        if (last.comment.isEmpty()) last.comment = "${line.scoreText()}/${line.depth}"
        dirty = true
        treeVersion++
        message = str(R.string.engine_line_added)
    }

    /** Anfang der Variante, in der [n] liegt (null = Hauptvariante). */
    private fun variationStart(n: Node): Node? {
        var v = n
        while (v.parent != null && v.parent.children.indexOf(v) == 0) v = v.parent
        return if (v.parent == null) null else v
    }

    fun deleteVariation(n: Node) {
        val v = variationStart(n) ?: run { message = str(R.string.err_main_line); return }
        val p = v.parent!!
        p.children.remove(v)
        dirty = true
        treeVersion++
        goTo(p)
    }

    /** Löscht alle Züge ab dem aktuellen Knoten (inklusive). */
    fun truncateFrom(n: Node) {
        val p = n.parent ?: return
        p.children.remove(n)
        dirty = true
        treeVersion++
        goTo(p)
    }

    fun promoteVariation(n: Node) {
        val v = variationStart(n) ?: return
        val p = v.parent!!
        p.children.remove(v)
        p.children.add(0, v)
        dirty = true
        treeVersion++
    }

    fun setComment(n: Node, text: String) {
        n.comment = text.trim()
        dirty = true
        treeVersion++
    }

    fun toggleNag(n: Node, nag: Int) {
        if (n.isRoot) return
        if (nag in n.nags) n.nags.remove(nag) else {
            // Zugbewertungen (1-6) schließen sich gegenseitig aus
            if (nag in 1..6) n.nags.removeAll { it in 1..6 }
            n.nags.add(nag)
        }
        dirty = true
        treeVersion++
    }

    fun updateTags(tags: Map<String, String>) {
        val g = game ?: return
        for ((k, v) in tags) {
            if (v.isBlank() && k !in Game.STR_TAGS) g.tags.remove(k) else g.tags[k] = v.trim()
        }
        dirty = true
        treeVersion++
    }

    val writableTargets get() = dbEntries.filter { it.format == DbFormat.SCID5 }
    val canSaveInPlace get() = db?.writable == true

    /** Speichert in die geöffnete Scid-5-Datenbank (ersetzen oder anhängen). */
    fun save(asNew: Boolean = false) {
        val d = db ?: return
        val g = game ?: return
        if (!d.writable) { message = str(R.string.err_read_only); return }
        runBusy(str(R.string.busy_saving)) {
            val idx = withContext(Dispatchers.IO) {
                if (gameIndex >= 0 && !asNew) { d.replaceGame(gameIndex, g); gameIndex } else d.addGame(g)
            }
            gameIndex = idx
            dirty = false
            if (filterText.isEmpty()) filtered = IntArray(d.gameCount) { it } else search(filterText)
            listVersion++
            message = str(R.string.saved, idx + 1)
        }
    }

    /** Speichert die Partie als neue Partie in eine andere Scid-5-Datenbank. */
    fun saveTo(entry: DbEntry) {
        val uri = folderUri ?: return
        val g = game ?: return
        if (entry == dbEntry && db?.writable == true) { save(asNew = true); return }
        runBusy(str(R.string.busy_saving_in, entry.baseName)) {
            val idx = withContext(Dispatchers.IO) {
                SafStorage.open(ctx, uri, entry, writable = true) {}.use { it.addGame(g) }
            }
            message = str(R.string.saved_in, entry.baseName, idx + 1)
            refreshFolder()
        }
    }

    /** Legt eine neue Scid-5-Datenbank an und speichert die Partie hinein. */
    fun createAndSave(name: String) {
        val uri = folderUri ?: return
        val g = game ?: return
        val clean = name.trim().removeSuffix(".si5")
        if (clean.isEmpty()) return
        runBusy(str(R.string.busy_creating, clean)) {
            withContext(Dispatchers.IO) { SafStorage.createScid5(ctx, uri, clean).use { it.addGame(g) } }
            message = str(R.string.saved_in_new, clean)
            dbEntries = withContext(Dispatchers.IO) { SafStorage.listDatabases(ctx, uri).filter { it.complete } }
        }
    }

    fun toggleDeleted(index: Int) {
        val d = db ?: return
        if (!d.writable) { message = str(R.string.err_read_only); return }
        runBusy(str(R.string.busy_mark)) {
            withContext(Dispatchers.IO) { d.setDeleted(index, !d.header(index).deleted) }
            listVersion++
        }
    }

    /** Partien, die nach Rückfrage endgültig gelöscht werden sollen (leer = keine Rückfrage offen). */
    var deleteRequest by mutableStateOf<List<Int>>(emptyList())
        private set

    fun requestDelete(indices: List<Int>) {
        val d = db ?: return
        if (!d.writable) { message = str(R.string.err_read_only); return }
        deleteRequest = indices
    }

    /** Fragt nach, ob alle zum Löschen markierten Partien gelöscht werden sollen. */
    fun requestDeleteMarked() {
        val d = db ?: return
        runBusy(str(R.string.busy_find_marked)) {
            val marked = withContext(Dispatchers.IO) { (0 until d.gameCount).filter { d.header(it).deleted } }
            if (marked.isEmpty()) message = str(R.string.none_marked) else requestDelete(marked)
        }
    }

    fun cancelDelete() { deleteRequest = emptyList() }

    fun confirmDelete() {
        val d = db ?: return
        val indices = deleteRequest
        deleteRequest = emptyList()
        if (indices.isEmpty()) return
        runBusy(plural(R.plurals.busy_deleting, indices.size, indices.size)) {
            withContext(Dispatchers.IO) { d.deleteGames(indices) }
            gamesRemoved(indices)
            message = plural(R.plurals.deleted, indices.size, indices.size)
            search(filterText)
            refreshFolder()
        }
    }

    /** Die offene Partie gehört nach dem Entfernen von [indices] nicht mehr zur Datenbank oder rückt nach vorn. */
    private fun gamesRemoved(indices: Collection<Int>) {
        if (gameIndex in indices) {
            gameIndex = -1
            dirty = true
        } else if (gameIndex >= 0) {
            gameIndex -= indices.count { it < gameIndex }
        }
    }

    /** Anzahl der markierten Partien, wenn eine Rückfrage zum Komprimieren offen ist (sonst null). */
    var compactRequest by mutableStateOf<Int?>(null)
        private set

    /** Komprimieren geht nur bei Scid 5 aus einem freigegebenen Ordner. */
    val canCompact get() = db?.writable == true && dbEntry?.format == DbFormat.SCID5 && dbTree != null

    fun requestCompact() {
        val d = db ?: return
        if (!canCompact) {
            message = str(R.string.err_compact_only)
            return
        }
        runBusy(str(R.string.busy_counting)) {
            compactRequest = withContext(Dispatchers.IO) { (0 until d.gameCount).count { d.header(it).deleted } }
        }
    }

    fun cancelCompact() { compactRequest = null }

    fun confirmCompact() {
        compactRequest = null
        val d = db ?: return
        val entry = dbEntry ?: return
        val tree = dbTree ?: return
        runBusy(str(R.string.busy_compacting, entry.baseName)) {
            val before = entry.size
            d.close()
            val (removed, fresh) = try {
                withContext(Dispatchers.IO) { SafStorage.compactScid5(ctx, tree, entry) }
            } catch (e: Exception) {
                // Original oder Kopie liegt vollständig im Ordner; die Liste zeigt, was vorhanden ist
                closeDatabase()
                refreshFolder()
                throw e
            }
            val newDb = withContext(Dispatchers.IO) { SafStorage.open(ctx, tree, fresh, writable = true) {} }
            db = newDb
            dbEntry = fresh
            dbSource = fresh
            gamesRemoved(removed)
            message = str(R.string.compacted, formatBytes(before), formatBytes(fresh.size)) +
                if (removed.isNotEmpty()) plural(R.plurals.compacted_removed, removed.size, removed.size) else ""
            search(filterText)
            refreshFolder()
        }
    }

    /** Vorgeschlagener Name, wenn eine Rückfrage zum Konvertieren nach Scid 5 offen ist (sonst null). */
    var convertRequest by mutableStateOf<String?>(null)
        private set

    /** Ziel der Konvertierung: Ordner der Datenbank, sonst der gewählte Datenbank-Ordner. */
    private fun convertTarget(): Pair<Uri, String?>? =
        dbTree?.let { it to dbEntry?.folderId } ?: folderUri?.let { it to null }

    val canConvert get() = db != null && db !is Scid5Database && convertTarget() != null

    fun requestConvert() {
        val d = db ?: return
        val (tree, folder) = convertTarget() ?: run {
            message = str(R.string.err_folder_for_convert)
            return
        }
        runBusy(str(R.string.busy_check_name)) {
            val base = d.name.removeSuffix(".pgn")
            convertRequest = withContext(Dispatchers.IO) {
                generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }
                    .first { !SafStorage.dbExists(ctx, tree, folder, it, DbFormat.SCID5) }
            }
        }
    }

    fun cancelConvert() { convertRequest = null }

    fun confirmConvert(name: String) {
        convertRequest = null
        val d = db ?: return
        val (tree, folder) = convertTarget() ?: return
        val clean = name.trim().removeSuffix(".si5")
        if (clean.isEmpty()) return
        runBusy(str(R.string.busy_converting)) {
            val total = d.gameCount
            val failed = withContext(Dispatchers.IO) {
                if (SafStorage.dbExists(ctx, tree, folder, clean, DbFormat.SCID5)) throw LocalizedIOException(R.string.err_exists, clean)
                SafStorage.convertToScid5(ctx, tree, folder, clean, d) { n ->
                    if (n % 50 == 0 || n == total) busy = str(R.string.busy_converting_to, clean, n, total)
                }
            }
            val done = total - failed.size
            message = plural(R.plurals.converted, done, clean, done) +
                if (failed.isNotEmpty()) {
                    plural(R.plurals.converted_failed, failed.size, failed.size,
                        failed.take(5).joinToString { "${it + 1}" } + if (failed.size > 5) " …" else "")
                } else ""
            refreshFolder()
        }
    }

    /** Vorgeschlagener Name, wenn eine Rückfrage zum Packen als 7z-Archiv offen ist (sonst null). */
    var packRequest by mutableStateOf<String?>(null)
        private set

    /** Packen geht bei Datenbanken aus einem freigegebenen Ordner, die selbst kein Archiv sind. */
    val canPack get() = db != null && dbEntry?.format?.packable == true && dbTree != null

    fun requestPack() {
        val entry = dbEntry ?: return
        val tree = dbTree ?: return
        runBusy(str(R.string.busy_check_name)) {
            packRequest = withContext(Dispatchers.IO) {
                generateSequence(1) { it + 1 }.map { if (it == 1) entry.baseName else "${entry.baseName}-$it" }
                    .first { !SafStorage.dbExists(ctx, tree, entry.folderId, it, DbFormat.SC7) }
            }
        }
    }

    fun cancelPack() { packRequest = null }

    fun confirmPack(name: String) {
        packRequest = null
        val entry = dbEntry ?: return
        val tree = dbTree ?: return
        val clean = name.trim().removeSuffix(".sc7")
        if (clean.isEmpty()) return
        val file = "$clean.sc7"
        runBusy(str(R.string.busy_packing, file, 0)) {
            val size = withContext(Dispatchers.IO) {
                if (SafStorage.dbExists(ctx, tree, entry.folderId, clean, DbFormat.SC7)) throw LocalizedIOException(R.string.err_exists, file)
                SafStorage.pack7z(ctx, tree, entry.folderId, entry, clean) { p ->
                    busy = str(R.string.busy_packing, file, (p * 100).toInt())
                }
            }
            message = str(R.string.packed, file, formatBytes(entry.size), formatBytes(size))
            refreshFolder()
        }
    }

    // ------------------------------------------------------------------
    // Teilen

    /** Intent zum Teilen; die Oberfläche startet ihn und ruft dann [shareDone] auf. */
    var shareIntent by mutableStateOf<Intent?>(null)
        private set

    fun shareDone() { shareIntent = null }

    /** Leeres Verzeichnis für die zu teilenden Dateien; die Dateien vom letzten Teilen werden dabei gelöscht. */
    private fun shareDir() = File(ctx.cacheDir, "share").apply { deleteRecursively(); mkdirs() }

    /** Teilen-Dialog für [file] (über den FileProvider) und/oder [text]. */
    private fun chooser(mime: String, subject: String, file: File?, text: String?): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_SUBJECT, subject)
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
            if (file != null) {
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.share", file)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return Intent.createChooser(send, str(R.string.share))
    }

    /** Ersetzt Zeichen, die in Dateinamen nicht erlaubt sind. */
    private fun fileName(s: String) = s.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().take(80)

    /** Teilt die aktuelle Partie als PGN-Datei und als Text. */
    fun shareGame() {
        val g = game ?: return
        runBusy(str(R.string.busy_sharing, "PGN")) {
            val text = Pgn.write(g)
            val title = listOf(g.white, g.black).filter { it.isNotBlank() && it != "?" }.joinToString(" – ")
            val name = fileName(title.replace(" – ", "-")).ifEmpty { "game" }
            val file = withContext(Dispatchers.IO) { File(shareDir(), "$name.pgn").apply { writeText(text) } }
            // Sehr lange Partien nur als Datei, der Text passt sonst nicht sicher in den Intent
            shareIntent = chooser("application/x-chess-pgn", title.ifEmpty { name }, file, text.takeIf { it.length < 100_000 })
        }
    }

    /** Teilt [fen] als Text. */
    fun shareFen(fen: String) {
        shareIntent = chooser("text/plain", "FEN", null, fen)
    }

    /** Teilen geht für Datenbanken aus Dateien (nicht für über „Öffnen mit“ übergebene PGN-Dateien). */
    val canShareDb get() = db != null && dbSource != null

    /** Teilt die Datenbank: Formate aus mehreren Dateien als `.sc7`-Archiv, sonst die Datei selbst. */
    fun shareDatabase() {
        val entry = dbSource ?: return
        runBusy(str(R.string.busy_sharing, entry.baseName)) {
            val (file, mime) = withContext(Dispatchers.IO) {
                val dir = shareDir()
                if (entry.format.exts.size > 1) {
                    val out = File(dir, "${fileName(entry.baseName)}.sc7")
                    SafStorage.pack7zTo(ctx, entry, entry.baseName, out) { p ->
                        busy = str(R.string.busy_packing, out.name, (p * 100).toInt())
                    }
                    out to "application/x-7z-compressed"
                } else {
                    val out = File(dir, "${fileName(entry.baseName)}.${entry.format.mainExt}")
                    SafStorage.copyMainFile(ctx, entry, out)
                    out to when (entry.format) {
                        DbFormat.PGN -> "application/x-chess-pgn"
                        DbFormat.PGN_GZ -> "application/gzip"
                        DbFormat.PGN_ZIP -> "application/zip"
                        DbFormat.SC7 -> "application/x-7z-compressed"
                        else -> "application/octet-stream"
                    }
                }
            }
            shareIntent = chooser(mime, file.name, file, null)
        }
    }

    private fun formatBytes(b: Long) = formatSize(b, ctx.resources.configuration.locales[0])

    fun pgnText(): String = game?.let { Pgn.write(it) } ?: ""

    fun importPgnText(text: String) {
        try {
            val g = Pgn.parseGame(text)
            game = g
            gameIndex = -1
            node = g.root
            dirty = true
            treeVersion++
            screen = Screen.BOARD
            analyzeCurrent()
        } catch (e: Exception) {
            AppLog.w("PGN", "Einfügen fehlgeschlagen", e)
            message = str(R.string.err_invalid_pgn, e.userMessage(ctx))
        }
    }

    // ------------------------------------------------------------------
    // Engine

    fun toggleEngine() {
        if (engineOn) {
            engine?.quit()
            engine = null
            engineOn = false
            engineLines = emptyList()
            return
        }
        val oex = selectedOex()
        if (engineId.isNotEmpty() && oex == null) {
            message = str(R.string.err_engine_missing)
            selectEngine("")
        }
        if (oex == null && !StockfishNet.file(ctx.filesDir).exists()) {
            netRequest = true
            return
        }
        runBusy(str(R.string.busy_starting, oex?.name ?: "Stockfish")) {
            val e = withContext(Dispatchers.IO) { startEngine(oex) }
            engine = e
            engineOn = true
            engineName = oex?.name ?: "Stockfish"
            if (e.exited) engineExited(e, oex)
            analyzeCurrent()
        }
    }

    /** Das Stockfish-Netz fehlt noch: Die Oberfläche fragt, ob es geladen werden soll. */
    var netRequest by mutableStateOf(false)
        private set

    fun cancelNetRequest() { netRequest = false }

    /** Lädt das Stockfish-Netz und startet danach die Engine. */
    fun downloadNet() {
        netRequest = false
        runBusy(str(R.string.busy_net)) {
            withContext(Dispatchers.IO) {
                StockfishNet.download(ctx.filesDir) { p -> busy = str(R.string.busy_net_progress, (p * 100).toInt()) }
            }
            toggleEngine()
        }
    }

    fun changeMultiPv(n: Int) {
        multiPv = n.coerceIn(1, 5)
        prefs.edit { putInt("multipv", multiPv) }
        engine?.setOption("MultiPV", multiPv.toString())
        analyzeCurrent()
    }

    // ------------------------------------------------------------------
    // Einstellungen

    /** Kommentare in der Zugliste anzeigen. */
    var showComments by mutableStateOf(prefs.getBoolean("showComments", true))
        private set
    /** Varianten in der Zugliste anzeigen (die Variante mit dem aktuellen Zug bleibt immer sichtbar). */
    var showVariations by mutableStateOf(prefs.getBoolean("showVariations", true))
        private set

    fun toggleComments() {
        showComments = !showComments
        prefs.edit { putBoolean("showComments", showComments) }
    }

    fun toggleVariations() {
        showVariations = !showVariations
        prefs.edit { putBoolean("showVariations", showVariations) }
    }

    /** Eigene Sprache der App gibt es erst ab Android 13; davor gilt immer die Systemsprache. */
    val languageSelectable get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Gewählte Sprache als Sprach-Tag ("de", "en"), leer = wie System. */
    var language by mutableStateOf(
        if (languageSelectable) ctx.getSystemService(LocaleManager::class.java).applicationLocales[0]?.language ?: "" else "",
    )
        private set

    /** Stellt die Sprache der App um; Android erzeugt die Oberfläche danach neu. */
    fun changeLanguage(tag: String) {
        if (!languageSelectable || tag == language) return
        language = tag
        ctx.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }

    /** Protokoll mit Engine-Kommunikation (siehe [AppLog.detailed]). */
    var logDetailed by mutableStateOf(prefs.getBoolean("logDetailed", false))
        private set

    fun toggleLogDetailed() {
        logDetailed = !logDetailed
        AppLog.detailed = logDetailed
        prefs.edit { putBoolean("logDetailed", logDetailed) }
        AppLog.i("Protokoll", if (logDetailed) "ausführlich" else "normal")
    }

    /** Inhalt des Protokolls zum Anzeigen. */
    suspend fun logText(): String = withContext(Dispatchers.IO) { AppLog.text() }

    /** Schreibt das Protokoll in das vom Nutzer gewählte Dokument [uri]. */
    fun saveLog(uri: Uri?) {
        if (uri == null) return
        runBusy(str(R.string.log_saving)) {
            withContext(Dispatchers.IO) {
                val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw LocalizedIOException(R.string.err_cannot_create, uri.lastPathSegment ?: "")
                out.use { it.write(AppLog.text().toByteArray()) }
            }
            message = str(R.string.log_saved)
        }
    }

    fun clearLog() {
        AppLog.clear()
        message = str(R.string.log_cleared)
    }

    fun changeTheme(mode: ThemeMode) {
        themeMode = mode
        prefs.edit { putString("theme", mode.name) }
    }

    fun refreshEngines() {
        viewModelScope.launch {
            oexEngines = withContext(Dispatchers.IO) {
                try {
                    OexEngines.list(ctx)
                } catch (e: Exception) {
                    AppLog.w("Engine", "Engine-Apps nicht abfragbar", e)
                    emptyList()
                }
            }
        }
    }

    /** Wählt die Engine ([OexEngine.id], leer für Stockfish); eine laufende Analyse wird neu gestartet. */
    fun selectEngine(id: String) {
        if (id == engineId) return
        engineId = id
        prefs.edit { putString("engine", id) }
        if (engineOn) {
            toggleEngine()
            toggleEngine()
        }
    }

    /** Anzeigename der gewählten Engine. */
    val engineLabel get() = selectedOex()?.name ?: "Stockfish"

    private fun selectedOex(): OexEngine? =
        if (engineId.isEmpty()) null else oexEngines.firstOrNull { it.id == engineId }

    private fun startEngine(oex: OexEngine?): UciEngine {
        val options = LinkedHashMap<String, String>()
        val command = if (oex != null) {
            OexEngines.command(ctx, oex)
        } else {
            val exe = File(ctx.applicationInfo.nativeLibraryDir, "libstockfish.so")
            if (!exe.exists()) throw LocalizedIOException(R.string.err_no_stockfish)
            options["EvalFile"] = StockfishNet.file(ctx.filesDir).absolutePath
            listOf(exe.absolutePath)
        }
        val cores = Runtime.getRuntime().availableProcessors()
        options["Threads"] = maxOf(1, minOf(4, cores - 1)).toString()
        options["Hash"] = "64"
        options["MultiPV"] = multiPv.toString()
        AppLog.i("Engine", "Starte ${oex?.let { "${it.name} (${it.packageName})" } ?: "Stockfish"}: ${command.joinToString(" ")}, $options")
        lateinit var e: UciEngine
        e = UciEngine(
            command, ctx.filesDir, options,
            onUpdate = { lines, name ->
                viewModelScope.launch(Dispatchers.Main) {
                    engineLines = lines
                    engineName = name
                }
            },
            onExit = { viewModelScope.launch(Dispatchers.Main) { engineExited(e, oex) } },
        )
        e.start()
        return e
    }

    private fun engineExited(e: UciEngine, oex: OexEngine?) {
        if (engine !== e) return
        AppLog.w("Engine", "${oex?.name ?: "Stockfish"} hat sich beendet, letzte Ausgabe: ${e.lastOutput}")
        engine = null
        engineOn = false
        engineLines = emptyList()
        message = str(R.string.engine_exited, oex?.name ?: "Stockfish") +
            e.lastOutput.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
    }

    private fun analyzeCurrent() {
        val e = engine ?: return
        val g = game ?: return
        val n = node ?: return
        val path = ArrayList<Node>()
        var p: Node? = n
        while (p != null && p.parent != null) { path.add(p); p = p.parent }
        path.reverse()
        val moves = path.map { it.move!!.uci(it.parent!!.pos) }
        engineLines = emptyList()
        if (n.pos.legalMoves().isEmpty()) {
            e.stop()
            return
        }
        e.analyze(g.startPosition.toFen(), moves, n.pos, g.startPosition.chess960)
    }

    /** Wandelt eine Engine-Variante in SAN mit Zugnummern um. */
    fun pvToSan(pv: List<String>, maxMoves: Int = 12): String {
        var pos = node?.pos?.copy() ?: return ""
        val sb = StringBuilder()
        for ((i, u) in pv.take(maxMoves).withIndex()) {
            val m = uciToMove(pos, u) ?: break
            if (pos.toMove == WHITE) sb.append(pos.fullMove).append(". ")
            else if (i == 0) sb.append(pos.fullMove).append("… ")
            sb.append(pos.toSan(m)).append(' ')
            pos = pos.copy().also { it.play(m) }
        }
        return sb.toString().trim()
    }

    private fun uciToMove(pos: Position, uci: String): Move? {
        if (uci.length < 4) return null
        val from = parseSquare(uci.substring(0, 2))
        val to = parseSquare(uci.substring(2, 4))
        val promo = when (uci.getOrNull(4)) { 'q' -> 2; 'r' -> 3; 'b' -> 4; 'n' -> 5; else -> 0 }
        val legal = pos.legalMoves()
        return legal.firstOrNull { it.from == from && it.to == to && it.promo == promo && it.castle == 0 }
            ?: legal.firstOrNull { it.castle != 0 && it.from == from && (it.to == to || pos.castleRook.contains(to)) }
    }

    fun isPromotion(from: Int, to: Int): Boolean {
        val pos = node?.pos ?: return false
        return typeOf(pos.board[from]) == PAWN && (rankOf(to) == 0 || rankOf(to) == 7)
    }

    override fun onCleared() {
        engine?.quit()
        db?.close()
    }
}

