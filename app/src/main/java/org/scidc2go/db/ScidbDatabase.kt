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
 *
 * Formatbeschreibung nach dem Quelltext von Scidb (http://scidb.sourceforge.net,
 * GPL), insbesondere src/db/sci/sci_codec.cpp, sci_decoder.cpp und
 * sci_decoder_position.cpp. Unterstützt wird die aktuelle Dateiversion 94.
 */
package org.scidc2go.db

import org.scidc2go.LocalizedIOException
import org.scidc2go.R
import org.scidc2go.Text
import org.scidc2go.chess.BISHOP
import org.scidc2go.chess.BLACK
import org.scidc2go.chess.EMPTY
import org.scidc2go.chess.Game
import org.scidc2go.chess.KING
import org.scidc2go.chess.KNIGHT
import org.scidc2go.chess.Move
import org.scidc2go.chess.Node
import org.scidc2go.chess.PAWN
import org.scidc2go.chess.Position
import org.scidc2go.chess.QUEEN
import org.scidc2go.chess.ROOK
import org.scidc2go.chess.WHITE
import org.scidc2go.chess.colorOf
import org.scidc2go.chess.fileOf
import org.scidc2go.chess.pieceOf
import org.scidc2go.chess.rankOf
import org.scidc2go.chess.square
import org.scidc2go.chess.typeOf
import java.io.IOException
import java.nio.channels.FileChannel

/** Liest Datenbanken im Scidb-Format (.sci/.scg/.scn). Nur lesend. */
class ScidbDatabase(private val files: DbFileSet) : ChessDatabase {
    override val name get() = files.baseName
    override val formatName get() = "Scidb"

    private val index: ByteArray
    override val gameCount: Int
    private val gameFile: FileChannel

    // Namensdateien: Name nach sortierter Position, plus Abbildung ID -> Position
    private val players: Array<String>
    private val playerLookup: IntArray
    private val sites: Array<String>
    private val siteLookup: IntArray
    private val events: Array<String>
    private val eventSite: IntArray
    private val eventLookup: IntArray
    private val annotators: Array<String>
    private val annotatorLookup: IntArray

    init {
        index = files.openRead("sci").use { it.readAll() }
        checkMagic(index, "Scidb.i")
        if (index.size < HEADER_END) throw LocalizedIOException(R.string.err_scidb_index)
        val version = u16(index, 8)
        if (version != FILE_VERSION) {
            throw LocalizedIOException(if (version < FILE_VERSION) R.string.err_scidb_old else R.string.err_scidb_version, version)
        }
        val numGames = u24(index, 10)
        gameCount = minOf(numGames, (index.size - HEADER_END) / ENTRY_SIZE)

        val nb = files.openRead("scn").use { it.readAll() }
        checkMagic(nb, "Scidb.n")
        if (u16(nb, 8) != version) throw LocalizedIOException(R.string.err_scidb_names_mismatch)
        val r = Reader(nb, 10)
        r.u32() // Gesamtzahl
        var pl: Pair<Array<String>, IntArray>? = null
        var si: Pair<Array<String>, IntArray>? = null
        var ev: Triple<Array<String>, IntArray, IntArray>? = null
        var an: Pair<Array<String>, IntArray>? = null
        repeat(4) {
            val tag = r.bytes(8)
            val size = r.u24()
            r.u24(); r.u24() // maxFreq, maxUsage
            val nextId = r.u24()
            when (tag[0].toInt().toChar()) {
                'p' -> pl = readSection(r, size, nextId, SectionKind.PLAYER, null)
                's' -> si = readSection(r, size, nextId, SectionKind.SITE, null)
                'e' -> {
                    val siteLk = si?.second ?: throw LocalizedIOException(R.string.err_scidb_names)
                    if (size < 0 || size.toLong() * 4 > r.end - r.p) throw LocalizedIOException(R.string.err_scidb_names)
                    val evSites = IntArray(size)
                    val res = readSection(r, size, nextId, SectionKind.EVENT, siteLk to evSites)
                    ev = Triple(res.first, res.second, evSites)
                }
                'a' -> an = readSection(r, size, nextId, SectionKind.NAME, null)
                else -> throw LocalizedIOException(R.string.err_scidb_names)
            }
        }
        players = pl!!.first; playerLookup = pl.second
        sites = si!!.first; siteLookup = si.second
        events = ev!!.first; eventLookup = ev.second; eventSite = ev.third
        annotators = an!!.first; annotatorLookup = an.second

        val g = files.openRead("scg")
        val magic = g.readFully(0, 10)
        checkMagic(magic, "Scidb.g")
        gameFile = g
    }

