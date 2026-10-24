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
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.scidc2go.chess.Game
import org.scidc2go.chess.Move
import org.scidc2go.chess.Node
import org.scidc2go.chess.PAWN
import org.scidc2go.chess.Pgn
import org.scidc2go.chess.Position
import org.scidc2go.chess.WHITE
import org.scidc2go.chess.parseSquare
import org.scidc2go.chess.rankOf
import org.scidc2go.chess.typeOf
import org.scidc2go.db.ChessDatabase
import org.scidc2go.engine.EngineLine
import org.scidc2go.engine.OexEngine
import org.scidc2go.engine.OexEngines
import org.scidc2go.engine.StockfishNet
import org.scidc2go.engine.UciEngine
import org.scidc2go.storage.DbEntry
import org.scidc2go.storage.DbFormat
import org.scidc2go.storage.SafStorage
import java.io.File

enum class Screen { FILES, GAMES, BOARD }

enum class ThemeMode(val label: String) { SYSTEM("Wie System"), LIGHT("Hell"), DARK("Dunkel") }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val prefs = app.getSharedPreferences("scidc2go", 0)

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
    var message by mutableStateOf<String?>(null)

    // Datenbank
    var db by mutableStateOf<ChessDatabase?>(null)
        private set
    private var dbEntry: DbEntry? = null
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
        refreshEngines()
        folderUri?.let { refreshFolder() }
        newGame()
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
        prefs.edit().putString("folder", uri.toString()).apply()
        refreshFolder()
    }

    fun refreshFolder() {
        val uri = folderUri ?: return
        viewModelScope.launch {
            try {
                dbEntries = withContext(Dispatchers.IO) { SafStorage.listDatabases(ctx, uri).filter { it.complete } }
            } catch (e: Exception) {
                message = "Ordner nicht lesbar: ${e.message}"
            }
        }
    }

    private fun runBusy(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = label
            try {
                block()
            } catch (e: Exception) {
                message = e.message ?: e.toString()
            } finally {
                busy = null
            }
        }
    }

    fun openDatabase(entry: DbEntry) {
        val uri = folderUri ?: return
        runBusy("Öffne ${entry.baseName} …") {
            val newDb = withContext(Dispatchers.IO) {
                SafStorage.open(ctx, uri, entry, writable = entry.format == DbFormat.SCID5) { p ->
                    busy = "Lese ${entry.baseName} … ${(p * 100).toInt()} %"
                }
            }
            setDatabase(newDb, entry)
        }
    }

    fun openPgnUri(uri: Uri) {
        runBusy("Öffne PGN …") {
            val name = queryName(uri) ?: "PGN"
            val newDb = withContext(Dispatchers.IO) {
                SafStorage.openPgn(ctx, uri, name.removeSuffix(".pgn")) { p ->
                    busy = "Lese $name … ${(p * 100).toInt()} %"
                }
            }
            setDatabase(newDb, null)
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
        runBusy("Öffne …") {
            val entries = withContext(Dispatchers.IO) { SafStorage.groupDocuments(ctx, uris) }
            entries.firstOrNull { it.complete }?.let { openEntry(null, it); return@runBusy }
            val e = entries.firstOrNull() ?: run { message = "Kein bekanntes Datenbankformat"; return@runBusy }
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
        runBusy("Öffne ${want.baseName} …") {
            val found = withContext(Dispatchers.IO) {
                SafStorage.findInTrees(ctx, want.files.values.first(), listOf(tree))
            }
            if (found == null) {
                message = "${want.baseName} liegt nicht in diesem Ordner"
                return@runBusy
            }
            openFound(found.first, found.second)
        }
    }

    /** Öffnet eine im Ordner gefundene Datenbank oder meldet, welche Dateien fehlen. */
    private suspend fun openFound(tree: Uri, entry: DbEntry) {
        if (!entry.complete) {
            message = "Es fehlen Dateien: " +
                entry.format.exts.filter { it !in entry.files }.joinToString(", ") { "${entry.baseName}.$it" }
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
                busy = "Lese ${entry.baseName} … ${(p * 100).toInt()} %"
            }
        }
        setDatabase(newDb, if (tree != null) entry else null)
    }

    private fun queryName(uri: Uri): String? =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun setDatabase(newDb: ChessDatabase, entry: DbEntry?) {
        db?.close()
        db = newDb
        dbEntry = entry
        filterText = ""
        filtered = IntArray(newDb.gameCount) { it }
        listVersion++
        screen = Screen.GAMES
    }

    fun closeDatabase() {
        db?.close()
        db = null
        dbEntry = null
        filtered = IntArray(0)
        screen = Screen.FILES
    }

    fun createScid5(name: String) {
        val uri = folderUri ?: run { message = "Bitte zuerst einen Ordner wählen"; return }
        val clean = name.trim().removeSuffix(".si5")
        if (clean.isEmpty()) return
        runBusy("Lege $clean an …") {
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
        runBusy("Lade Partie …") {
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

    /** Anfang der Variante, in der [n] liegt (null = Hauptvariante). */
    private fun variationStart(n: Node): Node? {
        var v = n
        while (v.parent != null && v.parent!!.children.indexOf(v) == 0) v = v.parent!!
        return if (v.parent == null) null else v
    }

    fun deleteVariation(n: Node) {
        val v = variationStart(n) ?: run { message = "Das ist die Hauptvariante"; return }
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
        if (!d.writable) { message = "Diese Datenbank ist schreibgeschützt"; return }
        runBusy("Speichere …") {
            val idx = withContext(Dispatchers.IO) {
                if (gameIndex >= 0 && !asNew) { d.replaceGame(gameIndex, g); gameIndex } else d.addGame(g)
            }
            gameIndex = idx
            dirty = false
            if (filterText.isEmpty()) filtered = IntArray(d.gameCount) { it } else search(filterText)
            listVersion++
            message = "Gespeichert (Partie ${idx + 1})"
        }
    }

    /** Speichert die Partie als neue Partie in eine andere Scid-5-Datenbank. */
    fun saveTo(entry: DbEntry) {
        val uri = folderUri ?: return
        val g = game ?: return
        if (entry == dbEntry && db?.writable == true) { save(asNew = true); return }
        runBusy("Speichere in ${entry.baseName} …") {
            val idx = withContext(Dispatchers.IO) {
                SafStorage.open(ctx, uri, entry, writable = true) {}.use { it.addGame(g) }
            }
            message = "In ${entry.baseName} gespeichert (Partie ${idx + 1})"
            refreshFolder()
        }
    }

    /** Legt eine neue Scid-5-Datenbank an und speichert die Partie hinein. */
    fun createAndSave(name: String) {
        val uri = folderUri ?: return
        val g = game ?: return
        val clean = name.trim().removeSuffix(".si5")
        if (clean.isEmpty()) return
        runBusy("Lege $clean an …") {
            withContext(Dispatchers.IO) { SafStorage.createScid5(ctx, uri, clean).use { it.addGame(g) } }
            message = "In neuer Datenbank $clean gespeichert"
            dbEntries = withContext(Dispatchers.IO) { SafStorage.listDatabases(ctx, uri).filter { it.complete } }
        }
    }

    fun toggleDeleted(index: Int) {
        val d = db ?: return
        if (!d.writable) { message = "Diese Datenbank ist schreibgeschützt"; return }
        runBusy("Ändere Löschmarkierung …") {
            withContext(Dispatchers.IO) { d.setDeleted(index, !d.header(index).deleted) }
            listVersion++
        }
    }

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
            message = "Kein gültiges PGN: ${e.message}"
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
            message = "Die gewählte Engine ist nicht mehr installiert, nehme Stockfish"
            selectEngine("")
        }
        if (oex == null && !StockfishNet.file(ctx.filesDir).exists()) {
            netRequest = true
            return
        }
        runBusy("Starte ${oex?.name ?: "Stockfish"} …") {
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
        runBusy("Lade Stockfish-Netz …") {
            withContext(Dispatchers.IO) {
                StockfishNet.download(ctx.filesDir) { p -> busy = "Lade Stockfish-Netz … ${(p * 100).toInt()} %" }
            }
            toggleEngine()
        }
    }

    fun changeMultiPv(n: Int) {
        multiPv = n.coerceIn(1, 5)
        prefs.edit().putInt("multipv", multiPv).apply()
        engine?.setOption("MultiPV", multiPv.toString())
        analyzeCurrent()
    }

    // ------------------------------------------------------------------
    // Einstellungen

    fun changeTheme(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString("theme", mode.name).apply()
    }

    fun refreshEngines() {
        viewModelScope.launch {
            oexEngines = withContext(Dispatchers.IO) {
                try { OexEngines.list(ctx) } catch (_: Exception) { emptyList() }
            }
        }
    }

    /** Wählt die Engine ([OexEngine.id], leer für Stockfish); eine laufende Analyse wird neu gestartet. */
    fun selectEngine(id: String) {
        if (id == engineId) return
        engineId = id
        prefs.edit().putString("engine", id).apply()
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
            if (!exe.exists()) throw IllegalStateException("Stockfish ist für dieses Gerät nicht enthalten")
            options["EvalFile"] = StockfishNet.file(ctx.filesDir).absolutePath
            listOf(exe.absolutePath)
        }
        val cores = Runtime.getRuntime().availableProcessors()
        options["Threads"] = maxOf(1, minOf(4, cores - 1)).toString()
        options["Hash"] = "64"
        options["MultiPV"] = multiPv.toString()
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
        engine = null
        engineOn = false
        engineLines = emptyList()
        message = "${oex?.name ?: "Stockfish"} wurde unerwartet beendet" +
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
        e.analyze(g.startPosition.toFen(), moves, n.pos)
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

