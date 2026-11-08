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

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.input.KeyboardType
import org.scidc2go.R
import org.scidc2go.chess.Game
import org.scidc2go.chess.Opening
import org.scidc2go.chess.Pgn
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
        val ctx = LocalContext.current
        val res = LocalResources.current
        LaunchedEffect(vm.shareIntent) {
            val intent = vm.shareIntent ?: return@LaunchedEffect
            vm.shareDone()
            try {
                ctx.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                vm.message = res.getString(R.string.err_no_share_app)
            }
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
                            title = { Text(stringResource(R.string.net_title)) },
                            text = {
                                Text(stringResource(R.string.net_text, (StockfishNet.SIZE / 1_000_000).toInt()))
                            },
                            confirmButton = { TextButton({ vm.downloadNet() }) { Text(stringResource(R.string.net_download)) } },
                            dismissButton = { TextButton({ vm.cancelNetRequest() }) { Text(stringResource(R.string.cancel)) } },
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
                    IconButton({ vm.refreshFolder() }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
                    IconButton({ pickFolder.launch(vm.folderUri) }) { Icon(Icons.Default.Folder, stringResource(R.string.choose_folder)) }
                    IconButton({ vm.refreshEngines(); settings = true }) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                    IconButton({ about = true }) { Icon(Icons.Default.Info, stringResource(R.string.about)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ pickFiles.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.open_files)) }
                FilledTonalButton({ vm.newGame(); vm.screen = Screen.BOARD }) { Text(stringResource(R.string.board)) }
                FilledTonalButton({ newDbDialog = true }, enabled = vm.folderUri != null) { Text(stringResource(R.string.new_scid5_db_short)) }
            }
            if (vm.folderUri == null) {
                Column(Modifier.padding(24.dp)) {
                    Text(stringResource(R.string.choose_folder_hint))
                    Spacer(Modifier.height(12.dp))
                    Button({ pickFolder.launch(null) }) { Text(stringResource(R.string.choose_folder)) }
                }
            } else {
                Text(
                    Uri.decode(vm.folderUri.toString()).substringAfterLast(':'),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (vm.dbEntries.isEmpty()) {
                    Text(stringResource(R.string.no_databases), Modifier.padding(16.dp))
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(vm.dbEntries, key = { it.baseName + it.format }) { e ->
                        ListItem(
                            headlineContent = { Text(e.baseName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(formatSize(e.size, LocalConfiguration.current.locales[0])) },
                            trailingContent = { AssistChip({ vm.openDatabase(e) }, { Text(stringResource(e.format.label)) }) },
                            modifier = Modifier.combinedClickable(onClick = { vm.openDatabase(e) }),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    if (newDbDialog) {
        NameDialog(stringResource(R.string.new_scid5_db), stringResource(R.string.name), onDismiss = { newDbDialog = false }) {
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
                    stringResource(
                        R.string.sibling_text, stringResource(e.format.label),
                        e.format.exts.joinToString(", ") { "${e.baseName}.$it" },
                    ),
                )
            },
            confirmButton = {
                TextButton({ pickSiblingFolder.launch(e.files.values.first()) }) { Text(stringResource(R.string.cont)) }
            },
            dismissButton = { TextButton({ vm.cancelSiblingRequest() }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Dateigröße für die Anzeige; [locale] bestimmt das Dezimalzeichen (die Sprache der Oberfläche). */
internal fun formatSize(b: Long, locale: java.util.Locale): String = when {
    b >= 1L shl 30 -> String.format(locale, "%.1f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(locale, "%.1f MB", b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> String.format(locale, "%.0f KB", b / 1024.0)
    else -> "$b B"
}

@Composable
fun NameDialog(title: String, label: String, initial: String = "", onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton({ onOk(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun SettingsDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var showLog by remember { mutableStateOf(false) }
    val saveLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { vm.saveLog(it) }
    val stamp = remember { java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.settings_appearance), fontWeight = FontWeight.Bold)
                for (m in ThemeMode.entries) {
                    RadioRow(stringResource(m.label), vm.themeMode == m) { vm.changeTheme(m) }
                }
                if (vm.languageSelectable) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.settings_language), fontWeight = FontWeight.Bold)
                    // Sprachnamen stehen immer in ihrer eigenen Sprache
                    for ((tag, label) in listOf("" to stringResource(R.string.lang_system), "de" to "Deutsch", "en" to "English")) {
                        RadioRow(label, vm.language == tag) { vm.changeLanguage(tag) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.settings_engine), fontWeight = FontWeight.Bold)
                RadioRow(stringResource(R.string.engine_builtin), vm.engineId.isEmpty()) { vm.selectEngine("") }
                for (e in vm.oexEngines) {
                    RadioRow(e.name, vm.engineId == e.id) { vm.selectEngine(e.id) }
                }
                Text(
                    if (vm.oexEngines.isEmpty()) {
                        stringResource(R.string.oex_hint_none)
                    } else {
                        stringResource(R.string.oex_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.settings_log), fontWeight = FontWeight.Bold)
                Row(
                    Modifier.fillMaxWidth().toggleable(vm.logDetailed, onValueChange = { vm.toggleLogDetailed() })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.log_detailed), Modifier.weight(1f))
                    Switch(vm.logDetailed, onCheckedChange = null)
                }
                Row {
                    TextButton({ showLog = true }) { Text(stringResource(R.string.log_show)) }
                    TextButton({ saveLog.launch("scidc2go-log-$stamp.txt") }) { Text(stringResource(R.string.log_save)) }
                    TextButton({ vm.clearLog() }) { Text(stringResource(R.string.log_clear)) }
                }
                Text(
                    stringResource(R.string.log_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.ok)) } },
    )
    if (showLog) LogDialog(vm) { showLog = false }
}

/** Zeigt das Protokoll (das Ende, bei sehr langen Protokollen nur die letzten 100 000 Zeichen). */
@Composable
private fun LogDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) { text = vm.logText().takeLast(100_000) }
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_log)) },
        text = {
            val t = text
            when {
                t == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                t.isEmpty() -> Text(stringResource(R.string.log_empty))
                else -> SelectionContainer {
                    Text(
                        t, Modifier.verticalScroll(scroll),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
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
                Text(stringResource(R.string.about_text))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.about_license))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.about_components), fontWeight = FontWeight.Bold)
                Text("• Stockfish – GPLv3, stockfishchess.org")
                Text(stringResource(R.string.about_pieces))
                Text(stringResource(R.string.about_formats))
                Text("• AndroidX / Jetpack Compose – Apache 2.0")
                Text("• Apache Commons Compress – Apache 2.0, XZ for Java – 0BSD")
                Text(stringResource(R.string.about_openings))
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

// ----------------------------------------------------------------------
// Partieliste

@Composable
fun GamesScreen(vm: AppViewModel, snackbar: SnackbarHostState, compact: Boolean) {
    val db = vm.db ?: return
    var menuFor by remember { mutableIntStateOf(-1) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(db.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            pluralStringResource(R.plurals.games_subtitle, db.gameCount, db.formatName, vm.filtered.size, db.gameCount) +
                                if (!db.writable) " · " + stringResource(R.string.read_only) else "",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                navigationIcon = {
                    IconButton({ vm.closeDatabase() }) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                },
                actions = {
                    var listMenu by remember { mutableStateOf(false) }
                    IconButton({ vm.shareDatabase() }, enabled = vm.canShareDb) {
                        Icon(Icons.Default.Share, stringResource(R.string.share_db))
                    }
                    IconButton({ listMenu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.menu)) }
                    DropdownMenu(listMenu, { listMenu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.new_game)) }, onClick = {
                            listMenu = false
                            vm.newGame()
                            vm.screen = Screen.BOARD
                        })
                        HorizontalDivider()
                        DropdownMenuItem({ Text(stringResource(R.string.delete_marked)) }, enabled = db.writable,
                            onClick = { listMenu = false; vm.requestDeleteMarked() })
                        DropdownMenuItem({ Text(stringResource(R.string.compact_db)) }, enabled = vm.canCompact,
                            onClick = { listMenu = false; vm.requestCompact() })
                        DropdownMenuItem({ Text(stringResource(R.string.convert_scid5)) }, enabled = vm.canConvert,
                            onClick = { listMenu = false; vm.requestConvert() })
                        DropdownMenuItem({ Text(stringResource(R.string.pack_7z)) }, enabled = vm.canPack,
                            onClick = { listMenu = false; vm.requestPack() })
                    }
                },
            )
        },
        snackbarHost = { if (!compact) SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                vm.filterText, { vm.search(it) },
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (vm.filterText.isNotEmpty()) IconButton({ vm.search("") }) { Icon(Icons.Default.Close, stringResource(R.string.clear)) }
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
                                        if (h.plies > 0) stringResource(R.string.moves_short, (h.plies + 1) / 2) else null)
                                        .filterNotNull().filter { it != "?" && it.isNotEmpty() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textDecoration = deco,
                                )
                            }
                            DropdownMenu(menuFor == idx, { menuFor = -1 }) {
                                DropdownMenuItem(
                                    { Text(stringResource(if (h.deleted) R.string.unmark_delete else R.string.mark_delete)) },
                                    enabled = db.writable,
                                    onClick = { menuFor = -1; vm.toggleDeleted(idx) },
                                )
                                DropdownMenuItem(
                                    { Text(stringResource(R.string.delete_game)) },
                                    enabled = db.writable,
                                    onClick = { menuFor = -1; vm.requestDelete(listOf(idx)) },
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    vm.convertRequest?.let { suggested ->
        NameDialog(stringResource(R.string.convert_scid5), stringResource(R.string.new_db_name), suggested,
            onDismiss = { vm.cancelConvert() }) { vm.confirmConvert(it) }
    }
    vm.packRequest?.let { suggested ->
        NameDialog(stringResource(R.string.pack_7z), stringResource(R.string.archive_name), suggested,
            onDismiss = { vm.cancelPack() }) { vm.confirmPack(it) }
    }
    vm.compactRequest?.let { marked ->
        AlertDialog(
            onDismissRequest = { vm.cancelCompact() },
            title = { Text(stringResource(R.string.compact_title, db.name)) },
            text = {
                Text(
                    (if (marked > 0) pluralStringResource(R.plurals.compact_marked, marked, marked) + " " else "") +
                        stringResource(R.string.compact_text),
                )
            },
            confirmButton = { TextButton({ vm.confirmCompact() }) { Text(stringResource(R.string.compact)) } },
            dismissButton = { TextButton({ vm.cancelCompact() }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (vm.deleteRequest.isNotEmpty()) {
        val n = vm.deleteRequest.size
        AlertDialog(
            onDismissRequest = { vm.cancelDelete() },
            title = { Text(pluralStringResource(R.plurals.delete_title, n, n)) },
            text = {
                Text(
                    if (n == 1) {
                        val h = runCatching { db.header(vm.deleteRequest[0]) }.getOrNull()
                        stringResource(R.string.delete_one_text, vm.deleteRequest[0] + 1,
                            h?.let { " (${it.white} – ${it.black})" } ?: "", db.name)
                    } else {
                        pluralStringResource(R.plurals.delete_many_text, n, n, db.name)
                    },
                )
            },
            confirmButton = { TextButton({ vm.confirmDelete() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton({ vm.cancelDelete() }) { Text(stringResource(R.string.cancel)) } },
        )
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
    val res = LocalResources.current
    var menu by remember { mutableStateOf(false) }
    var tagDialog by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf(false) }
    var commentDialog by remember { mutableStateOf(false) }
    var pasteDialog by remember { mutableStateOf(false) }
    /** Titel in voller Länge unter der Kopfleiste zeigen (Symbol in der Kopfleiste). */
    var titleOpen by remember { mutableStateOf(false) }
    val title = "${game.white} – ${game.black}"
    val subtitle = listOf(game.event, game.date, game.result).filter { it != "?" }.joinToString(" · ") +
        if (vm.dirty) " · " + stringResource(R.string.modified) else ""
    val openings = vm.openings
    val opening = remember(node, vm.treeVersion, openings) { openings?.lookup(node) }
    // Eröffnung der ganzen Partie; ohne Treffer der ECO-Code aus den Partiedaten
    val gameOpening = remember(game, vm.treeVersion, openings) {
        openings?.lookupGame(game)?.toString() ?: game.tags["ECO"]?.takeIf { it.isNotBlank() && it != "?" }
    }

    Scaffold(
        topBar = { Column {
            TopAppBar(
                title = {
                    IconButton({ titleOpen = !titleOpen }) {
                        Icon(if (titleOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, stringResource(R.string.game_info))
                    }
                },
                navigationIcon = {
                    if (!wide || vm.db == null) {
                        IconButton({ vm.screen = if (vm.db != null) Screen.GAMES else Screen.FILES }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    var shareMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton({ shareMenu = true }) { Icon(Icons.Default.Share, stringResource(R.string.share)) }
                        DropdownMenu(shareMenu, { shareMenu = false }) {
                            DropdownMenuItem({ Text(stringResource(R.string.share_game)) },
                                onClick = { shareMenu = false; vm.shareGame() })
                            DropdownMenuItem({ Text(stringResource(R.string.share_fen)) },
                                onClick = { shareMenu = false; vm.shareFen(node.pos.toFen()) })
                        }
                    }
                    IconButton({ saveDialog = true }) { Icon(Icons.Default.Save, stringResource(R.string.save)) }
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.menu)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            { Text(stringResource(R.string.analysis_menu, vm.engineLabel)) },
                            trailingIcon = { Switch(vm.engineOn, onCheckedChange = null) },
                            onClick = { menu = false; vm.toggleEngine() },
                        )
                        DropdownMenuItem(
                            { Text(stringResource(R.string.show_comments)) },
                            trailingIcon = { Switch(vm.showComments, onCheckedChange = null) },
                            onClick = { menu = false; vm.toggleComments() },
                        )
                        DropdownMenuItem(
                            { Text(stringResource(R.string.show_variations)) },
                            trailingIcon = { Switch(vm.showVariations, onCheckedChange = null) },
                            onClick = { menu = false; vm.toggleVariations() },
                        )
                        HorizontalDivider()
                        DropdownMenuItem({ Text(stringResource(R.string.new_game)) }, onClick = { menu = false; vm.newGame() })
                        DropdownMenuItem({ Text(stringResource(R.string.edit_tags)) }, onClick = { menu = false; tagDialog = true })
                        DropdownMenuItem({ Text(stringResource(R.string.comment_move)) }, enabled = !node.isRoot || true,
                            onClick = { menu = false; commentDialog = true })
                        DropdownMenuItem({ Text(stringResource(R.string.promote_variation)) }, enabled = !node.isRoot,
                            onClick = { menu = false; vm.promoteVariation(node) })
                        DropdownMenuItem({ Text(stringResource(R.string.delete_variation)) }, enabled = !node.isRoot,
                            onClick = { menu = false; vm.deleteVariation(node) })
                        DropdownMenuItem({ Text(stringResource(R.string.truncate)) }, enabled = !node.isRoot,
                            onClick = { menu = false; vm.truncateFrom(node) })
                        DropdownMenuItem({ Text(stringResource(R.string.copy_pgn)) }, onClick = {
                            menu = false
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("PGN", vm.pgnText()))
                            vm.message = res.getString(R.string.pgn_copied)
                        })
                        DropdownMenuItem({ Text(stringResource(R.string.paste_pgn)) }, onClick = { menu = false; pasteDialog = true })
                        DropdownMenuItem({ Text(stringResource(R.string.copy_fen)) }, onClick = {
                            menu = false
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("FEN", node.pos.toFen()))
                            vm.message = res.getString(R.string.fen_copied)
                        })
                    }
                },
            )
            if (titleOpen) {
                Column(
                    Modifier.fillMaxWidth().clickable { titleOpen = false }.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.labelMedium)
                    gameOpening?.let {
                        Text(stringResource(R.string.opening_of_game, it), style = MaterialTheme.typography.labelMedium)
                    }
                }
                HorizontalDivider()
            }
        } },
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
                        OpeningLine(opening)
                    }
                    // Im Querformat ist über dem Brett kein Platz: Analyse oben neben dem Brett
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        EnginePanel(vm)
                        MoveListView(
                            game, node, vm.treeVersion, { vm.goTo(it) }, Modifier.weight(1f).fillMaxWidth(),
                            vm.showComments, vm.showVariations,
                        )
                        AnnotationBar(vm)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    EnginePanel(vm)
                    board(Modifier.fillMaxWidth())
                    NavBar(vm)
                    OpeningLine(opening)
                    HorizontalDivider()
                    MoveListView(
                        game, node, vm.treeVersion, { vm.goTo(it) }, Modifier.weight(1f).fillMaxWidth(),
                        vm.showComments, vm.showVariations,
                    )
                    AnnotationBar(vm)
                }
            }
        }
    }

    if (tagDialog) TagDialog(game, onDismiss = { tagDialog = false }) { tagDialog = false; vm.updateTags(it) }
    if (saveDialog) SaveDialog(vm) { saveDialog = false }
    if (commentDialog) {
        NameDialog(stringResource(R.string.comment), stringResource(R.string.text), node.comment, onDismiss = { commentDialog = false }) {
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
        IconButton({ vm.toStart() }) { Icon(Icons.Default.SkipPrevious, stringResource(R.string.nav_start)) }
        IconButton({ vm.back() }) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, stringResource(R.string.back)) }
        IconButton({ vm.forward() }) { Icon(Icons.AutoMirrored.Filled.NavigateNext, stringResource(R.string.nav_forward)) }
        IconButton({ vm.toEnd() }) { Icon(Icons.Default.SkipNext, stringResource(R.string.nav_end)) }
        IconButton({ vm.flipped = !vm.flipped }) { Icon(Icons.Default.SwapVert, stringResource(R.string.flip_board)) }
    }
}