    private enum class SectionKind { PLAYER, SITE, EVENT, NAME }

    private fun readSection(
        r: Reader, count: Int, nextId: Int, kind: SectionKind, eventExtra: Pair<IntArray, IntArray>?,
    ): Pair<Array<String>, IntArray> {
        // Jeder Eintrag belegt mindestens 4 Bytes; größere Zahlen im Kopf sind beschädigt (und würden Speicher belegen)
        if (count < 0 || count.toLong() * 4 > r.end - r.p) throw LocalizedIOException(R.string.err_scidb_names)
        val names = Array(count) { "" }
        val lookup = IntArray(maxOf(nextId, 0)) { -1 }
        var prev = ByteArray(0)
        for (i in 0 until count) {
            val id = r.u24()
            val cur: ByteArray
            if (i == 0) {
                val len = r.u8()
                cur = r.bytes(len)
            } else {
                val prefix = r.u8()
                val len = r.u8()
                if (prefix > len || prefix > prev.size) throw LocalizedIOException(R.string.err_scidb_names)
                cur = if (kind == SectionKind.NAME || prefix < len) {
                    val b = ByteArray(len)
                    System.arraycopy(prev, 0, b, 0, prefix)
                    System.arraycopy(r.bytes(len - prefix), 0, b, prefix, len - prefix)
                    b
                } else {
                    prev
                }
            }
            prev = cur
            names[i] = decodeText(cur)
            if (id < lookup.size) lookup[id] = i
            when (kind) {
                SectionKind.PLAYER -> {
                    val flags = r.u8()
                    if (flags != 0) {
                        if (flags and 0x40 != 0) r.u16()
                        if (flags and 0x80 != 0) r.u32()
                    }
                }
                SectionKind.SITE -> r.u16()
                SectionKind.EVENT -> {
                    val siteId = r.u24()
                    val (siteLk, evSites) = eventExtra!!
                    evSites[i] = siteLk.getOrElse(siteId) { -1 }
                    val flags = r.u16()
                    if (flags != 0 && flags and 0x8000 != 0) r.u16()
                }
                SectionKind.NAME -> {}
            }
        }
        return names to lookup
    }

    private fun lookupName(names: Array<String>, lookup: IntArray, id: Int): String {
        val i = lookup.getOrElse(id) { -1 }
        return names.getOrNull(i)?.ifEmpty { "?" } ?: "?"
    }

    // ------------------------------------------------------------------
    // Index (56 Byte je Partie; die meisten 32-Bit-Felder sind Big-Endian)

    private class Entry(
        val white: Int, val black: Int, val event: Int, val annotator: Int,
        val plies: Int, val year: Int, val month: Int, val day: Int,
        val round: Int, val subround: Int, val result: Int,
        val whiteElo: Int, val blackElo: Int, val gameOffset: Long, val positionId: Int,
    )

    private fun entry(i: Int): Entry {
        if (i !in 0 until gameCount) throw IndexOutOfBoundsException()
        val b = HEADER_END + i * ENTRY_SIZE
        fun w(n: Int) = u32(index, b + n * 4)
        val w2 = w(2); val w3 = w(3); val w7 = w(7); val w8 = w(8)
        val w11 = w(11); val w12 = w(12); val w13 = w(13)
        val y10 = (w13 ushr 10) and 0x3FF
        return Entry(
            white = (w2 ushr 8) and 0xFFFFFF,
            black = ((w2 and 0xFF) shl 16) or ((w3 ushr 16) and 0xFFFF),
            event = (w8 ushr 8) and 0xFFFFFF,
            annotator = (w7 ushr 8) and 0xFFFFFF,
            plies = (w3 ushr 5) and 0x7FF,
            year = if (y10 == ZERO_YEAR_10BITS) 0 else y10 + MIN_YEAR,
            month = (w12 ushr 4) and 15,
            day = w3 and 31,
            round = w8 and 0xFF,
            subround = w11 and 0xFF,
            result = (w13 ushr 3) and 7,
            whiteElo = (w13 ushr 20) and 0xFFF,
            blackElo = (w12 ushr 20) and 0xFFF,
            gameOffset = w(6).toLong() and 0xFFFFFFFFL,
            positionId = (w11 ushr 8) and 0xFFF,
        )
    }

