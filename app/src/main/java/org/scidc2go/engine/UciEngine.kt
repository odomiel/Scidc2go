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
package org.scidc2go.engine

import org.scidc2go.chess.Position
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.concurrent.thread

/** Eine Hauptvariante der Analyse. Bewertung aus Sicht von Weiß. */
data class EngineLine(
    val multipv: Int,
    val depth: Int,
    val scoreCp: Int?,
    val mate: Int?,
    val pv: List<String>,
) {
    fun scoreText(): String = when {
        mate != null -> if (mate > 0) "#$mate" else "#-${-mate}"
        scoreCp != null -> String.format(java.util.Locale.ROOT, "%+.2f", scoreCp / 100.0)
        else -> ""
    }
}

/**
 * Startet eine UCI-Engine als eigenen Prozess (z.B. das mitgelieferte Stockfish)
 * und liefert die Analyse über [onUpdate].
 */
class UciEngine(
    private val executable: File,
    private val options: Map<String, String>,
    private val onUpdate: (List<EngineLine>, String) -> Unit,
) {
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    @Volatile private var lines = sortedMapOf<Int, EngineLine>()
    @Volatile private var whiteToMove = true
    @Volatile private var searching = false
    /** Nach "stop" kommen noch Ausgaben der alten Suche bis zu deren "bestmove". */
    @Volatile private var pendingBestmoves = 0
    var name = "Engine"
        private set

    fun start() {
        val p = ProcessBuilder(executable.absolutePath)
            .directory(executable.parentFile)
            .redirectErrorStream(true)
            .start()
        process = p
        writer = OutputStreamWriter(p.outputStream)
        thread(name = "uci-reader", isDaemon = true) { readLoop(BufferedReader(InputStreamReader(p.inputStream))) }
        send("uci")
        for ((k, v) in options) send("setoption name $k value $v")
        send("isready")
    }

    private fun readLoop(reader: BufferedReader) {
        try {
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("id name ") -> name = line.substring(8)
                    line.startsWith("info ") && line.contains(" pv ") -> if (pendingBestmoves == 0) parseInfo(line)
                    line.startsWith("bestmove") -> if (pendingBestmoves > 0) pendingBestmoves-- else searching = false
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun parseInfo(line: String) {
        val t = line.split(' ')
        var depth = 0
        var multipv = 1
        var cp: Int? = null
        var mate: Int? = null
        var i = 1
        var pv: List<String> = emptyList()
        while (i < t.size) {
            when (t[i]) {
                "depth" -> depth = t.getOrNull(++i)?.toIntOrNull() ?: 0
                "multipv" -> multipv = t.getOrNull(++i)?.toIntOrNull() ?: 1
                "score" -> {
                    when (t.getOrNull(++i)) {
                        "cp" -> cp = t.getOrNull(++i)?.toIntOrNull()
                        "mate" -> mate = t.getOrNull(++i)?.toIntOrNull()
                    }
                }
                "pv" -> { pv = t.subList(i + 1, t.size); i = t.size }
            }
            i++
        }
        if (line.contains(" lowerbound") || line.contains(" upperbound")) return
        val sign = if (whiteToMove) 1 else -1
        val el = EngineLine(multipv, depth, cp?.times(sign), mate?.times(sign), pv)
        val m = sortedMapOf<Int, EngineLine>()
        m.putAll(lines)
        m[multipv] = el
        lines = m
        onUpdate(m.values.toList(), name)
    }

    @Synchronized
    private fun send(cmd: String) {
        try {
            writer?.apply { write(cmd); write("\n"); flush() }
        } catch (_: Exception) {
        }
    }

    /** Analysiert die Stellung (Startstellung + Züge in UCI-Notation) unbegrenzt. */
    fun analyze(startFen: String, moves: List<String>, current: Position) {
        stopSearch()
        lines = sortedMapOf()
        whiteToMove = current.toMove == org.scidc2go.chess.WHITE
        val mv = if (moves.isEmpty()) "" else " moves " + moves.joinToString(" ")
        send("position fen $startFen$mv")
        send("go infinite")
        searching = true
        onUpdate(emptyList(), name)
    }

    @Synchronized
    private fun stopSearch() {
        if (searching) {
            pendingBestmoves++
            searching = false
            send("stop")
        }
    }

    fun setOption(name: String, value: String) {
        stopSearch()
        send("setoption name $name value $value")
    }

    fun stop() = stopSearch()

    fun quit() {
        send("stop")
        send("quit")
        val p = process
        process = null
        thread(isDaemon = true) {
            try {
                Thread.sleep(500)
                p?.destroy()
            } catch (_: Exception) {
            }
        }
    }
}