/** Eröffnung der aktuellen Stellung; die Zeile bleibt auch ohne Treffer stehen, damit die Zugliste nicht springt. */
@Composable
private fun OpeningLine(opening: Opening?) {
    Text(
        opening?.let { "${it.eco}  ${it.name}" } ?: "",
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        minLines = 1, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
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

/** Analyse über dem Brett; ein- und ausgeschaltet wird sie im Menü. */
@Composable
private fun EnginePanel(vm: AppViewModel) {
    if (!vm.engineOn) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val depth = vm.engineLines.firstOrNull()?.depth
            Text(
                "${vm.engineName}${depth?.let { " · " + stringResource(R.string.depth, it) } ?: ""}",
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton({ vm.changeMultiPv(vm.multiPv - 1) }, enabled = vm.multiPv > 1) { Text("−") }
            Text("${vm.multiPv}")
            TextButton({ vm.changeMultiPv(vm.multiPv + 1) }, enabled = vm.multiPv < 5) { Text("+") }
        }
        // feste Zeilenzahl, damit das Brett darunter nicht springt
        for (i in 0 until vm.multiPv) {
            val l = vm.engineLines.getOrNull(i)
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(
                        onClick = { l?.pv?.firstOrNull()?.let { vm.playUci(it) } },
                        onLongClick = { l?.let { vm.addEngineLine(it) } },
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(l?.scoreText() ?: "", Modifier.width(56.dp), fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium)
                Text(l?.let { vm.pvToSan(it.pv) } ?: "", Modifier.weight(1f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                // kleines Symbol statt IconButton, damit die Zeilenhöhe gleich bleibt
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.add_engine_line),
                    Modifier.padding(start = 4.dp).size(24.dp)
                        .clickable(enabled = l != null) { l?.let { vm.addEngineLine(it) } },
                    tint = if (l != null) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f),
                )
            }
        }
    }
}

