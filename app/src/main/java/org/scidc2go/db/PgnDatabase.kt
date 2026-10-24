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

import org.scidc2go.chess.Game
import org.scidc2go.chess.Pgn
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * PGN-Datei als Datenbank (nur lesend). Beim Öffnen werden die Partieanfänge
 * und die Kopfdaten eingelesen, die Züge erst beim Laden einer Partie.
 */
class PgnDatabase(
    override val name: String,
    private val channel: FileChannel,
    progress: (Float) -> Unit = {},
) : ChessDatabase {
    override val formatName get() = "PGN"

    private val offsets = LongArrayList()
    private val headers = ArrayList<GameHeader>()
    override val gameCount get() = headers.size

    init {
        scan(progress)
    }

    private fun scan(progress: (Float) -> Unit) {
        val size = channel.size()
        val buf = ByteBuffer.allocate(1 shl 16)
        var filePos = 0L
        var line = ByteArray(512)
        var lineLen = 0
        var lineStart = 0L
        var inHeader = false
        var tags = HashMap<String, String>()
        var lastReport = 0L

        fun finishGame() {
            if (tags.isNotEmpty()) headers.add(toHeader(tags))
            tags = HashMap()
        }

        fun handleLine() {
            var i = 0
            while (i < lineLen && (line[i] == ' '.code.toByte() || line[i] == '\t'.code.toByte() || line[i] == 0xEF.toByte() || line[i] == 0xBB.toByte() || line[i] == 0xBF.toByte())) i++
            val isTag = i < lineLen && line[i] == '['.code.toByte()
            if (isTag) {
                if (!inHeader) {
                    finishGame()
                    offsets.add(lineStart)
                    inHeader = true
                }
                val text = decodeText(line, i, lineLen - i)
                parseTag(text, tags)
            } else if (inHeader && i < lineLen) {
                inHeader = false
            } else if (!inHeader && offsets.size == 0 && i < lineLen) {
                // Partie ohne Tags am Dateianfang
                offsets.add(lineStart)
                tags["Event"] = "?"
            }
        }

        while (filePos < size) {
            buf.clear()
            val n = channel.read(buf, filePos)
            if (n <= 0) break
            val arr = buf.array()
            for (k in 0 until n) {
                val b = arr[k]
                if (b == '\n'.code.toByte()) {
                    if (lineLen > 0 && line[lineLen - 1] == '\r'.code.toByte()) lineLen--
                    handleLine()
                    lineLen = 0
                    lineStart = filePos + k + 1
                } else {
                    if (lineLen == line.size) line = line.copyOf(line.size * 2)
                    line[lineLen++] = b
                }
            }
            filePos += n
            if (filePos - lastReport > (1 shl 22)) {
                lastReport = filePos
                progress(filePos.toFloat() / size)
            }
        }
        if (lineLen > 0) handleLine()
        finishGame()
        offsets.add(size)
        // Falls am Anfang eine tagfreie Partie erkannt wurde, aber keine Tags folgten
        while (headers.size < offsets.size - 1) headers.add(toHeader(HashMap()))
    }

    private fun parseTag(text: String, tags: MutableMap<String, String>) {
        val t = Pgn.parseTags(text)
        tags.putAll(t)
    }

    private fun toHeader(t: Map<String, String>) = GameHeader(
        white = t["White"] ?: "?",
        black = t["Black"] ?: "?",
        event = t["Event"] ?: "?",
        site = t["Site"] ?: "?",
        round = t["Round"] ?: "?",
        date = t["Date"] ?: "????.??.??",
        result = t["Result"] ?: "*",
        whiteElo = t["WhiteElo"]?.toIntOrNull() ?: 0,
        blackElo = t["BlackElo"]?.toIntOrNull() ?: 0,
        eco = t["ECO"] ?: "",
        plies = t["PlyCount"]?.toIntOrNull() ?: 0,
    )

    override fun header(index: Int) = headers[index]

    override fun loadGame(index: Int): Game {
        val start = offsets[index]
        val end = offsets[index + 1]
        val bytes = synchronized(channel) { channel.readFully(start, (end - start).toInt()) }
        return Pgn.parseGame(decodeText(bytes))
    }

    override fun close() = channel.close()
}

internal class LongArrayList {
    private var data = LongArray(1024)
    var size = 0
        private set

    fun add(v: Long) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Long {
        if (i >= size) throw IndexOutOfBoundsException()
        return data[i]
    }
}