    private fun dateString(e: Entry): String {
        if (e.year == 0) return "????.??.??"
        val m = if (e.month == 0) "??" else e.month.toString().padStart(2, '0')
        val d = if (e.day == 0) "??" else e.day.toString().padStart(2, '0')
        return "${e.year}.$m.$d"
    }

    private fun roundString(e: Entry) = when {
        e.round == 0 -> "?"
        e.subround == 0 -> e.round.toString()
        else -> "${e.round}.${e.subround}"
    }

    private fun resultString(r: Int) = when (r) { 1 -> "1-0"; 2 -> "0-1"; 3 -> "1/2-1/2"; 4 -> "0-0"; else -> "*" }

    private fun siteOfEvent(eventId: Int): String {
        val ei = eventLookup.getOrElse(eventId) { -1 }
        val si = eventSite.getOrElse(ei) { -1 }
        return sites.getOrNull(si)?.ifEmpty { "?" } ?: "?"
    }

    override fun header(index: Int): GameHeader {
        val e = entry(index)
        return GameHeader(
            white = lookupName(players, playerLookup, e.white),
            black = lookupName(players, playerLookup, e.black),
            event = lookupName(events, eventLookup, e.event),
            site = siteOfEvent(e.event),
            round = roundString(e),
            date = dateString(e),
            result = resultString(e.result),
            whiteElo = e.whiteElo,
            blackElo = e.blackElo,
            eco = "",
            plies = e.plies,
        )
    }

    override fun loadGame(index: Int): Game {
        val e = entry(index)
        val data = synchronized(gameFile) {
            val lenBytes = gameFile.readFully(e.gameOffset, 3)
            val len = u24(lenBytes, 0)
            if (len < 3) throw LocalizedIOException(R.string.err_scidb_game)
            gameFile.readFully(e.gameOffset + 3, len - 3)
        }
        val game = ScidbGameDecoder(data).decode()
        game.event = lookupName(events, eventLookup, e.event)
        game.site = siteOfEvent(e.event)
        game.white = lookupName(players, playerLookup, e.white)
        game.black = lookupName(players, playerLookup, e.black)
        game.date = dateString(e)
        game.round = roundString(e)
        game.result = resultString(e.result)
        if (e.whiteElo > 0 && game.tags["WhiteElo"] == null) game.tags["WhiteElo"] = e.whiteElo.toString()
        if (e.blackElo > 0 && game.tags["BlackElo"] == null) game.tags["BlackElo"] = e.blackElo.toString()
        val annotator = lookupName(annotators, annotatorLookup, e.annotator)
        if (annotator != "?" && annotator.isNotEmpty()) game.tags["Annotator"] = annotator
        return game
    }

    override fun close() = gameFile.close()

