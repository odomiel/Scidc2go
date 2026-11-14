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
import java.io.IOException
import java.nio.channels.FileChannel

/**
 * Liest Datenbanken im Format Scid 3 (.si3/.sn3/.sg3) und Scid 4 (.si4/.sn4/.sg4).
 * Nur lesend.
 */
class Scid4Database(private val files: DbFileSet, private val v3: Boolean = false) : ChessDatabase {
    private val extIndex = if (v3) "si3" else "si4"
    private val extNames = if (v3) "sn3" else "sn4"
    private val extGames = if (v3) "sg3" else "sg4"

    override val name get() = files.baseName
    override val formatName get() = if (version < 400) "Scid 3" else "Scid 4"

    private val index: ByteArray
    private val version: Int
    private val entrySize: Int
    override val gameCount: Int
    private val names: Array<Array<String>>
    private val gameFile: FileChannel
    private val headerSize: Int

    init {
        index = files.openRead(extIndex).use { it.readAll() }
        if (index.size < 8 + 2 + 4 + 3 || String(index, 0, 7, Charsets.ISO_8859_1) != "Scid.si") {
            throw LocalizedIOException(R.string.err_no_scid_index)
        }
        version = u16(index, 8)
        if (version < 300 || version > 400) throw LocalizedIOException(R.string.err_scid_version, version)
        val numGames = u24(index, 14)
        entrySize = if (version < 400) 46 else 47
        val headerSize = if (version < 400) 8 + 2 + 4 + 3 + 3 + 108 else 182
        val available = (index.size - headerSize) / entrySize
        gameCount = minOf(numGames, maxOf(available, 0))
        this.headerSize = headerSize
        names = readNames(files.openRead(extNames).use { it.readAll() })
        gameFile = files.openRead(extGames)
    }

    private fun readNames(d: ByteArray): Array<Array<String>> {
        if (d.size < 44 || String(d, 0, 7, Charsets.ISO_8859_1) != "Scid.sn") throw LocalizedIOException(R.string.err_no_scid_names)
        var p = 12
        val num = IntArray(4) { u24(d, p + it * 3) }
        p += 12
        val maxFreq = IntArray(4) { u24(d, p + it * 3) }
        p += 12
        // Jeder Name belegt mindestens 4 Bytes; größere Zahlen im Kopf sind beschädigt (und würden Speicher belegen)
        if (num.sumOf { it.toLong() } * 4 > d.size) throw LocalizedIOException(R.string.err_corrupt_names)
        // beschädigte Längen laufen über das Pufferende
        return try { readNameLists(d, p, num, maxFreq) } catch (_: IndexOutOfBoundsException) {
            throw LocalizedIOException(R.string.err_corrupt_names)
        }
    }

    private fun readNameLists(d: ByteArray, start: Int, num: IntArray, maxFreq: IntArray): Array<Array<String>> {
        var p = start
        return Array(4) { nt ->
            val arr = Array(num[nt]) { "?" }
            val buf = ByteArray(256)
            var prevLen = 0
            for (i in 0 until num[nt]) {
                val id: Int
                if (num[nt] >= 65536) { id = u24(d, p); p += 3 } else { id = u16(d, p); p += 2 }
                p += when {
                    maxFreq[nt] >= 65536 -> 3
                    maxFreq[nt] >= 256 -> 2
                    else -> 1
                }
                val length = d[p++].toInt() and 0xFF
                val prefix = if (i > 0) d[p++].toInt() and 0xFF else 0
                if (prefix > length || prefix > prevLen) throw LocalizedIOException(R.string.err_corrupt_names)
                val newChars = length - prefix
                System.arraycopy(d, p, buf, prefix, newChars)
                p += newChars
                prevLen = length
                if (id < arr.size) arr[id] = decodeText(buf, 0, length)
            }
            arr
        }
    }

    private fun name(nt: Int, id: Int): String = names[nt].getOrNull(id) ?: "?"

    private fun entry(i: Int): ScidIndexEntry {
        if (i !in 0 until gameCount) throw IndexOutOfBoundsException()
        val b = index
        var p = headerSize + i * entrySize
        val ie = ScidIndexEntry()
        ie.offset = u32(b, p).toLong() and 0xFFFFFFFFL; p += 4
        val lenLow = u16(b, p); p += 2
        val lenFlags = if (version < 400) 0 else (b[p++].toInt() and 0xFF)
        ie.length = ((lenFlags and 0x80) shl 9) or lenLow
        val flags = u16(b, p); p += 2
        ie.flags = ((lenFlags and 0x3F) shl 16) or flags
        val wbHigh = b[p++].toInt() and 0xFF
        ie.white = ((wbHigh and 0xF0) shl 12) or u16(b, p); p += 2
        ie.black = ((wbHigh and 0x0F) shl 16) or u16(b, p); p += 2
        val esrHigh = b[p++].toInt() and 0xFF
        ie.event = ((esrHigh and 0xE0) shl 11) or u16(b, p); p += 2
        ie.site = ((esrHigh and 0x1C) shl 14) or u16(b, p); p += 2
        ie.round = ((esrHigh and 0x03) shl 16) or u16(b, p); p += 2
        val varCounts = u16(b, p); p += 2
        ie.rawVariations = varCounts and 15
        ie.rawComments = (varCounts shr 4) and 15
        ie.rawNags = (varCounts shr 8) and 15
        ie.result = (varCounts shr 12) and 15
        ie.eco = u16(b, p); p += 2
        val dateEdate = u32(b, p); p += 4
        ie.date = dateEdate and 0xFFFFF
        val edate = dateEdate ushr 20
        val ey = (edate shr 9) and 7
        ie.eventDate = if (ey == 0) 0 else {
            var y = ey + (ie.date shr 9)
            y = if (y < 4) 0 else y - 4
            (y shl 9) or (edate and 0x1FF)
        }
        val we = u16(b, p); p += 2
        ie.whiteElo = we and 0xFFF; ie.whiteRatingType = we shr 12
        val be = u16(b, p); p += 2
        ie.blackElo = be and 0xFFF; ie.blackRatingType = be shr 12
        val fm = u32(b, p); p += 4
        ie.finalMatSig = fm and 0xFFFFFF
        ie.storedLineCode = fm ushr 24
        val nhm = b[p++].toInt() and 0xFF
        val pd0 = b[p++].toInt() and 0xFF
        ie.numHalfMoves = ((pd0 and 0xC0) shl 2) or nhm
        ie.homePawnCount = pd0 and 0x3F
        return ie
    }

    override fun header(index: Int): GameHeader {
        val ie = entry(index)
        return GameHeader(
            white = name(NAME_PLAYER, ie.white),
            black = name(NAME_PLAYER, ie.black),
            event = name(NAME_EVENT, ie.event),
            site = name(NAME_SITE, ie.site),
            round = name(NAME_ROUND, ie.round),
            date = ScidFormat.dateToString(ie.date),
            result = ScidFormat.resultToString(ie.result),
            whiteElo = ie.whiteElo,
            blackElo = ie.blackElo,
            eco = ScidFormat.ecoToString(ie.eco),
            plies = ie.numHalfMoves,
            deleted = ie.flags and FLAG_DELETE != 0,
        )
    }

    override fun loadGame(index: Int): Game {
        val ie = entry(index)
        val data = synchronized(gameFile) { gameFile.readFully(ie.offset, ie.length) }
        return ScidFormat.decodeGame(data, ie, ::name)
    }

    override fun close() {
        gameFile.close()
    }

    companion object {
        fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
        fun u24(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 16) or u16(b, p + 1)
        fun u32(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 24) or u24(b, p + 1)
    }
}