/** Die PGN-Ergebnisse zur Auswahl im Feld „Ergebnis“; andere Werte lassen sich von Hand eintragen. */
private val RESULTS = listOf(
    "1-0" to R.string.result_white, "0-1" to R.string.result_black,
    "1/2-1/2" to R.string.result_draw, "*" to R.string.result_unknown,
)

@Composable
private fun TagDialog(game: Game, onDismiss: () -> Unit, onOk: (Map<String, String>) -> Unit) {
    val keys = listOf("White", "WhiteElo", "Black", "BlackElo", "Event", "Site", "Date", "Round", "Result", "ECO", "Annotator")
    val labels = mapOf(
        "White" to R.string.tag_white, "WhiteElo" to R.string.tag_white_elo, "Black" to R.string.tag_black,
        "BlackElo" to R.string.tag_black_elo, "Event" to R.string.tag_event, "Site" to R.string.tag_site,
        "Date" to R.string.tag_date, "Round" to R.string.tag_round, "Result" to R.string.tag_result,
        "ECO" to R.string.tag_eco, "Annotator" to R.string.tag_annotator,
    )
    val values = remember { keys.associateWith { mutableStateOf(game.tags[it] ?: "") } }
    var datePicker by remember { mutableStateOf(false) }
    var resultMenu by remember { mutableStateOf(false) }
    if (datePicker) {
        val date = values.getValue("Date")
        // unvollständiges Datum (z.B. 2024.??.??): Kalender wenigstens im bekannten Jahr öffnen
        val year = date.value.take(4).toIntOrNull()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = Pgn.dateToMillis(date.value),
            initialDisplayedMonthMillis = Pgn.dateToMillis(date.value)
                ?: year?.let { Pgn.dateToMillis("%04d.01.01".format(java.util.Locale.ROOT, it)) },
        )
        DatePickerDialog(
            onDismissRequest = { datePicker = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { date.value = Pgn.millisToDate(it) }
                    datePicker = false
                }, enabled = state.selectedDateMillis != null) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton({ datePicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.game_data)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (k in keys) {
                    val st = values.getValue(k)
                    OutlinedTextField(
                        st.value, { st.value = it }, Modifier.fillMaxWidth(),
                        label = { Text(labels[k]?.let { stringResource(it) } ?: k) }, singleLine = true,
                        keyboardOptions = if (k.endsWith("Elo")) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                        trailingIcon = when (k) {
                            "Date" -> {
                                { IconButton({ datePicker = true }) { Icon(Icons.Default.CalendarMonth, stringResource(R.string.pick_date)) } }
                            }
                            "Result" -> {
                                {
                                    Box {
                                        IconButton({ resultMenu = true }) { Icon(Icons.Default.ArrowDropDown, stringResource(R.string.pick_result)) }
                                        DropdownMenu(resultMenu, { resultMenu = false }) {
                                            for ((r, label) in RESULTS) {
                                                DropdownMenuItem({ Text("$r  ${stringResource(label)}") }, onClick = {
                                                    st.value = r
                                                    resultMenu = false
                                                })
                                            }
                                        }
                                    }
                                }
                            }
                            else -> null
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton({ onOk(values.mapValues { it.value.value }) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SaveDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_game)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (vm.canSaveInPlace) {
                    if (vm.gameIndex >= 0) {
                        TextButton({ onDismiss(); vm.save() }) { Text(stringResource(R.string.replace_game, vm.gameIndex + 1)) }
                    }
                    TextButton({ onDismiss(); vm.save(asNew = true) }) { Text(stringResource(R.string.save_as_new, vm.db?.name ?: "")) }
                    HorizontalDivider()
                }
                Text(stringResource(R.string.into_scid5), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
                if (vm.folderUri == null) Text(stringResource(R.string.no_folder))
                for (e: DbEntry in vm.writableTargets) {
                    TextButton({ onDismiss(); vm.saveTo(e) }) { Text(e.baseName) }
                }
                if (vm.folderUri != null) {
                    TextButton({ newName = "" }) {
                        Icon(Icons.Default.Add, null); Text(stringResource(R.string.new_scid5_db_dots))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (newName != null) {
        NameDialog(stringResource(R.string.new_scid5_db), stringResource(R.string.name), onDismiss = { newName = null }) { name ->
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
        title = { Text(stringResource(R.string.paste_pgn)) },
        text = {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().height(240.dp), label = { Text("PGN") })
        },
        confirmButton = { TextButton({ onOk(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.load)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