    companion object {
        const val FILE_VERSION = 94
        const val ENTRY_SIZE = 56
        const val HEADER_END = 128
        const val MIN_YEAR = 1200
        const val ZERO_YEAR_10BITS = 2222 - 1200 + 1

        val TAG_NAMES = arrayOf(
        "Event", "Site", "Date", "Round", "White", "Black", "Result", "EventDate",
        "EventCountry", "EventType", "EventRounds", "EventCategory", "ECO", "Opening", "Variation", "SubVariation",
        "Variant", "SetUp", "FEN", "Idn", "Termination", "TimeControl", "TimeMode", "WhiteCountry",
        "BlackCountry", "WhiteTitle", "BlackTitle", "WhiteNA", "BlackNA", "WhiteType", "BlackType", "WhiteSex",
        "BlackSex", "WhiteFideId", "BlackFideId", "WhiteClock", "BlackClock", "WhiteElo", "WhiteRating", "WhiteRapid",
        "WhiteICCF", "WhiteUSCF", "WhiteDWZ", "WhiteECF", "WhiteIPS", "BlackElo", "BlackRating", "BlackRapid",
        "BlackICCF", "BlackUSCF", "BlackDWZ", "BlackECF", "BlackIPS", "WhiteTeam", "BlackTeam", "WhiteTeamCountry",
        "BlackTeamCountry", "Annotator", "Mode", "Source", "SourceDate", "PlyCount", "Remark", "Board",
        "FICSGamesDBGameNo", "Lag", "WhiteDays", "BlackDays", "WhiteRatingDiff", "BlackRatingDiff", "", "",
        "", "", "", "", "", "", "", "",
        "", "", "", "BughouseDBGameNo", "WhiteA", "WhiteB", "WhiteACountry", "WhiteBCountry",
        "WhiteATitle", "WhiteBTitle", "WhiteANA", "WhiteBNA", "WhiteAType", "WhiteBType", "WhiteASex", "WhiteBSex",
        "WhiteAClock", "WhiteBClock", "WhiteAElo", "WhiteBElo", "WhiteARating", "WhiteBRating", "WhiteATeam", "WhiteBTeam",
        "WhiteATeamCountry", "WhiteBTeamCountry", "BlackA", "BlackB", "BlackACountry", "BlackBCountry", "BlackATitle", "BlackBTitle",
        "BlackANA", "BlackBNA", "BlackAType", "BlackBType", "BlackASex", "BlackBSex", "BlackAClock", "BlackBClock",
        "BlackAElo", "BlackBElo", "BlackARating", "BlackBRating", "BlackATeam", "BlackBTeam", "BlackATeamCountry", "BlackBTeamCountry",
        )

        private fun checkMagic(b: ByteArray, magic: String) {
            if (b.size < 8 || String(b, 0, 7, Charsets.ISO_8859_1) != magic) throw LocalizedIOException(R.string.err_not_scidb)
        }

        fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
        fun u24(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 16) or u16(b, p + 1)
        fun u32(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 24) or u24(b, p + 1)
    }
}

/** Sequentieller Big-Endian-Leser. */
internal class Reader(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
    private fun need(n: Int) { if (p + n > end) throw LocalizedIOException(R.string.err_data_end) }
    fun u8(): Int { need(1); return b[p++].toInt() and 0xFF }
    fun peek(): Int { need(1); return b[p].toInt() and 0xFF }
    fun u16(): Int { need(2); val v = ScidbDatabase.u16(b, p); p += 2; return v }
    fun u24(): Int { need(3); val v = ScidbDatabase.u24(b, p); p += 3; return v }
    fun u32(): Int { need(4); val v = ScidbDatabase.u32(b, p); p += 4; return v }
    fun bytes(n: Int): ByteArray { need(n); val r = b.copyOfRange(p, p + n); p += n; return r }
    fun skip(n: Int) { need(n); p += n }
    fun string(): String {
        var e = p
        while (e < end && b[e] != 0.toByte()) e++
        if (e >= end) throw LocalizedIOException(R.string.err_data_end)
        val s = decodeText(b, p, e - p)
        p = e + 1
        return s
    }
    val remaining get() = end - p
}

/** Dekodiert die Partiedaten eines Scidb-Datensatzes (sci_decoder.cpp). */
class ScidbGameDecoder(private val rec: ByteArray) {
    private val strm = Reader(rec)
    private lateinit var data: Reader
    private var text: Reader? = null

    /** Figurennummern -> Felder (0-15 Weiß, 16-31 Schwarz), wie decoder::Position. */
    private class Squares(val sq: IntArray = IntArray(32) { -1 }, val rookNum: IntArray = IntArray(4) { -1 }) {
        fun copy() = Squares(sq.copyOf(), rookNum)
    }

