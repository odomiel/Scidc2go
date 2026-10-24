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
 * Die Formatbeschreibungen folgen dem Quelltext von Scid
 * (https://github.com/benini/scid, GPL), insbesondere bytebuf.h, game.cpp,
 * codec_scid4.cpp und codec_scid5.h.
 */
package org.scidc2go.db

import org.scidc2go.chess.BISHOP
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
import org.scidc2go.chess.BLACK
import org.scidc2go.chess.colorOf
import org.scidc2go.chess.fileOf
import org.scidc2go.chess.pieceOf
import org.scidc2go.chess.rankOf
import org.scidc2go.chess.square
import org.scidc2go.chess.typeOf
import java.io.ByteArrayOutputStream
import java.io.IOException

const val NAME_PLAYER = 0
const val NAME_EVENT = 1
const val NAME_SITE = 2
const val NAME_ROUND = 3

const val FLAG_START = 1 shl 0
const val FLAG_PROMO = 1 shl 1
const val FLAG_UPROMO = 1 shl 2
const val FLAG_DELETE = 1 shl 3

/** Ein Indexeintrag (gemeinsames Modell für Scid 3/4/5). */
class ScidIndexEntry {
    var white = 0
    var black = 0
    var event = 0
    var site = 0
    var round = 0
    var date = 0
    var eventDate = 0
    var whiteElo = 0
    var blackElo = 0
    var whiteRatingType = 0
    var blackRatingType = 0
    var result = 0
    var eco = 0
    var flags = 0
    var numHalfMoves = 0
    var offset = 0L
    var length = 0
    var storedLineCode = 0
    var finalMatSig = 0
    var homePawnCount = 0
    val homePawnData = ByteArray(8)
    var rawVariations = 0
    var rawComments = 0
    var rawNags = 0
    var chess960 = false
}

object ScidFormat {
    private val commonTags = arrayOf(
        "WhiteCountry", "BlackCountry", "Annotator", "PlyCount", "EventDate",
        "Opening", "Variation", "Setup", "Source", "SetUp",
    )
    private const val MAX_TAG_LEN = 240

    private const val ENCODE_NAG = 11
    private const val ENCODE_COMMENT = 12
    private const val ENCODE_START_MARKER = 13
    private const val ENCODE_END_MARKER = 14
    private const val ENCODE_END_GAME = 15

    /** Tags, die im Index bzw. in der Startstellung stehen und nicht als Zusatztag gespeichert werden. */
    private val indexTags = setOf(
        "Event", "Site", "Date", "Round", "White", "Black", "Result",
        "WhiteElo", "BlackElo", "ECO", "EventDate", "FEN", "SetUp",
    )

    val ratingTypeNames = arrayOf("Elo", "Rating", "Rapid", "ICCF", "USCF", "DWZ", "ECF")

    // ------------------------------------------------------------------
    // Hilfsfunktionen für Datum, ECO und Ergebnis

    fun dateToString(date: Int): String {
        if (date == 0) return "????.??.??"
        val y = date shr 9
        val m = (date shr 5) and 15
        val d = date and 31
        val ys = if (y == 0) "????" else y.toString().padStart(4, '0')
        val ms = if (m == 0) "??" else m.toString().padStart(2, '0')
        val ds = if (d == 0) "??" else d.toString().padStart(2, '0')
        return "$ys.$ms.$ds"
    }

    fun dateFromString(s: String): Int {
        val parts = s.trim().split('.', '/', '-')
        val y = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val d = parts.getOrNull(2)?.toIntOrNull() ?: 0
        if (y !in 0..2047) return 0
        return (y shl 9) or ((if (m in 1..12) m else 0) shl 5) or (if (d in 1..31) d else 0)
    }

    fun ecoToString(eco: Int): String {
        if (eco <= 0) return ""
        val code = eco - 1
        val basic = code / 131
        val sb = StringBuilder()
        sb.append('A' + basic / 100).append('0' + (basic % 100) / 10).append('0' + basic % 10)
        val ext = code % 131
        if (ext > 0) {
            sb.append('a' + (ext - 1) / 5)
            val sub = (ext - 1) % 5
            if (sub > 0) sb.append('0' + sub)
        }
        return sb.toString()
    }

    fun ecoFromString(s: String): Int {
        if (s.isEmpty()) return 0
        val c0 = s[0].uppercaseChar()
        if (c0 !in 'A'..'E') return 0
        var eco = (c0 - 'A') * 13100
        if (s.length < 2) return eco + 1
        if (!s[1].isDigit()) return 0
        eco += (s[1] - '0') * 1310
        if (s.length < 3) return eco + 1
        if (!s[2].isDigit()) return 0
        eco += (s[2] - '0') * 131
        if (s.length >= 4 && s[3] in 'a'..'z') {
            eco += 1 + (s[3] - 'a') * 5
            if (s.length >= 5 && s[4] in '1'..'4') eco += s[4] - '0'
        }
        return eco + 1
    }

    fun resultToString(r: Int) = when (r) { 1 -> "1-0"; 2 -> "0-1"; 3 -> "1/2-1/2"; else -> "*" }
    fun resultFromString(s: String) = when (s.trim()) { "1-0" -> 1; "0-1" -> 2; "1/2-1/2", "1/2", "=-=" -> 3; else -> 0 }

    private val countValues = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 30, 40, 50)

    fun rawCount(n: Int): Int {
        var best = 0
        for (i in countValues.indices) {
            if (Math.abs(countValues[i] - n) <= Math.abs(countValues[best] - n)) best = i
        }
        return best
    }

    // ------------------------------------------------------------------
    // Partie dekodieren

    fun decodeGame(data: ByteArray, ie: ScidIndexEntry, names: (Int, Int) -> String): Game {
        var p = 0
        val tags = LinkedHashMap<String, String>()
        // Zusatztags
        while (true) {
            if (p >= data.size) throw IOException("Defekte Partiedaten (Tags)")
            val tagLen = data[p++].toInt() and 0xFF
            if (tagLen == 0) break
            var tagName: String? = null
            if (tagLen > MAX_TAG_LEN) {
                val id = tagLen - MAX_TAG_LEN - 1
                if (id < commonTags.size) tagName = commonTags[id]
            } else {
                tagName = decodeText(data, p, tagLen)
                p += tagLen
            }
            val valueLen = if (tagLen != 255) (data[p++].toInt() and 0xFF) else 3
            if (p + valueLen > data.size) throw IOException("Defekte Partiedaten (Tags)")
            val value = decodeText(data, p, valueLen)
            p += valueLen
            if (tagName != null) tags[tagName] = value
        }
        // Startstellung
        val sbFlags = data[p++].toInt()
        var fen: String? = null
        if (sbFlags and 1 != 0) {
            val end = indexOf0(data, p)
            fen = String(data, p, end - p, Charsets.ISO_8859_1)
            p = end + 1
        }

        val game = if (fen != null) {
            val pos = Position.fromFen(fen) ?: throw IOException("Ungültige FEN: $fen")
            Game(fen).also { g ->
                // Scid verwendet die Figurenreihenfolge der FEN, auch für die Grundstellung
                if (!g.hasCustomStart) replaceRootPosition(g, pos)
            }
        } else {
            Game()
        }
        game.event = names(NAME_EVENT, ie.event)
        game.site = names(NAME_SITE, ie.site)
        game.date = dateToString(ie.date)
        game.round = names(NAME_ROUND, ie.round)
        game.white = names(NAME_PLAYER, ie.white)
        game.black = names(NAME_PLAYER, ie.black)
        game.result = resultToString(ie.result)
        if (ie.whiteElo > 0) game.tags["White" + ratingTypeNames.getOrElse(ie.whiteRatingType) { "Elo" }] = ie.whiteElo.toString()
        if (ie.blackElo > 0) game.tags["Black" + ratingTypeNames.getOrElse(ie.blackRatingType) { "Elo" }] = ie.blackElo.toString()
        if (ie.eco != 0) game.tags["ECO"] = ecoToString(ie.eco)
        if (ie.eventDate != 0) game.tags["EventDate"] = dateToString(ie.eventDate)
        for ((k, v) in tags) game.tags[k] = v

        p = decodeMoves(data, p, game)
        return game
    }

    private fun replaceRootPosition(g: Game, pos: Position) {
        // Die Wurzel ist unveränderlich; Figurenlisten übernehmen
        pos.list[0].copyInto(g.root.pos.list[0]); pos.list[1].copyInto(g.root.pos.list[1])
        pos.listPos.copyInto(g.root.pos.listPos)
    }

    private fun indexOf0(data: ByteArray, from: Int): Int {
        var i = from
        while (i < data.size && data[i] != 0.toByte()) i++
        if (i >= data.size) throw IOException("Defekte Partiedaten")
        return i
    }

    private class CommentTarget(var node: Node?, val pre: Boolean)

    private fun decodeMoves(data: ByteArray, start: Int, game: Game): Int {
        var p = start
        var cur: Node = game.root
        val stack = ArrayList<Node>()
        var atVarStart = false
        var pendingPre: CommentTarget? = null
        val targets = ArrayList<CommentTarget>()

        loop@ while (true) {
            if (p >= data.size) throw IOException("Defekte Partiedaten (Züge)")
            val b = data[p++].toInt() and 0xFF
            when (b) {
                ENCODE_NAG -> {
                    if (p >= data.size) throw IOException("Defekte Partiedaten (NAG)")
                    val nag = data[p++].toInt() and 0xFF
                    if (!cur.isRoot && !atVarStart) cur.nags.add(nag)
                }
                ENCODE_COMMENT -> {
                    when {
                        atVarStart -> {
                            val t = CommentTarget(null, true)
                            pendingPre = t
                            targets.add(t)
                        }
                        else -> targets.add(CommentTarget(cur, false))
                    }
                }
                ENCODE_START_MARKER -> {
                    if (cur.isRoot) throw IOException("Defekte Partiedaten (Variante)")
                    stack.add(cur)
                    cur = cur.parent!!
                    atVarStart = true
                    pendingPre = null
                }
                ENCODE_END_MARKER -> {
                    if (stack.isEmpty()) throw IOException("Defekte Partiedaten (Variantenende)")
                    cur = stack.removeAt(stack.size - 1)
                    atVarStart = false
                    pendingPre = null
                }
                ENCODE_END_GAME -> {
                    if (stack.isNotEmpty()) throw IOException("Defekte Partiedaten (Partieende)")
                    break@loop
                }
                else -> {
                    val r = decodeMove(cur.pos, b, data, p)
                    p = r.second
                    cur = cur.addChild(r.first, reuseExisting = false)
                    if (atVarStart) {
                        pendingPre?.node = cur
                        pendingPre = null
                        atVarStart = false
                    }
                }
            }
        }
        // Kommentare
        for (t in targets) {
            if (p >= data.size) break
            val end = indexOf0(data, p)
            val text = decodeText(data, p, end - p)
            p = end + 1
            val node = t.node ?: continue
            if (t.pre) node.preComment = text else node.comment = text
        }
        return p
    }

    private val pawnPromo = intArrayOf(
        EMPTY, EMPTY, EMPTY, QUEEN, QUEEN, QUEEN, ROOK, ROOK, ROOK,
        BISHOP, BISHOP, BISHOP, KNIGHT, KNIGHT, KNIGHT, EMPTY,
    )
    private val pawnDiff = intArrayOf(7, 8, 9, 7, 8, 9, 7, 8, 9, 7, 8, 9, 7, 8, 9, 16)
    private val knightDiff = intArrayOf(0, -17, -15, -10, -6, 6, 10, 15, 17, 0, 0, 0, 0, 0, 0, 0)
    private val kingDiff = intArrayOf(0, -9, -8, -7, -1, 1, 7, 8, 9)

    /** Dekodiert einen Zug; liefert Zug und neue Leseposition. */
    private fun decodeMove(pos: Position, b: Int, data: ByteArray, pIn: Int): Pair<Move, Int> {
        var p = pIn
        val c = pos.toMove
        val idx = b shr 4
        val code = b and 15
        if (idx >= pos.count[c]) throw IOException("Defekte Partiedaten (Figurenindex)")
        val from = pos.list[c][idx]
        val to: Int
        var promo = EMPTY
        when (typeOf(pos.board[from])) {
            PAWN -> {
                to = if (c == WHITE) from + pawnDiff[code] else from - pawnDiff[code]
                promo = pawnPromo[code]
            }
            BISHOP -> {
                val fd = (code and 7) - fileOf(from)
                to = if (code >= 8) from - 7 * fd else from + 9 * fd
            }
            KNIGHT -> {
                if (knightDiff[code] == 0) throw IOException("Defekter Springerzug")
                to = from + knightDiff[code]
            }
            QUEEN, ROOK -> {
                if (typeOf(pos.board[from]) == QUEEN && code == fileOf(from)) {
                    if (p >= data.size) throw IOException("Defekter Damenzug")
                    to = (data[p++].toInt() and 0xFF) - 64
                } else {
                    to = if (code >= 8) square(fileOf(from), code - 8) else square(code, rankOf(from))
                }
            }
            KING -> {
                return when (code) {
                    0 -> Move.NULL to p
                    in 1..8 -> Move(from, from + kingDiff[code]) to p
                    9, 10 -> {
                        val rank = if (c == WHITE) 0 else 7
                        Move(from, square(if (code == 10) 6 else 2, rank), castle = if (code == 10) 1 else -1) to p
                    }
                    else -> throw IOException("Defekter Königszug")
                }
            }
            else -> throw IOException("Defekte Partiedaten (keine Figur)")
        }
        if (to !in 0..63) throw IOException("Defekte Partiedaten (Zielfeld)")
        val target = pos.board[to]
        if (target != EMPTY && (typeOf(target) == KING || colorOf(target) == c)) {
            throw IOException("Defekte Partiedaten (ungültiger Zug)")
        }
        return Move(from, to, promo) to p
    }

    // ------------------------------------------------------------------
    // Partie kodieren

    class Encoded(val data: ByteArray, val entry: ScidIndexEntry, val names: Array<String>)

    /**
     * Kodiert eine Partie. [names] enthält Event, Site, White, Black, Round;
     * die Namens-IDs im Indexeintrag setzt der Aufrufer.
     */
    fun encodeGame(game: Game): Encoded {
        val out = ByteArrayOutputStream(512)
        val ie = ScidIndexEntry()

        // Zusatztags
        for ((k, v) in game.tags) {
            if (k.isEmpty() || k in indexTags) continue
            if (isRatingTag(k)) continue
            val ci = commonTags.indexOf(k)
            if (ci >= 0) {
                out.write(ci + MAX_TAG_LEN + 1)
            } else {
                val kb = k.toByteArray(Charsets.UTF_8)
                val len = minOf(kb.size, MAX_TAG_LEN)
                out.write(len); out.write(kb, 0, len)
            }
            val vb = v.toByteArray(Charsets.UTF_8)
            val vl = minOf(vb.size, 255)
            out.write(vl); out.write(vb, 0, vl)
        }
        out.write(0)

        // Hauptvariante analysieren
        val main = game.root.mainLine()
        var promoFlag = false
        var underPromo = false
        for (n in main) {
            val m = n.move!!
            if (m.promo != EMPTY) {
                promoFlag = true
                if (m.promo != QUEEN) underPromo = true
            }
        }
        // Startstellung
        var sbFlags = 0
        val custom = game.hasCustomStart
        if (custom) sbFlags += 1
        if (promoFlag) sbFlags += 2
        if (underPromo) sbFlags += 4
        out.write(sbFlags)
        if (custom) {
            out.write(game.startPosition.toFen().toByteArray(Charsets.ISO_8859_1))
            out.write(0)
        }

        // Züge
        val comments = ArrayList<String>()
        var nVars = 0
        var nNags = 0
        var nComments = 0
        if (game.root.comment.isNotEmpty()) {
            out.write(ENCODE_COMMENT); comments.add(game.root.comment); nComments++
        }
        // Die Figurenindizes werden aus einer frisch aufgebauten Stellung bestimmt,
        // genau so, wie Scid sie beim Lesen rekonstruiert.
        fun encodeLine(startParent: Node, startPos: Position) {
            var parent = startParent
            var pos = startPos
            while (parent.children.isNotEmpty()) {
                val m = parent.children[0]
                encodeMove(m.move!!, pos, out)
                for (nag in m.nags.take(8)) { out.write(ENCODE_NAG); out.write(nag and 0xFF); nNags++ }
                // Scid kennt keine Kommentare vor einem Hauptzug: an den Zugkommentar anhängen
                val mc = listOf(m.preComment, m.comment).filter { it.isNotEmpty() }.joinToString(" ")
                if (mc.isNotEmpty()) { out.write(ENCODE_COMMENT); comments.add(mc); nComments++ }
                for (v in parent.children.drop(1)) {
                    nVars++
                    out.write(ENCODE_START_MARKER)
                    if (v.preComment.isNotEmpty()) { out.write(ENCODE_COMMENT); comments.add(v.preComment); nComments++ }
                    encodeMove(v.move!!, pos, out)
                    for (nag in v.nags.take(8)) { out.write(ENCODE_NAG); out.write(nag and 0xFF); nNags++ }
                    if (v.comment.isNotEmpty()) { out.write(ENCODE_COMMENT); comments.add(v.comment); nComments++ }
                    encodeLine(v, pos.copy().also { it.play(v.move!!) })
                    out.write(ENCODE_END_MARKER)
                }
                pos = pos.copy().also { it.play(m.move!!) }
                parent = m
            }
        }
        val encStart = if (custom) Position.fromFen(game.startPosition.toFen())!! else Position.startPos()
        encodeLine(game.root, encStart)
        out.write(ENCODE_END_GAME)
        for (c in comments) {
            out.write(c.toByteArray(Charsets.UTF_8))
            out.write(0)
        }

        // Indexeintrag
        ie.date = dateFromString(game.date)
        ie.eventDate = game.tags["EventDate"]?.let { dateFromString(it) } ?: 0
        ie.result = resultFromString(game.result)
        ie.eco = game.tags["ECO"]?.let { ecoFromString(it) } ?: 0
        val (we, wt) = rating(game, "White")
        val (be, bt) = rating(game, "Black")
        ie.whiteElo = we; ie.whiteRatingType = wt
        ie.blackElo = be; ie.blackRatingType = bt
        var flags = 0
        if (custom) flags = flags or FLAG_START
        if (promoFlag) flags = flags or FLAG_PROMO
        if (underPromo) flags = flags or FLAG_UPROMO
        ie.flags = flags
        ie.chess960 = custom && game.startPosition.chess960
        ie.numHalfMoves = minOf(main.size, 1023)
        ie.rawComments = rawCount(nComments)
        ie.rawVariations = rawCount(nVars)
        ie.rawNags = rawCount(nNags)
        computeMainlineInfo(game, main, ie)

        val names = arrayOf(game.event, game.site, game.white, game.black, game.round)
        return Encoded(out.toByteArray(), ie, names)
    }

    private fun isRatingTag(k: String): Boolean {
        for (side in arrayOf("White", "Black")) {
            if (k.startsWith(side) && k.substring(side.length) in ratingTypeNames) return true
        }
        return false
    }

    private fun rating(game: Game, side: String): Pair<Int, Int> {
        for ((i, t) in ratingTypeNames.withIndex()) {
            val v = game.tags[side + t]?.trim()?.toIntOrNull() ?: continue
            if (v > 0) return minOf(v, 4000) to i
        }
        return 0 to 0
    }

    private fun encodeMove(m: Move, pos: Position, out: ByteArrayOutputStream) {
        if (m.isNull) {
            out.write(0) // Königsindex 0, Code 0
            return
        }
        val pieceNum = pos.listPos[m.from]
        var second = -1
        val code: Int = when (typeOf(pos.board[m.from])) {
            KING -> when {
                m.castle > 0 -> 10
                m.castle < 0 -> 9
                else -> intArrayOf(1, 2, 3, 0, 0, 0, 0, 9, 4, 0, 5, 10, 0, 0, 0, 0, 6, 7, 8)[m.to - m.from + 9]
            }
            QUEEN -> when {
                rankOf(m.from) == rankOf(m.to) -> fileOf(m.to)
                fileOf(m.from) == fileOf(m.to) -> 8 + rankOf(m.to)
                else -> { second = m.to + 64; fileOf(m.from) }
            }
            ROOK -> if (rankOf(m.from) == rankOf(m.to)) fileOf(m.to) else 8 + rankOf(m.to)
            BISHOP -> {
                val rd = rankOf(m.to) - rankOf(m.from)
                val fd = fileOf(m.to) - fileOf(m.from)
                if (rd * fd < 0) fileOf(m.to) + 8 else fileOf(m.to)
            }
            KNIGHT -> {
                val table = intArrayOf(1, 0, 2, 0, 0, 0, 0, 3, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 5, 0, 0, 0, 6, 0, 0, 0, 0, 7, 0, 8)
                table[m.to - m.from + 17]
            }
            else -> {
                val diff = Math.abs(m.to - m.from)
                if (diff == 16) 15 else {
                    var v = when (diff) { 7 -> 0; 8 -> 1; else -> 2 }
                    if (m.promo != EMPTY) v += 3 * (m.promo - 1)
                    v
                }
            }
        }
        out.write((code or (pieceNum shl 4)) and 0xFF)
        if (second >= 0) out.write(second)
    }

    /** Heimbauern-Daten, Materialsignatur (Scid: mainlineInfo). Stored-Line-Code bleibt 0. */
    private fun computeMainlineInfo(game: Game, main: List<Node>, ie: ScidIndexEntry) {
        val custom = game.hasCustomStart
        var hpCount = 0
        val hpVal = ByteArray(8)
        var hpOld = 0xFFFF
        if (!custom) {
            for (n in main) {
                val hpNew = hpSig(n.pos)
                val changed = hpOld - hpNew
                if (changed != 0) {
                    hpOld = hpNew
                    var c = changed
                    var idx = 0
                    while (true) { c = c shr 1; if (c == 0) break; idx++ }
                    if (hpCount < 16) {
                        val v = if (hpCount and 1 == 0) idx shl 4 else idx
                        hpVal[hpCount / 2] = (hpVal[hpCount / 2].toInt() or v).toByte()
                    }
                    hpCount++
                }
            }
        }
        ie.homePawnCount = minOf(hpCount, 16)
        hpVal.copyInto(ie.homePawnData)
        val finalPos = if (main.isEmpty()) game.root.pos else main.last().pos
        ie.finalMatSig = matSig(finalPos)
        ie.storedLineCode = 0
    }

    private fun hpSig(pos: Position): Int {
        var sig = 0
        for (f in 0..7) {
            if (pos.board[8 + f] == pieceOf(WHITE, PAWN)) sig = sig or (0x8000 shr f)
            if (pos.board[48 + f] == pieceOf(BLACK, PAWN)) sig = sig or (0x80 shr f)
        }
        return sig
    }

    private fun matSig(pos: Position): Int {
        val cnt = IntArray(16)
        for (sq in 0..63) if (pos.board[sq] != EMPTY) cnt[pos.board[sq]]++
        fun c3(p: Int) = minOf(3, cnt[p])
        var m = 0
        m = m or (c3(pieceOf(WHITE, QUEEN)) shl 22)
        m = m or (c3(pieceOf(WHITE, ROOK)) shl 20)
        m = m or (c3(pieceOf(WHITE, BISHOP)) shl 18)
        m = m or (c3(pieceOf(WHITE, KNIGHT)) shl 16)
        m = m or (cnt[pieceOf(WHITE, PAWN)] shl 12)
        m = m or (c3(pieceOf(BLACK, QUEEN)) shl 10)
        m = m or (c3(pieceOf(BLACK, ROOK)) shl 8)
        m = m or (c3(pieceOf(BLACK, BISHOP)) shl 6)
        m = m or (c3(pieceOf(BLACK, KNIGHT)) shl 4)
        m = m or cnt[pieceOf(BLACK, PAWN)]
        return m
    }
}
