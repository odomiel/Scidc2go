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
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package org.scidc2go.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.input.KeyboardType
import org.scidc2go.chess.Game
import org.scidc2go.engine.StockfishNet
import org.scidc2go.storage.DbEntry

/** Dunkles Farbschema je nach Einstellung oder System. */
@Composable
fun isDarkTheme(vm: AppViewModel): Boolean = when (vm.themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun Scidc2goApp(vm: AppViewModel) {
    val dark = isDarkTheme(vm)
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(vm.message) {
            vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
        }
        BackHandler(enabled = vm.screen != Screen.FILES) {
            vm.screen = when (vm.screen) {
                Screen.BOARD -> if (vm.db != null) Screen.GAMES else Screen.FILES
                else -> Screen.FILES
            }
        }
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 840.dp
                Box(Modifier.fillMaxSize()) {
                    when {
                        vm.screen == Screen.FILES -> FilesScreen(vm, snackbar)
                        wide -> Row(Modifier.fillMaxSize()) {
                            if (vm.db != null) {
                                Box(Modifier.weight(0.38f)) { GamesScreen(vm, snackbar, compact = true) }
                                VerticalDivider()
                            }
                            Box(Modifier.weight(0.62f)) { BoardScreen(vm, snackbar, wide = true) }
                        }
                        vm.screen == Screen.GAMES -> GamesScreen(vm, snackbar, compact = false)
                        else -> BoardScreen(vm, snackbar, wide = false)
                    }
                    if (vm.netRequest) {
                        AlertDialog(
                            onDismissRequest = { vm.cancelNetRequest() },
                            title = { Text("Stockfish-Netz laden") },
                            text = {
                                Text(
                                    "Für die Analyse braucht Stockfish sein Bewertungsnetz " +
                                        "(${StockfishNet.SIZE / 1_000_000} MB). Es wird einmalig von " +
                                        "stockfishchess.org geladen, am besten im WLAN.",
                                )
                            },
                            confirmButton = { TextButton({ vm.downloadNet() }) { Text("Laden") } },
                            dismissButton = { TextButton({ vm.cancelNetRequest() }) { Text("Abbrechen") } },
                        )
                    }
                    vm.busy?.let { label ->
                        Surface(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                            tonalElevation = 6.dp,
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(label)
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// Dateien

@Composable
fun FilesScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { vm.setFolder(it) }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        vm.openDocuments(uris)
    }
    val pickSiblingFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        vm.openFromFolder(uri)
    }
    var newDbDialog by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scidc2go") },
                actions = {
                    IconButton({ vm.refreshFolder() }) { Icon(Icons.Default.Refresh, "Aktualisieren") }
                    IconButton({ pickFolder.launch(vm.folderUri) }) { Icon(Icons.Default.Folder, "Ordner wählen") }
                    IconButton({ vm.refreshEngines(); settings = true }) { Icon(Icons.Default.Settings, "Einstellungen") }
                    IconButton({ about = true }) { Icon(Icons.Default.Info, "Über") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ pickFiles.launch(arrayOf("*/*")) }) { Text("Dateien öffnen") }
                FilledTonalButton({ vm.newGame(); vm.screen = Screen.BOARD }) { Text("Brett") }
                FilledTonalButton({ newDbDialog = true }, enabled = vm.folderUri != null) { Text("Neue Scid-5-DB") }
            }
            if (vm.folderUri == null) {
                Column(Modifier.padding(24.dp)) {
                    Text("Wähle den Ordner, in dem deine Datenbanken liegen (Scid 3/4/5, Scidb, PGN). " +
                        "Oder öffne einzelne Dateien.")
                    Spacer(Modifier.height(12.dp))
                    Button({ pickFolder.launch(null) }) { Text("Ordner wählen") }
                }
            } else {
                Text(
                    Uri.decode(vm.folderUri.toString()).substringAfterLast(':'),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (vm.dbEntries.isEmpty()) {
                    Text("Keine Datenbanken in diesem Ordner.", Modifier.padding(16.dp))
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(vm.dbEntries, key = { it.baseName + it.format }) { e ->
                        ListItem(
                            headlineContent = { Text(e.baseName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(formatSize(e.size)) },
                            trailingContent = { AssistChip({ vm.openDatabase(e) }, { Text(e.format.label) }) },
                            modifier = Modifier.combinedClickable(onClick = { vm.openDatabase(e) }),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    if (newDbDialog) {
        NameDialog("Neue Scid-5-Datenbank", "Name", onDismiss = { newDbDialog = false }) {
            newDbDialog = false
            vm.createScid5(it)
        }
    }
    if (about) AboutDialog { about = false }
    if (settings) SettingsDialog(vm) { settings = false }
    vm.siblingRequest?.let { e ->
        AlertDialog(
            onDismissRequest = { vm.cancelSiblingRequest() },
            title = { Text(e.baseName) },
            text = {
                Text(
                    "Eine ${e.format.label}-Datenbank besteht aus " +
                        e.format.exts.joinToString(", ") { "${e.baseName}.$it" } +
                        ". Damit die App die übrigen Dateien lesen kann, gib im nächsten Schritt einmalig den Ordner " +
                        "frei („Diesen Ordner verwenden“). Danach öffnen sich Datenbanken aus diesem Ordner " +
                        "und seinen Unterordnern direkt.",
                )
            },
            confirmButton = {
                TextButton({ pickSiblingFolder.launch(e.files.values.first()) }) { Text("Weiter") }
            },
            dismissButton = { TextButton({ vm.cancelSiblingRequest() }) { Text("Abbrechen") } },
        )
    }
}

private fun formatSize(b: Long): String = when {
    b >= 1L shl 30 -> String.format("%.1f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format("%.1f MB", b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> String.format("%.0f KB", b / 1024.0)
    else -> "$b B"
}

@Composable
fun NameDialog(title: String, label: String, initial: String = "", onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton({ onOk(text) }, enabled = text.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun SettingsDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Einstellungen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Darstellung", fontWeight = FontWeight.Bold)
                for (m in ThemeMode.entries) {
                    RadioRow(m.label, vm.themeMode == m) { vm.changeTheme(m) }
                }
                Spacer(Modifier.height(12.dp))
                Text("Engine", fontWeight = FontWeight.Bold)
                RadioRow("Stockfish (eingebaut)", vm.engineId.isEmpty()) { vm.selectEngine("") }
                for (e in vm.oexEngines) {
                    RadioRow(e.name, vm.engineId == e.id) { vm.selectEngine(e.id) }
                }
                Text(
                    if (vm.oexEngines.isEmpty()) {
                        "Weitere Engines erscheinen hier, wenn Engine-Apps nach dem Open-Exchange-Standard (OEX) installiert sind."
                    } else {
                        "Weitere Engines stammen aus installierten Engine-Apps (Open Exchange)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("OK") } },
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val version = remember { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scidc2go $version") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Schachdatenbanken unterwegs: lesen von Scid 3/4/5, Scidb und PGN, schreiben von Scid 5.")
                Spacer(Modifier.height(8.dp))
                Text("Freie Software unter der GNU General Public License, Version 3 oder später. " +
                    "Ohne Gewähr. Quelltext und Lizenztext liegen dem Projekt bei.")
                Spacer(Modifier.height(8.dp))
                Text("Enthaltene freie Komponenten:", fontWeight = FontWeight.Bold)
                Text("• Stockfish – GPLv3, stockfishchess.org")
                Text("• Schachfiguren „cburnett“ von Colin M.L. Burnett – GPLv2+")
                Text("• Datenbankformate nach den Quelltexten von Scid (GPL) und Scidb (GPL)")
                Text("• AndroidX / Jetpack Compose – Apache 2.0")
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("OK") } },
    )
}

// ----------------------------------------------------------------------
// Partieliste

@Composable
fun GamesScreen(vm: AppViewModel, snackbar: SnackbarHostState, compact: Boolean) {
    val db = vm.db ?: return
    var menuFor by remember { mutableStateOf(-1) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(db.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${db.formatName} · ${vm.filtered.size} / ${db.gameCount} Partien" +
                                if (!db.writable) " · nur lesen" else "",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                navigationIcon = {
                    IconButton({ vm.closeDatabase() }) { Icon(Icons.Default.Close, "Schließen") }
                },
            )
        },
        snackbarHost = { if (!compact) SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                vm.filterText, { vm.search(it) },
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                placeholder = { Text("Spieler, Turnier, Jahr, ECO …", maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (vm.filterText.isNotEmpty()) IconButton({ vm.search("") }) { Icon(Icons.Default.Close, "Leeren") }
                },
                singleLine = true,
            )
            val filtered = vm.filtered
            val version = vm.listVersion
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered.size, key = { filtered[it] }) { pos ->
                    val idx = filtered[pos]
                    val h = remember(idx, version) { runCatching { db.header(idx) }.getOrNull() }
                    if (h != null) {
                        val selected = idx == vm.gameIndex
                        Box {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { vm.openGame(idx) },
                                        onLongClick = { menuFor = idx },
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                val deco = if (h.deleted) TextDecoration.LineThrough else null
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${idx + 1}. ${h.white}${elo(h.whiteElo)} – ${h.black}${elo(h.blackElo)}",
                                        Modifier.weight(1f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        textDecoration = deco,
                                    )
                                    Text(h.result, Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    listOf(h.event, h.site, h.date.removeSuffix(".??.??").removeSuffix(".??"),
                                        h.round.takeIf { it != "?" }?.let { "R$it" }, h.eco.takeIf { it.isNotEmpty() },
                                        if (h.plies > 0) "${(h.plies + 1) / 2} Z." else null)
                                        .filterNotNull().filter { it != "?" && it.isNotEmpty() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textDecoration = deco,
                                )
                            }
                            DropdownMenu(menuFor == idx, { menuFor = -1 }) {
                                DropdownMenuItem(
                                    { Text(if (h.deleted) "Löschmarkierung entfernen" else "Zum Löschen markieren") },
                                    enabled = db.writable,
                                    onClick = { menuFor = -1; vm.toggleDeleted(idx) },
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun elo(e: Int) = if (e > 0) " ($e)" else ""

// ----------------------------------------------------------------------
// Brett

@Composable
fun BoardScreen(vm: AppViewModel, snackbar: SnackbarHostState, wide: Boolean) {
    val game = vm.game ?: return
    val node = vm.node ?: game.root
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var tagDialog by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf(false) }
    var commentDialog by remember { mutableStateOf(false) }
    var pasteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("${game.white} – ${game.black}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOf(game.event, game.date, game.result).filter { it != "?" }.joinToString(" · ") +
                                if (vm.dirty) " · geändert" else "",
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    if (!wide || vm.db == null) {
                        IconButton({ vm.screen = if (vm.db != null) Screen.GAMES else Screen.FILES }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                        }
                    }
                },
                actions = {
                    IconButton({ saveDialog = true }) { Icon(Icons.Default.Save, "Speichern") }
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Menü") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Neue Partie") }, onClick = { menu = false; vm.newGame() })
                        DropdownMenuItem({ Text("Partiedaten bearbeiten") }, onClick = { menu = false; tagDialog = true })
                        DropdownMenuItem({ Text("Kommentar zum Zug") }, enabled = !node.isRoot || true,
                            onClick = { menu = false; commentDialog = true })
                        DropdownMenuItem({ Text("Variante zur Hauptvariante") }, enabled = !node.isRoot,
                            onClick = { menu = false; vm.promoteVariation(node) })
                        DropdownMenuItem({ Text("Variante löschen") }, enabled = !node.isRoot,
                            onClick = { menu = false; vm.deleteVariation(node) })
                        DropdownMenuItem({ Text("Restliche Züge ab hier löschen") }, enabled = !node.isRoot,
                            onClick = { menu = false; vm.truncateFrom(node) })
                        DropdownMenuItem({ Text("PGN kopieren") }, onClick = {
                            menu = false
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("PGN", vm.pgnText()))
                            vm.message = "PGN in die Zwischenablage kopiert"
                        })
                        DropdownMenuItem({ Text("PGN einfügen") }, onClick = { menu = false; pasteDialog = true })
                        DropdownMenuItem({ Text("FEN kopieren") }, onClick = {
                            menu = false
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("FEN", node.pos.toFen()))
                            vm.message = "FEN kopiert"
                        })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val landscape = maxWidth > maxHeight * 1.15f
            // Im Querformat bekommt die Zugliste mindestens rund 40 % der Breite
            val boardMax = minOf(maxHeight - 56.dp, maxWidth * 0.6f)
            val board: @Composable (Modifier) -> Unit = { m ->
                BoardView(
                    pos = node.pos,
                    lastMove = node.move,
                    flipped = vm.flipped,
                    legalTargets = { vm.legalTargets(it) },
                    candidates = { f, t -> vm.candidateMoves(f, t) },
                    onMove = { vm.playMove(it) },
                    modifier = m,
                )
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxHeight().width(boardMax)) {
                        board(Modifier.weight(1f, fill = false))
                        NavBar(vm)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        EnginePanel(vm)
                        HorizontalDivider()
                        MoveListView(game, node, vm.treeVersion, { vm.goTo(it) }, Modifier.weight(1f).fillMaxWidth())
                        AnnotationBar(vm)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    board(Modifier.fillMaxWidth())
                    NavBar(vm)
                    EnginePanel(vm)
                    HorizontalDivider()
                    MoveListView(game, node, vm.treeVersion, { vm.goTo(it) }, Modifier.weight(1f).fillMaxWidth())
                    AnnotationBar(vm)
                }
            }
        }
    }

    if (tagDialog) TagDialog(game, onDismiss = { tagDialog = false }) { tagDialog = false; vm.updateTags(it) }
    if (saveDialog) SaveDialog(vm) { saveDialog = false }
    if (commentDialog) {
        NameDialog("Kommentar", "Text", node.comment, onDismiss = { commentDialog = false }) {
            commentDialog = false
            vm.setComment(node, it)
        }
    }
    if (pasteDialog) {
        PasteDialog(onDismiss = { pasteDialog = false }) { pasteDialog = false; vm.importPgnText(it) }
    }
}

@Composable
private fun NavBar(vm: AppViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        IconButton({ vm.toStart() }) { Icon(Icons.Default.SkipPrevious, "Anfang") }
        IconButton({ vm.back() }) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, "Zurück") }
        IconButton({ vm.forward() }) { Icon(Icons.AutoMirrored.Filled.NavigateNext, "Vor") }
        IconButton({ vm.toEnd() }) { Icon(Icons.Default.SkipNext, "Ende") }
        IconButton({ vm.flipped = !vm.flipped }) { Icon(Icons.Default.SwapVert, "Brett drehen") }
    }
}

@Composable
private fun AnnotationBar(vm: AppViewModel) {
    val node = vm.node ?: return
    if (node.isRoot) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        for ((nag, sym) in listOf(1 to "!", 2 to "?", 3 to "!!", 4 to "??", 5 to "!?", 6 to "?!")) {
            TextButton({ vm.toggleNag(node, nag) }) {
                Text(sym, fontWeight = if (nag in node.nags) FontWeight.Bold else FontWeight.Normal,
                    color = if (nag in node.nags) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EnginePanel(vm: AppViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(vm.engineOn, { vm.toggleEngine() })
            Spacer(Modifier.width(8.dp))
            val depth = vm.engineLines.firstOrNull()?.depth
            Text(
                if (vm.engineOn) "${vm.engineName}${depth?.let { " · Tiefe $it" } ?: ""}" else "Analyse (${vm.engineLabel})",
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (vm.engineOn) {
                TextButton({ vm.changeMultiPv(vm.multiPv - 1) }, enabled = vm.multiPv > 1) { Text("−") }
                Text("${vm.multiPv}")
                TextButton({ vm.changeMultiPv(vm.multiPv + 1) }, enabled = vm.multiPv < 5) { Text("+") }
            }
        }
        if (vm.engineOn) {
            for (l in vm.engineLines.take(vm.multiPv)) {
                Row(
                    Modifier.fillMaxWidth().combinedClickable(onClick = { l.pv.firstOrNull()?.let { vm.playUci(it) } })
                        .padding(vertical = 2.dp),
                ) {
                    Text(l.scoreText(), Modifier.width(56.dp), fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(vm.pvToSan(l.pv), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun TagDialog(game: Game, onDismiss: () -> Unit, onOk: (Map<String, String>) -> Unit) {
    val keys = listOf("White", "WhiteElo", "Black", "BlackElo", "Event", "Site", "Date", "Round", "Result", "ECO", "Annotator")
    val labels = mapOf(
        "White" to "Weiß", "WhiteElo" to "Elo Weiß", "Black" to "Schwarz", "BlackElo" to "Elo Schwarz",
        "Event" to "Turnier", "Site" to "Ort", "Date" to "Datum (JJJJ.MM.TT)", "Round" to "Runde",
        "Result" to "Ergebnis (1-0, 0-1, 1/2-1/2, *)", "ECO" to "ECO", "Annotator" to "Kommentator",
    )
    val values = remember { keys.associateWith { mutableStateOf(game.tags[it] ?: "") } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Partiedaten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (k in keys) {
                    val st = values.getValue(k)
                    OutlinedTextField(
                        st.value, { st.value = it }, Modifier.fillMaxWidth(),
                        label = { Text(labels[k] ?: k) }, singleLine = true,
                        keyboardOptions = if (k.endsWith("Elo")) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                    )
                }
            }
        },
        confirmButton = { TextButton({ onOk(values.mapValues { it.value.value }) }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun SaveDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Partie speichern") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (vm.canSaveInPlace) {
                    if (vm.gameIndex >= 0) {
                        TextButton({ onDismiss(); vm.save() }) { Text("Partie ${vm.gameIndex + 1} ersetzen") }
                    }
                    TextButton({ onDismiss(); vm.save(asNew = true) }) { Text("Als neue Partie in ${vm.db?.name}") }
                    HorizontalDivider()
                }
                Text("In Scid-5-Datenbank:", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
                if (vm.folderUri == null) Text("Kein Ordner gewählt.")
                for (e: DbEntry in vm.writableTargets) {
                    TextButton({ onDismiss(); vm.saveTo(e) }) { Text(e.baseName) }
                }
                if (vm.folderUri != null) {
                    TextButton({ newName = "" }) {
                        Icon(Icons.Default.Add, null); Text("Neue Scid-5-Datenbank …")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Abbrechen") } },
    )
    if (newName != null) {
        NameDialog("Neue Scid-5-Datenbank", "Name", onDismiss = { newName = null }) { name ->
            newName = null
            onDismiss()
            vm.createAndSave(name)
        }
    }
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onOk: (String) -> Unit) {
    val ctx = LocalContext.current
    var text by remember {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        mutableStateOf(cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PGN einfügen") },
        text = {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().height(240.dp), label = { Text("PGN") })
        },
        confirmButton = { TextButton({ onOk(text) }, enabled = text.isNotBlank()) { Text("Laden") } },
        dismissButton = { TextButton(onDismiss) { Text("Abbrechen") } },
    )
}