    /** Spiegel der Scidb-Knotenstruktur, nötig für die Zuordnung der Kommentartexte. */
    private class SItem(val node: Node?) {
        var flag = 0
        val vars = ArrayList<SLine>()
    }
    private class SLine(startNode: Node?) {
        val start = SItem(startNode)
        val items = ArrayList<SItem>()
        val end = SItem(null)
        var lastNode: Node? = null
    }

    fun decode(): Game {
        val flags = strm.u16()
        val idn = flags and 0x0FFF
        val game: Game
        val squares: Squares
        if (idn != 0) {
            val (g, s) = setupIdn(idn)
            game = g; squares = s
        } else {
            val fen = strm.string()
            game = Game(fen)
            squares = setupFen(fen, game.root.pos)
        }
        val dataOffset = strm.u24()
        if (dataOffset > rec.size) throw LocalizedIOException(R.string.err_scidb_game)
        if (flags and TEXT_SECTION != 0) {
            val size = ScidbDatabase.u24(rec, dataOffset)
            text = Reader(rec, dataOffset + 3, dataOffset + 3 + size)
            data = Reader(rec, dataOffset + 3 + size)
        } else {
            data = Reader(rec, dataOffset)
        }
        if (flags and TAG_SECTION != 0) decodeTags(game)
        if (flags and ENGINE_SECTION != 0) {
            val n = data.u8()
            repeat(n) { data.string() }
        }
        if (flags and TIMETABLE_SECTION != 0) skipTimeTable()

        val line = SLine(game.root)
        var cur = game.root
        val run = strm.u16()
        for (i in 0 until run) {
            val b = strm.u8()
            val (m, num) = decodeMove(b, cur.pos, squares)
            applySquares(squares, m, num, cur.pos.toMove)
            cur = cur.addChild(m, reuseExisting = false)
            line.items.add(SItem(cur))
        }
        line.lastNode = cur
        decodeVariation(line, cur, squares, pendingMove = null)

        text?.let { if (it.remaining > 0) assignText(line, it, game) }
        return game
    }

    private fun decodeTags(game: Game) {
        while (true) {
            val id = data.u8()
            if (id == 0) break
            if (id == EXTRA_TAG) {
                val n = data.string()
                val v = data.string()
                if (n.isNotEmpty()) game.tags[n] = v
            } else {
                val v = data.string()
                val n = ScidbDatabase.TAG_NAMES.getOrNull(id) ?: ""
                if (n.isNotEmpty() && n != "FEN" && n != "SetUp" && n != "Idn") game.tags[n] = v
            }
        }
    }

    private fun moveInfoLength(first: Int): Int {
        if (first == 0) return 1
        return when (((first shr 4) and 7) + 1) {
            2, 3, 4, 6 -> 3     // Uhrzeiten
            1, 5, 8 -> 4        // Bewertung, Millisekunden, Video
            7 -> 5              // Fernschach
            else -> 1
        }
    }

    private fun skipTimeTable() {
        if (data.peek() == 0) {
            // veraltetes Format
            val len = data.u16()
            repeat(len) { data.skip(moveInfoLength(data.peek())) }
            return
        }
        var n = data.u8()
        while (n > 0) {
            var length = 0
            while (n == 255) { length += 255; n = data.u8() }
            length += n
            repeat(length) { data.skip(moveInfoLength(data.peek())) }
            n = data.u8()
        }
    }

    private fun skipMark() {
        when (data.u8()) {
            0 -> {}
            2 -> data.string()
            1 -> { data.skip(2); if (data.u8() and 0x80 != 0) data.skip(1) }
            else -> throw LocalizedIOException(R.string.err_scidb_marker)
        }
    }

    /**
     * Wie Decoder::decodeVariation. Die Figurennummern des zuletzt gelesenen Zuges
     * werden erst vor dem nächsten Zug nachgeführt ([pendingMove]), damit eine
     * Variante an der Stellung vor diesem Zug ansetzen kann.
     */
    private fun decodeVariation(line: SLine, startNode: Node, squares: Squares, pendingMove: Triple<Move, Int, Int>?) {
        var cur = startNode
        // ausstehender Zug: (Zug, Figurennummer, Farbe am Zug)
        var pending = pendingMove
        var lastItem: SItem = if (line.items.isEmpty()) line.start else line.items.last()
        while (true) {
            val b = strm.u8()
            if (b >= SPECIAL_MOVE) {
                pending?.let { applySquares(squares, it.first, it.second, it.third) }
                val color = cur.pos.toMove
                val (m, num) = decodeMove(b, cur.pos, squares)
                cur = cur.addChild(m, reuseExisting = false)
                val item = SItem(cur)
                line.items.add(item)
                lastItem = item
                line.lastNode = cur
                pending = Triple(m, num, color)
                continue
            }
            when (b) {
                END_MARKER -> {
                    when (strm.u8()) {
                        COMMENT -> line.end.flag = data.u8()
                        END_MARKER -> {}
                        else -> throw LocalizedIOException(R.string.err_scidb_game)
                    }
                    return
                }
                START_MARKER -> {
                    if (pending == null || cur.isRoot) throw LocalizedIOException(R.string.err_scidb_game)
                    val sub = SLine(null)
                    lastItem.vars.add(sub)
                    // Die Figurennummern stehen noch vor dem ausstehenden Zug
                    decodeVariationFrom(sub, cur.parent!!, squares.copy())
                }
                NAG -> {
                    val nag = data.u8()
                    if (nag != 0 && lastItem !== line.start) cur.nags.add(nag)
                }
                MARK -> {
                    if (data.peek() and 0x80 != 0) data.skip(moveInfoLength(data.peek())) else skipMark()
                }
                COMMENT -> lastItem.flag = data.u8()
                else -> throw LocalizedIOException(R.string.err_scidb_game)
            }
        }
    }

    private fun decodeVariationFrom(sub: SLine, parent: Node, squares: Squares) {
        decodeVariation(sub, parent, squares, null)
    }

    private fun applySquares(s: Squares, m: Move, pieceNum: Int, color: Int) {
        if (m.isNull) return
        if (m.castle != 0) {
            val rank = rankOf(m.from)
            if (m.castle > 0) {
                s.sq[pieceNum] = square(6, rank)
                val rn = s.rookNum[color * 2 + 1]
                if (rn >= 0) s.sq[rn] = square(5, rank)
            } else {
                s.sq[pieceNum] = square(2, rank)
                val rn = s.rookNum[color * 2]
                if (rn >= 0) s.sq[rn] = square(3, rank)
            }
        } else {
            s.sq[pieceNum] = m.to
        }
    }

    private val kingOffsets = intArrayOf(0, 0, 0, 0, 0, 0, -9, -8, -7, -1, 1, 7, 8, 9, 0, 0)
    private val knightOffsets = intArrayOf(0, -17, -15, -10, -6, 6, 10, 15, 17, 0, 0, 0, 0, 0, 0, 0)
    private val pawnOffsets = intArrayOf(7, 8, 9, 7, 8, 9, 7, 8, 9, 7, 8, 9, 7, 8, 9, 16)
    private val promoted = intArrayOf(EMPTY, EMPTY, EMPTY, QUEEN, QUEEN, QUEEN, ROOK, ROOK, ROOK,
        BISHOP, BISHOP, BISHOP, KNIGHT, KNIGHT, KNIGHT, EMPTY)

    private fun decodeMove(value: Int, pos: Position, s: Squares): Pair<Move, Int> {
        if (value == SPECIAL_MOVE) return Move.NULL to 0
        var pieceNum = value shr 4
        val nyb = value and 15
        if (pos.toMove == BLACK) pieceNum = pieceNum or 0x10
        val from = s.sq[pieceNum]
        if (from !in 0..63) throw LocalizedIOException(R.string.err_scidb_game_detail, Text(R.string.detail_piece))
        val piece = pos.board[from]
        if (piece == EMPTY || colorOf(piece) != pos.toMove) throw LocalizedIOException(R.string.err_scidb_game_detail, Text(R.string.detail_piece))
        val m: Move = when (typeOf(piece)) {
            KING -> when (nyb) {
                14, 15 -> {
                    val rank = rankOf(from)
                    Move(from, square(if (nyb == 14) 6 else 2, rank), castle = if (nyb == 14) 1 else -1)
                }
                else -> Move(from, (from + kingOffsets[nyb]) and 63)
            }
            QUEEN -> when {
                nyb >= 8 -> Move(from, square(fileOf(from), nyb and 7))
                nyb != fileOf(from) -> Move(from, square(nyb, rankOf(from)))
                else -> Move(from, strm.u8() and 63)
            }
            ROOK -> if (nyb >= 8) Move(from, square(fileOf(from), nyb and 7)) else Move(from, square(nyb, rankOf(from)))
            BISHOP -> {
                val diff = (nyb and 7) - fileOf(from)
                Move(from, (if (nyb < 8) from + 9 * diff else from - 7 * diff) and 63)
            }
            KNIGHT -> Move(from, (from + knightOffsets[nyb]) and 63)
            PAWN -> {
                val off = pawnOffsets[nyb]
                val to = (if (pos.toMove == WHITE) from + off else from - off) and 63
                val last = if (pos.toMove == WHITE) 7 else 0
                if (nyb != 15 && rankOf(to) == last) Move(from, to, promoted[nyb].takeIf { it != EMPTY } ?: QUEEN)
                else Move(from, to)
            }
            else -> throw LocalizedIOException(R.string.err_scidb_game)
        }
        if (!m.isNull && m.castle == 0) {
            val t = pos.board[m.to]
            if (t != EMPTY && (colorOf(t) == pos.toMove || typeOf(t) == KING)) {
                throw LocalizedIOException(R.string.err_scidb_game_detail, Text(R.string.detail_move))
            }
        }
        return m to pieceNum
    }

    // ------------------------------------------------------------------
    // Kommentartexte in Scidb-Reihenfolge zuordnen (Decoder::decodeTextSection)

    private fun assignText(line: SLine, t: Reader, game: Game) {
        fun read(flag: Int): String {
            val sb = StringBuilder()
            if (flag and ANTE != 0 && t.remaining > 0) sb.append(cleanComment(t.string()))
            val post = if (flag and POST != 0 && t.remaining > 0) cleanComment(t.string()) else ""
            return listOf(sb.toString(), post).filter { it.isNotEmpty() }.joinToString(" ")
        }
        fun append(old: String, s: String) = if (old.isEmpty()) s else if (s.isEmpty()) old else "$old $s"

        fun walk(l: SLine, isMain: Boolean) {
            // Startknoten: Kommentar vor der Partie bzw. vor der Variante
            if (l.start.flag != 0) {
                val s = read(l.start.flag)
                if (isMain) game.root.comment = append(game.root.comment, s)
                else l.items.firstOrNull()?.node?.let { it.preComment = append(it.preComment, s) }
            }
            for (item in l.items) {
                if (item.flag != 0) {
                    val node = item.node!!
                    if (item.flag and ANTE != 0 && t.remaining > 0) node.preComment = append(node.preComment, cleanComment(t.string()))
                    if (item.flag and POST != 0 && t.remaining > 0) node.comment = append(node.comment, cleanComment(t.string()))
                }
                for (v in item.vars) walk(v, false)
            }
            if (l.end.flag != 0) {
                val s = read(l.end.flag)
                val last = l.lastNode
                if (last != null) last.comment = append(last.comment, s)
            }
        }
        walk(line, true)
    }

    /** Entfernt Scidbs XML-Auszeichnung aus Kommentaren. */
    private fun cleanComment(s: String): String {
        if (!s.startsWith("<xml>")) return s
        return s.replace(Regex("<[^>]*>"), "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
            .trim()
    }

    // ------------------------------------------------------------------
    // Startstellungen

    /** Standardstellung oder Chess960-Stellung nach Scharnagl-Nummer. */
    private fun setupIdn(idn: Int): Pair<Game, Squares> {
        if (idn == 518 || idn == 3398) {
            val g = Game()
            val s = Squares()
            val std = intArrayOf(4, 8, 9, 10, 11, 12, 13, 14, 15, 0, 1, 2, 3, 5, 6, 7)
            for (i in 0..15) s.sq[i] = std[i]
            // Schwarz: 16 = König e8, 17-23 = a8..h8 ohne e8, 24-31 = a7..h7
            s.sq[16] = 60
            val back = intArrayOf(0, 1, 2, 3, 5, 6, 7)
            for (i in 0..6) s.sq[17 + i] = 56 + back[i]
            for (i in 0..7) s.sq[24 + i] = 48 + i
            s.rookNum[0] = 9; s.rookNum[1] = 15; s.rookNum[2] = 17; s.rookNum[3] = 23
            return g to s
        }
        if (idn !in 1..960) throw LocalizedIOException(R.string.err_scidb_variant, idn)
        var n = idn % 960
        val rank = CharArray(8) { ' ' }
        rank[(n % 4) * 2 + 1] = 'B'; n /= 4
        rank[(n % 4) * 2] = 'B'; n /= 4
        fun empties() = (0..7).filter { rank[it] == ' ' }
        rank[empties()[n % 6]] = 'Q'; n /= 6
        val krn = arrayOf("NNRKR", "NRNKR", "NRKNR", "NRKRN", "RNNKR", "RNKNR", "RNKRN", "RKNNR", "RKNRN", "RKRNN")[n]
        empties().forEachIndexed { i, f -> rank[f] = krn[i] }
        val back = String(rank)
        val fen = "${back.lowercase()}/pppppppp/8/8/8/8/PPPPPPPP/$back w KQkq - 0 1"
        val pos = Position.fromFen(fen) ?: throw LocalizedIOException(R.string.err_invalid_960)
        val g = Game(pos.toFen())
        val s = Squares()
        s.sq[0] = g.root.pos.kingSq(WHITE)
        s.sq[16] = g.root.pos.kingSq(BLACK)
        for (i in 0..7) { s.sq[1 + i] = 8 + i; s.sq[24 + i] = 48 + i }
        var wn = 9; var bn = 17
        for (f in 0..7) {
            when (back[f]) {
                'R', 'Q', 'B', 'N' -> {
                    if (back[f] == 'R') {
                        if (s.rookNum[0] < 0) { s.rookNum[0] = wn; s.rookNum[2] = bn } else { s.rookNum[1] = wn; s.rookNum[3] = bn }
                    }
                    s.sq[wn++] = f
                    s.sq[bn++] = 56 + f
                }
            }
        }
        return g to s
    }

    /** Figurennummern für eine Stellung aus FEN (Position::setup(fen)). */
    private fun setupFen(fen: String, pos: Position): Squares {
        val s = Squares()
        var wn = 1; var bn = 17
        var i = 0
        val placement = fen.trim().substringBefore(' ')
        for (ch in placement) {
            if (i >= 64) break
            when {
                ch.isDigit() -> i += ch - '0'
                ch == '/' || ch == '~' -> {}
                else -> {
                    val sq = square(i % 8, 7 - i / 8)
                    when (ch) {
                        'K' -> s.sq[0] = sq
                        'k' -> s.sq[16] = sq
                        else -> if (ch.isUpperCase()) {
                            if (ch == 'R') {
                                if (sq == pos.castleRookSq(WHITE, true)) s.rookNum[1] = wn
                                else if (sq == pos.castleRookSq(WHITE, false)) s.rookNum[0] = wn
                            }
                            if (wn < 16) s.sq[wn++] = sq
                        } else {
                            if (ch == 'r') {
                                if (sq == pos.castleRookSq(BLACK, true)) s.rookNum[3] = bn
                                else if (sq == pos.castleRookSq(BLACK, false)) s.rookNum[2] = bn
                            }
                            if (bn < 32) s.sq[bn++] = sq
                        }
                    }
                    i++
                }
            }
        }
        return s
    }

    companion object {
        const val MARK = 0
        const val NAG = 1
        const val COMMENT = 2
        const val START_MARKER = 3
        const val END_MARKER = 4
        const val SPECIAL_MOVE = 5

        const val ANTE = 1
        const val POST = 2

        const val TEXT_SECTION = 1 shl 15
        const val TAG_SECTION = 1 shl 14
        const val ENGINE_SECTION = 1 shl 13
        const val TIMETABLE_SECTION = 1 shl 12

        const val EXTRA_TAG = 128
    }
}
